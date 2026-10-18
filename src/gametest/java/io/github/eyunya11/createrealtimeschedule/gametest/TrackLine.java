package io.github.eyunya11.createrealtimeschedule.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsBlock;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.content.trains.entity.RemoveTrainPacket;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.TrackGraphHelper;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;

import io.github.eyunya11.createrealtimeschedule.clock.ClockService;
import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.schedule.TrainSlotMemory;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A straight line of Create track with stations on it and a train standing at the first of them,
 * built the way a player builds one and stepped once a tick by a game test.
 *
 * <p>Create's own track blocks and station blocks make the track graph, the first station assembles
 * a train out of a bogey, train controls and a blaze burner (which is the conductor Create wants
 * before it lets a train follow a schedule), and everything after that, the schedule, the
 * navigation and the movement, is Create's. A test calls {@link #setUp()} once a tick until it
 * answers true, gives the train its schedule, and calls {@link #cleanUp()} when it ends.
 *
 * <p>Tests that use a line share the clock and Create's railways with everything else, so each must
 * have a batch of its own: tests in one batch run side by side.
 */
final class TrackLine {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The line, in the test's own coordinates. These count from the structure block, so the bottom layer of the template is at y = 1. */
    static final int TRACK_Y = 2;
    static final int TRACK_Z = 2;
    static final int FIRST_X = 0;

    private enum Phase {
        BUILD_TRACK, WAIT_FOR_GRAPH, WAIT_FOR_STATIONS, WAIT_FOR_CONDUCTOR, READY
    }

    private final GameTestHelper helper;
    private final ServerLevel level;
    private final MinecraftServer server;
    private final int lastX;
    private final int[] stationX;
    private final String[] names;
    private final long startClock;
    private final long savedOffset;
    private final UUID owner = UUID.randomUUID();
    private final GlobalStation[] stations;

    private Phase phase = Phase.BUILD_TRACK;
    private int phaseTicks;
    private Train train;

    /**
     * @param lastX    the last track block of the line; it runs from {@link #FIRST_X}
     * @param stationX where each station is, in the order they are named; the train is built at the first
     * @param names    the stations' names
     * @param startClock the clock value to put the clock at when the line is built, so that a failure reads in times that mean something
     */
    TrackLine(GameTestHelper helper, int lastX, int[] stationX, String[] names, long startClock) {
        this.helper = helper;
        this.level = helper.getLevel();
        this.server = level.getServer();
        this.lastX = lastX;
        this.stationX = stationX;
        this.names = names;
        this.startClock = startClock;
        this.savedOffset = ClockService.data(server).offset();
        this.stations = new GlobalStation[stationX.length];
    }

    Train train() {
        return train;
    }

    GlobalStation station(int index) {
        return stations[index];
    }

    BlockPos track(int x) {
        return new BlockPos(x, TRACK_Y, TRACK_Z);
    }

    private BlockPos stationBlock(int x) {
        return new BlockPos(x, TRACK_Y, TRACK_Z + 1);
    }

    /** Sets the clock to read {@code clock} on the next tick. The game test runs at the end of a tick, after the level and its trains, so the next thing to read the clock is the next tick's. */
    void clockOnNextTick(long clock) {
        long next = server.overworld().getGameTime() + 1;
        ClockService.data(server).setOffset(RealClock.offsetFor(next, clock));
    }

    Vec3 frontOfTrain() {
        return train.carriages.get(0).getLeadingPoint().getPosition(train.graph);
    }

    // ------------------------------------------------------------------ setting up

    /** One tick of setting up. True once the train stands at the first station with a driver and nothing is left to wait for. */
    boolean setUp() {
        phaseTicks++;
        switch (phase) {
            case BUILD_TRACK -> buildTrack();
            case WAIT_FOR_GRAPH -> waitForGraph();
            case WAIT_FOR_STATIONS -> waitForStations();
            case WAIT_FOR_CONDUCTOR -> waitForConductor();
            case READY -> {}
        }
        return phase == Phase.READY;
    }

    private void enter(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    private void stuckAfter(int ticks, String what) {
        if (phaseTicks > ticks)
            helper.fail(what + " after " + phaseTicks + " ticks (" + describe() + ")");
    }

    /** Where the line and its train are, for a failure message. */
    String describe() {
        StringBuilder text = new StringBuilder("setting up: " + phase + ", clock " + ScheduleTests.show(ClockService.now(server))
            + ", line starts at x mod 16 = " + Math.floorMod(helper.absolutePos(BlockPos.ZERO).getX(), 16));
        if (train == null)
            return text.append(", no train").toString();
        GlobalStation at = train.getCurrentStation();
        return text.append(", train at ").append(at == null ? "no station" : at.name)
            .append(", schedule ").append(train.runtime.state).append(" stop ").append(train.runtime.currentEntry)
            .append(", heading for ").append(train.navigation.destination == null ? "nowhere" : train.navigation.destination.name)
            .append(", speed ").append(train.speed)
            .append(", front driver ").append(train.hasForwardConductor())
            .append(", paused ").append(train.runtime.paused)
            .append(", derailed ").append(train.derailed).toString();
    }

    private void buildTrack() {
        clockOnNextTick(startClock);
        BlockState track = AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, TrackShape.XO);
        for (int x = FIRST_X; x <= lastX; x++)
            helper.setBlock(track(x), track);
        enter(Phase.WAIT_FOR_GRAPH);
    }

    /** A track block lays its part of the graph a tick after it is placed, so the stations wait for it. */
    private void waitForGraph() {
        for (int x : stationX)
            if (TrackGraphHelper.getGraphLocationAt(level, helper.absolutePos(track(x)), AxisDirection.NEGATIVE, new Vec3(1, 0, 0)) == null) {
                stuckAfter(40, "the track graph does not reach x = " + x);
                return;
            }
        for (int x : stationX)
            placeStation(x);
        enter(Phase.WAIT_FOR_STATIONS);
    }

    /**
     * Does what placing the item does: the block, and the track it points at. The arrow points the
     * way the train's body lies, so that trains running east arrive at it.
     */
    private void placeStation(int x) {
        BlockPos station = helper.absolutePos(stationBlock(x));
        BlockPos trackPos = helper.absolutePos(track(x));
        level.setBlock(station, AllBlocks.TRACK_STATION.getDefaultState(), 3);
        CompoundTag data = new CompoundTag();
        data.put("TargetTrack", NbtUtils.writeBlockPos(trackPos.subtract(station)));
        data.putBoolean("TargetDirection", false);
        level.getBlockEntity(station).loadCustomOnly(data, level.registryAccess());
    }

    private StationBlockEntity stationEntity(int x) {
        return (StationBlockEntity) level.getBlockEntity(helper.absolutePos(stationBlock(x)));
    }

    /** A station block makes its place in the graph on its first tick; then the line gets its names and its train. */
    private void waitForStations() {
        for (int x : stationX)
            if (stationEntity(x).getStation() == null) {
                stuckAfter(40, "the station at x = " + x + " has no place in the graph");
                return;
            }
        for (int i = 0; i < stationX.length; i++) {
            helper.assertTrue(stationEntity(stationX[i]).updateName(names[i]), "the station at x = " + stationX[i] + " could not be named " + names[i]);
            stations[i] = stationEntity(stationX[i]).getStation();
        }

        assembleAtFirstStation();
        train = stations[0].getPresentTrain();
        if (train == null) {
            AssemblyException problem = AssemblyException.read(stationEntity(stationX[0]).saveWithoutMetadata(level.registryAccess()), level.registryAccess());
            helper.fail("no train was assembled at " + names[0] + (problem == null ? "" : ": " + problem.component.getString()));
        }
        enter(Phase.WAIT_FOR_CONDUCTOR);
    }

    /**
     * A bogey on the track beside the station's, a blaze burner over the station's own block and
     * the train controls beyond it facing back at it, all glued together: the burner is the
     * conductor, which Create wants before it lets a train follow a schedule.
     */
    private void assembleAtFirstStation() {
        BlockPos trackFirst = track(stationX[0]);
        BlockPos bogey = trackFirst.offset(-1, 1, 0);
        BlockPos burner = trackFirst.offset(0, 1, 0);
        BlockPos controls = trackFirst.offset(1, 1, 0);

        BlockPos absoluteTrack = helper.absolutePos(trackFirst);
        BlockState trackState = level.getBlockState(absoluteTrack);
        helper.setBlock(bogey, ((ITrackBlock) trackState.getBlock()).getBogeyAnchor(level, absoluteTrack, trackState));
        helper.setBlock(burner, AllBlocks.BLAZE_BURNER.getDefaultState().setValue(BlazeBurnerBlock.HEAT_LEVEL, BlazeBurnerBlock.HeatLevel.SMOULDERING));
        helper.setBlock(controls, AllBlocks.TRAIN_CONTROLS.getDefaultState().setValue(ControlsBlock.FACING, Direction.WEST));
        level.addFreshEntity(new SuperGlueEntity(level, SuperGlueEntity.span(helper.absolutePos(bogey), helper.absolutePos(controls))));

        stationEntity(stationX[0]).assemble(owner);
    }

    /** Create finds out who is driving a tick after the train's entities are made, so the schedule waits for that. */
    private void waitForConductor() {
        if (!train.hasForwardConductor()) {
            stuckAfter(40, "the train has no driver");
            return;
        }
        enter(Phase.READY);
    }

    // ------------------------------------------------------------------ putting it all back

    /** Takes the train, the stations and the track out of Create's railways and the world, and gives the clock back. */
    void cleanUp() {
        try {
            if (train != null) {
                for (var carriage : train.carriages)
                    carriage.forEachPresentEntity(Entity::discard);
                for (GlobalStation station : stations)
                    if (station != null)
                        station.cancelReservation(train);
                Create.RAILWAYS.removeTrain(train.id);
                CatnipServices.NETWORK.sendToAllClients(new RemoveTrainPacket(train));
                TrainSlotMemory.get(server).forget(train.id);
            }
            List<Entity> leftovers = new ArrayList<>(level.getEntitiesOfClass(Entity.class, helper.getBounds().inflate(1.0), e -> !(e instanceof Player)));
            leftovers.forEach(Entity::discard);
            for (int x : stationX)
                level.setBlock(helper.absolutePos(stationBlock(x)), Blocks.AIR.defaultBlockState(), 3);
            BlockPos trackFirst = track(stationX[0]);
            for (int dx = -1; dx <= 1; dx++)
                level.setBlock(helper.absolutePos(trackFirst.offset(dx, 1, 0)), Blocks.AIR.defaultBlockState(), 3);
            for (int x = FIRST_X; x <= lastX; x++)
                level.setBlock(helper.absolutePos(track(x)), Blocks.AIR.defaultBlockState(), 3);
        } catch (RuntimeException e) {
            LOGGER.warn("[{}] could not clean up after the track test", helper.testInfo.getTestName(), e);
        } finally {
            ClockService.data(server).setOffset(savedOffset);
        }
    }
}
