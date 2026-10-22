package io.github.eyunya11.createrealtimeschedule.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;
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
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackGraphHelper;
import com.simibubi.create.content.trains.graph.TrackGraphLocation;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.simibubi.create.content.trains.track.TrackShape;

import io.github.eyunya11.createrealtimeschedule.clock.ClockService;
import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.schedule.TrainSlotMemory;
import net.createmod.catnip.data.Couple;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
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
 * navigation and the movement, is Create's. A test calls {@link #cleanUpWhenTheTestEnds()} once,
 * then {@link #setUp()} once a tick until it answers true, and gives the train its schedule.
 *
 * <p>A line can have a {@link Bend}, a curved track of Create's that takes it over to a row of blocks
 * further south, or a {@link Turn}, one that turns it south, for a test of what a curve does to a
 * train.
 *
 * <p>Tests that use a line share the clock and Create's railways with everything else, so each must
 * have a batch of its own: tests in one batch run side by side.
 */
final class TrackLine {

    /**
     * An S-bend, as a player lays one with Create's track: a curved track from the block at
     * {@code fromX} to the one at {@code toX}, after which the line runs on {@code shift} blocks further
     * south (towards +z). The track graph holds it as an edge of its own that is a turn, and a train
     * is held to its turn speed while its front is on it, as on any curve.
     */
    record Bend(int fromX, int toX, int shift) {
        Bend {
            if (!(fromX < toX && shift > 0))
                throw new IllegalArgumentException("a bend runs east and over to the south: " + fromX + " to " + toX + ", " + shift + " over");
        }
    }

    /**
     * A quarter turn to the south: a curved track from the block at {@code fromX}, where the first row
     * ends, to the block at ({@code toX}, {@code toZ}), after which the line runs south to
     * {@code lastZ}. It is a quarter circle, so {@code toZ} has to be {@code toX - fromX + TRACK_Z}: it
     * then starts and ends as far from where its two ends' lines meet.
     */
    record Turn(int fromX, int toX, int toZ, int lastZ) {
        Turn {
            if (!(fromX < toX && toZ == toX - fromX + TRACK_Z && toZ < lastZ))
                throw new IllegalArgumentException("not a quarter turn to the south: " + fromX + " to (" + toX + ", " + toZ + "), on to " + lastZ);
        }
    }

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
    /** The track block each station is beside, in the test's coordinates. */
    private final BlockPos[] stationTracks;
    private final String[] names;
    private final OptionalLong startClock;
    private final @Nullable Bend bend;
    private final @Nullable Turn turn;
    private final long savedOffset;
    private final UUID owner = UUID.randomUUID();
    private final GlobalStation[] stations;
    /** The length of the curve as Create laid it, once it is. */
    private double curveLength = Double.NaN;

    private Phase phase = Phase.BUILD_TRACK;
    private int phaseTicks;
    private Train train;

    /** A line that leaves the clock alone, for a test that does not read it. */
    TrackLine(GameTestHelper helper, int lastX, int[] stationX, String[] names) {
        this(helper, lastX, onTheRow(stationX, null), names, OptionalLong.empty(), null, null);
    }

    /** A line that puts the clock at {@code startClock} when it is built, so that a failure reads in times that mean something. */
    TrackLine(GameTestHelper helper, int lastX, int[] stationX, String[] names, long startClock) {
        this(helper, lastX, onTheRow(stationX, null), names, OptionalLong.of(startClock), null, null);
    }

    /** A line with a bend in it, which leaves the clock alone. A station past the bend is on the row it leads to. */
    TrackLine(GameTestHelper helper, int lastX, int[] stationX, String[] names, Bend bend) {
        this(helper, lastX, onTheRow(stationX, bend), names, OptionalLong.empty(), bend, null);
    }

    /**
     * A line that turns south, which leaves the clock alone, with two stations: the first on the row
     * before the turn at {@code firstX}, where the train is built, and the second on the line south of
     * it at {@code secondZ}.
     */
    TrackLine(GameTestHelper helper, Turn turn, int firstX, int secondZ, String[] names) {
        this(helper, turn.fromX(), aroundTheTurn(turn, firstX, secondZ), names, OptionalLong.empty(), null, turn);
    }

    /**
     * @param lastX         the last track block of the first row of the line; it runs from {@link #FIRST_X}
     * @param stationTracks the track block of each station, in the order they are named; the train is built at the first
     * @param names         the stations' names
     */
    private TrackLine(GameTestHelper helper, int lastX, BlockPos[] stationTracks, String[] names, OptionalLong startClock,
        @Nullable Bend bend, @Nullable Turn turn) {
        this.helper = helper;
        this.level = helper.getLevel();
        this.server = level.getServer();
        this.lastX = lastX;
        this.stationTracks = stationTracks;
        this.names = names;
        this.startClock = startClock;
        this.bend = bend;
        this.turn = turn;
        this.savedOffset = ClockService.data(server).offset();
        this.stations = new GlobalStation[stationTracks.length];
    }

    /** The track blocks of stations at these places along the first row, or the row past the bend from the end of the bend. */
    private static BlockPos[] onTheRow(int[] stationX, @Nullable Bend bend) {
        BlockPos[] tracks = new BlockPos[stationX.length];
        for (int i = 0; i < stationX.length; i++) {
            int x = stationX[i];
            if (bend != null && x > bend.fromX() && x < bend.toX())
                throw new IllegalArgumentException("a station at x = " + x + " would be under the bend, where there is no straight track");
            tracks[i] = new BlockPos(x, TRACK_Y, bend != null && x >= bend.toX() ? TRACK_Z + bend.shift() : TRACK_Z);
        }
        return tracks;
    }

    /** The track blocks of the two stations of a line that turns south: one on the row, one on the line south of the turn. */
    private static BlockPos[] aroundTheTurn(Turn turn, int firstX, int secondZ) {
        if (!(firstX < turn.fromX() && secondZ > turn.toZ() && secondZ <= turn.lastZ()))
            throw new IllegalArgumentException("the stations have to be on the row before the turn and on the straight line after it: x = "
                + firstX + ", z = " + secondZ + " for " + turn);
        return new BlockPos[] {new BlockPos(firstX, TRACK_Y, TRACK_Z), new BlockPos(turn.toX(), TRACK_Y, secondZ)};
    }

    /** The length of the line's curve as Create laid it, in blocks; known once the line is set up. */
    double curveLength() {
        return curveLength;
    }

    /**
     * Has {@link #cleanUp()} run when the test passes, fails or times out. The clock's offset is put
     * back too, so it is safe for a test to move the clock.
     */
    void cleanUpWhenTheTestEnds() {
        helper.testInfo.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo test) {}

            @Override
            public void testPassed(GameTestInfo test, GameTestRunner runner) {
                cleanUp();
            }

            @Override
            public void testFailed(GameTestInfo test, GameTestRunner runner) {
                cleanUp();
            }

            @Override
            public void testAddedForRerun(GameTestInfo oldTest, GameTestInfo newTest, GameTestRunner runner) {}
        });
    }

    Train train() {
        return train;
    }

    GlobalStation station(int index) {
        return stations[index];
    }

    /** The track block at {@code x}, on the row the line runs on there (not on the line south of a turn). */
    BlockPos track(int x) {
        return new BlockPos(x, TRACK_Y, rowZ(x));
    }

    /** The station's block: south of its track on a row, east of it on the line south of a turn. */
    private BlockPos stationBlock(int station) {
        BlockPos track = stationTracks[station];
        return southward(track) ? track.east() : track.south();
    }

    /** Whether the track block is on the line south of the turn, which runs along z. */
    private boolean southward(BlockPos track) {
        return turn != null && track.getX() == turn.toX() && track.getZ() >= turn.toZ();
    }

    private int rowZ(int x) {
        return bend != null && x >= bend.toX() ? TRACK_Z + bend.shift() : TRACK_Z;
    }

    /** Whether there is a straight track block at {@code x}: everywhere but under the bend. */
    private boolean straightAt(int x) {
        return bend == null || x <= bend.fromX() || x >= bend.toX();
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
            helper.fail(what + " after " + phaseTicks + " ticks (while setting up, in " + phase + "; " + describe() + ")");
    }

    /** Where the line and its train are, for a failure message. The caller says what stage its own test is in. */
    String describe() {
        StringBuilder text = new StringBuilder("clock " + TestTimes.show(ClockService.now(server))
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
        startClock.ifPresent(this::clockOnNextTick);
        BlockState track = AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, TrackShape.XO);
        // The ends of a curve keep it in their block entities.
        BlockState curveEnd = track.setValue(TrackBlock.HAS_BE, true);
        for (int x = FIRST_X; x <= lastX; x++) {
            if (!straightAt(x))
                continue;
            boolean endsACurve = bend != null && (x == bend.fromX() || x == bend.toX()) || turn != null && x == turn.fromX();
            helper.setBlock(track(x), endsACurve ? curveEnd : track);
        }
        if (bend != null)
            layCurve(track(bend.fromX()), new Vec3(1, 0, 0), track(bend.toX()), new Vec3(-1, 0, 0));
        if (turn != null) {
            BlockState southward = AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, TrackShape.ZO);
            for (int z = turn.toZ(); z <= turn.lastZ(); z++)
                helper.setBlock(new BlockPos(turn.toX(), TRACK_Y, z), z == turn.toZ() ? southward.setValue(TrackBlock.HAS_BE, true) : southward);
            layCurve(track(turn.fromX()), new Vec3(1, 0, 0), new BlockPos(turn.toX(), TRACK_Y, turn.toZ()), new Vec3(0, 0, -1));
        }
        enter(Phase.WAIT_FOR_GRAPH);
    }

    /**
     * Does what {@code TrackPlacement} does when a player joins two ends with the track item: a
     * {@link BezierConnection} from the face of the one block to the face of the other, each end
     * pointing at the other, kept by the block entities at both ends.
     *
     * @param fromAxis the way out of the first block into the curve
     * @param toAxis   the way out of the second block into the curve
     */
    private void layCurve(BlockPos fromTrack, Vec3 fromAxis, BlockPos toTrack, Vec3 toAxis) {
        BlockPos from = helper.absolutePos(fromTrack);
        BlockPos to = helper.absolutePos(toTrack);
        BlockState fromState = level.getBlockState(from);
        BlockState toState = level.getBlockState(to);
        ITrackBlock block = (ITrackBlock) fromState.getBlock();
        BezierConnection curve = new BezierConnection(Couple.create(from, to),
            Couple.create(block.getCurveStart(level, from, fromState, fromAxis), block.getCurveStart(level, to, toState, toAxis)),
            Couple.create(fromAxis, toAxis),
            Couple.create(block.getUpNormal(level, from, fromState), block.getUpNormal(level, to, toState)),
            true, false, TrackMaterial.ANDESITE);
        curveLength = curve.getLength();
        ((TrackBlockEntity) level.getBlockEntity(from)).addConnection(curve);
        ((TrackBlockEntity) level.getBlockEntity(to)).addConnection(curve.secondary());
    }

    /**
     * A track block lays its part of the graph a tick after it is placed, so the stations wait for it;
     * and with a curve, for the curve to join the stretches into one graph.
     */
    private void waitForGraph() {
        TrackGraph graph = null;
        for (BlockPos track : stationTracks) {
            Vec3 axis = southward(track) ? new Vec3(0, 0, 1) : new Vec3(1, 0, 0);
            TrackGraphLocation at = TrackGraphHelper.getGraphLocationAt(level, helper.absolutePos(track), AxisDirection.NEGATIVE, axis);
            if (at == null || (graph != null && at.graph != graph)) {
                stuckAfter(40, at == null ? "the track graph does not reach " + track.toShortString() : "the stations are not on one track graph");
                return;
            }
            graph = at.graph;
        }
        for (int i = 0; i < stationTracks.length; i++)
            placeStation(i);
        enter(Phase.WAIT_FOR_STATIONS);
    }

    /**
     * Does what placing the item does: the block, and the track it points at. The arrow points the
     * way the train's body lies, so that trains running east, or south on the line south of a turn,
     * arrive at it.
     */
    private void placeStation(int index) {
        BlockPos station = helper.absolutePos(stationBlock(index));
        BlockPos trackPos = helper.absolutePos(stationTracks[index]);
        level.setBlock(station, AllBlocks.TRACK_STATION.getDefaultState(), 3);
        CompoundTag data = new CompoundTag();
        data.put("TargetTrack", NbtUtils.writeBlockPos(trackPos.subtract(station)));
        data.putBoolean("TargetDirection", false);
        level.getBlockEntity(station).loadCustomOnly(data, level.registryAccess());
    }

    private StationBlockEntity stationEntity(int index) {
        return (StationBlockEntity) level.getBlockEntity(helper.absolutePos(stationBlock(index)));
    }

    /** A station block makes its place in the graph on its first tick; then the line gets its names and its train. */
    private void waitForStations() {
        for (int i = 0; i < stationTracks.length; i++)
            if (stationEntity(i).getStation() == null) {
                stuckAfter(40, "the station at " + stationTracks[i].toShortString() + " has no place in the graph");
                return;
            }
        for (int i = 0; i < stationTracks.length; i++) {
            helper.assertTrue(stationEntity(i).updateName(names[i]),
                "the station at " + stationTracks[i].toShortString() + " could not be named " + names[i]);
            stations[i] = stationEntity(i).getStation();
        }

        assembleAtFirstStation();
        train = stations[0].getPresentTrain();
        if (train == null) {
            AssemblyException problem = AssemblyException.read(stationEntity(0).saveWithoutMetadata(level.registryAccess()), level.registryAccess());
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
        BlockPos trackFirst = stationTracks[0];
        BlockPos bogey = trackFirst.offset(-1, 1, 0);
        BlockPos burner = trackFirst.offset(0, 1, 0);
        BlockPos controls = trackFirst.offset(1, 1, 0);

        BlockPos absoluteTrack = helper.absolutePos(trackFirst);
        BlockState trackState = level.getBlockState(absoluteTrack);
        helper.setBlock(bogey, ((ITrackBlock) trackState.getBlock()).getBogeyAnchor(level, absoluteTrack, trackState));
        helper.setBlock(burner, AllBlocks.BLAZE_BURNER.getDefaultState().setValue(BlazeBurnerBlock.HEAT_LEVEL, BlazeBurnerBlock.HeatLevel.SMOULDERING));
        helper.setBlock(controls, AllBlocks.TRAIN_CONTROLS.getDefaultState().setValue(ControlsBlock.FACING, Direction.WEST));
        level.addFreshEntity(new SuperGlueEntity(level, SuperGlueEntity.span(helper.absolutePos(bogey), helper.absolutePos(controls))));

        stationEntity(0).assemble(owner);
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
            for (int i = 0; i < stationTracks.length; i++)
                level.setBlock(helper.absolutePos(stationBlock(i)), Blocks.AIR.defaultBlockState(), 3);
            BlockPos trackFirst = stationTracks[0];
            for (int dx = -1; dx <= 1; dx++)
                level.setBlock(helper.absolutePos(trackFirst.offset(dx, 1, 0)), Blocks.AIR.defaultBlockState(), 3);
            for (int x = FIRST_X; x <= lastX; x++)
                if (straightAt(x))
                    level.setBlock(helper.absolutePos(track(x)), Blocks.AIR.defaultBlockState(), 3);
            if (turn != null)
                for (int z = turn.toZ(); z <= turn.lastZ(); z++)
                    level.setBlock(helper.absolutePos(new BlockPos(turn.toX(), TRACK_Y, z)), Blocks.AIR.defaultBlockState(), 3);
            // The blocks Create puts along a curve, so that it can be walked on and broken.
            if (bend != null)
                clear(bend.fromX(), bend.toX(), TRACK_Z, TRACK_Z + bend.shift());
            if (turn != null)
                clear(turn.fromX(), turn.toX(), TRACK_Z, turn.toZ());
        } catch (RuntimeException e) {
            LOGGER.warn("[{}] could not clean up after the track test", helper.testInfo.getTestName(), e);
        } finally {
            ClockService.data(server).setOffset(savedOffset);
        }
    }

    /** Sets the blocks of the track's layer in the box between x0, z0 and x1, z1 (either way round) to air. */
    private void clear(int x0, int x1, int z0, int z1) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
            for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++)
                level.setBlock(helper.absolutePos(new BlockPos(x, TRACK_Y, z)), Blocks.AIR.defaultBlockState(), 3);
    }
}
