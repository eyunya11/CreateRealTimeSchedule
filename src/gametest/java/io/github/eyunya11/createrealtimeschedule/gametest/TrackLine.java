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
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackGraphHelper;
import com.simibubi.create.content.trains.graph.TrackGraphLocation;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.signal.TrackEdgePoint;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.simibubi.create.content.trains.track.TrackShape;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

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
import net.minecraft.world.level.block.entity.BlockEntity;
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
 * <p>More trains can be assembled the same way at other stations and left standing there
 * ({@link #parkATrainAt}), so that those platforms have a train at them.
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
     * A quarter turn to the south: a curved track from the block at {@code fromX} on the first row to
     * the block at ({@code toX}, {@code toZ}), after which the line runs south to {@code lastZ}. The
     * first row ends at {@code fromX}, or goes on past it, which makes the block a junction, as when a
     * player starts a curve from the middle of a straight. The turn is a quarter circle, so
     * {@code toZ} has to be {@code toX - fromX + TRACK_Z}: it then starts and ends as far from where
     * its two ends' lines meet.
     */
    record Turn(int fromX, int toX, int toZ, int lastZ) {
        Turn {
            if (!(fromX < toX && toZ == toX - fromX + TRACK_Z && toZ < lastZ))
                throw new IllegalArgumentException("not a quarter turn to the south: " + fromX + " to (" + toX + ", " + toZ + "), on to " + lastZ);
        }
    }

    /**
     * A siding beside the first row: a curved track from the first row at {@code outFromX} over to a row
     * {@code shift} blocks further south at {@code outToX}, that row on to {@code backFromX}, and a curved
     * track from there back to the first row at {@code backToX}. The first row runs on past both ends,
     * so the blocks at {@code outFromX} and {@code backToX} are junctions, as when a player lays a siding
     * from the middle of a straight. Both curves are S-bends, as a {@link Bend} is.
     */
    record Siding(int outFromX, int outToX, int backFromX, int backToX, int shift) {
        /**
         * The least shift: a station of the first row has its block south of its track, and a signal
         * beside the siding is north of the siding's, so that the two rows need a block between them.
         */
        static final int LEAST_SHIFT = 3;

        Siding {
            if (!(outFromX < outToX && outToX < backFromX && backFromX < backToX && shift >= LEAST_SHIFT))
                throw new IllegalArgumentException("not a siding: out from " + outFromX + " to " + outToX + ", back from " + backFromX
                    + " to " + backToX + ", " + shift + " over");
        }

        /** The row of the siding. */
        int z() {
            return TRACK_Z + shift;
        }
    }

    /**
     * A signal of Create's beside the row along z = {@code z} at {@code x}, on its north side, for trains
     * running east, or with {@code eastward} false for trains running west. A signal lets trains through
     * only the way it faces ({@code SignalBoundary.canNavigateVia}), so one for trains running west
     * closes the line to trains running east.
     */
    record Signal(int x, boolean eastward, int z) {

        /** What {@link #z} is for a signal beside the first row, wherever that runs at {@link #x}. */
        static final int FIRST_ROW = Integer.MIN_VALUE;

        /** A signal beside the first row, on the row it runs on at {@code x} (past a bend, the row the bend leads to). */
        Signal(int x, boolean eastward) {
            this(x, eastward, FIRST_ROW);
        }
    }

    /**
     * A loop that a train goes round and comes back to where it started: the first row, along
     * z = {@code northZ}, from {@code westX + radius} to {@code eastX}, a quarter turn south at each end
     * of it, lines south on x = {@code westX} and x = {@code eastX + radius}, and a row back along
     * z = {@code southZ}, joined to them by two more quarter turns. All four turns have the radius
     * {@code radius}. Two loops that do not touch, side by side or one {@link #inside} the other, are
     * two track graphs.
     */
    record Loop(int westX, int eastX, int radius, int northZ, int southZ) {
        Loop {
            if (!(radius > 0 && westX >= FIRST_X && eastX > westX + radius && northZ >= TRACK_Z && southZ > northZ + 2 * radius))
                throw new IllegalArgumentException("not a loop: from x = " + westX + " east to " + eastX + ", turns of " + radius
                    + ", along z = " + northZ + " and back along z = " + southZ);
        }

        /** A loop whose line south on the west is on the line's first block. */
        Loop(int eastX, int radius, int northZ, int southZ) {
            this(FIRST_X, eastX, radius, northZ, southZ);
        }

        /** A loop whose first row is the line's usual one. */
        Loop(int eastX, int radius, int southZ) {
            this(eastX, radius, TRACK_Z, southZ);
        }

        /**
         * The loop {@code gap} blocks inside this one all the way round, as the other track of a double
         * line: its turns have the same centres, and so a radius {@code gap} smaller. Its first row runs
         * to the same x, so stations at the same x along the two first rows are side by side.
         */
        Loop inside(int gap) {
            return new Loop(westX + gap, eastX, radius - gap, northZ + gap, southZ - gap);
        }

        /** Where the first row starts, at the end of the loop's last turn. */
        int firstRowX() {
            return westX + radius;
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
    private final @Nullable Loop loop;
    private final @Nullable Siding siding;
    private final List<Signal> signals;
    private final long savedOffset;
    private final UUID owner = UUID.randomUUID();
    private final GlobalStation[] stations;
    /** The length of the curve as Create laid it, once it is. */
    private double curveLength = Double.NaN;

    private Phase phase = Phase.BUILD_TRACK;
    private int phaseTicks;
    private Train train;
    /** The stations a train is parked at, besides the first; see {@link #parkATrainAt}. */
    private final List<Integer> parkedAt = new ArrayList<>();
    private final List<Train> parked = new ArrayList<>();

    /** A line that leaves the clock alone, for a test that does not read it. */
    TrackLine(GameTestHelper helper, int lastX, int[] stationX, String[] names) {
        this(helper, lastX, onTheRow(stationX, null), names, OptionalLong.empty(), null, null, List.of(), null, null);
    }

    /** A line that puts the clock at {@code startClock} when it is built, so that a failure reads in times that mean something. */
    TrackLine(GameTestHelper helper, int lastX, int[] stationX, String[] names, long startClock) {
        this(helper, lastX, onTheRow(stationX, null), names, OptionalLong.of(startClock), null, null, List.of(), null, null);
    }

    /** A line with a bend in it, which leaves the clock alone. A station past the bend is on the row it leads to. */
    TrackLine(GameTestHelper helper, int lastX, int[] stationX, String[] names, Bend bend) {
        this(helper, lastX, onTheRow(stationX, bend), names, OptionalLong.empty(), bend, null, List.of(), null, null);
    }

    /** A line with signals beside it, which leaves the clock alone. */
    TrackLine(GameTestHelper helper, int lastX, int[] stationX, String[] names, List<Signal> signals) {
        this(helper, lastX, onTheRow(stationX, null), names, OptionalLong.empty(), null, null, signals, null, null);
    }

    /** A loop, which leaves the clock alone, with stations on its first row. */
    TrackLine(GameTestHelper helper, Loop loop, int[] stationX, String[] names) {
        this(helper, loop.eastX(), onTheLoop(stationX, loop), names, OptionalLong.empty(), null, null, List.of(), loop, null);
    }

    /** A loop that puts the clock at {@code startClock} when it is built, with stations on its first row. */
    TrackLine(GameTestHelper helper, Loop loop, int[] stationX, String[] names, long startClock) {
        this(helper, loop.eastX(), onTheLoop(stationX, loop), names, OptionalLong.of(startClock), null, null, List.of(), loop, null);
    }

    /**
     * A line that turns south, which leaves the clock alone, with two stations: the first on the row
     * before the turn at {@code firstX}, where the train is built, and the second on the line south of
     * it at {@code secondZ}.
     */
    TrackLine(GameTestHelper helper, Turn turn, int firstX, int secondZ, String[] names) {
        this(helper, turn.fromX(), aroundTheTurn(turn, firstX, secondZ), names, OptionalLong.empty(), null, turn, List.of(), null, null);
    }

    /**
     * A line whose first row runs on to {@code lastX} past a turn south, which leaves the clock alone:
     * a junction, with stations on either way out of it. A station is given by its track block, on the
     * first row or on the line south of the turn; the train is built at the first, on the first row.
     */
    TrackLine(GameTestHelper helper, int lastX, Turn turn, BlockPos[] stationTracks, String[] names) {
        this(helper, lastX, stationTracks, names, OptionalLong.empty(), null, turn, List.of(), null, null);
    }

    /**
     * A line whose first row runs to {@code lastX} with a siding beside it, and signals, which leaves the
     * clock alone. A station is given by its track block, on the first row or on the siding's; the train
     * is built at the first, on the first row.
     */
    TrackLine(GameTestHelper helper, int lastX, Siding siding, BlockPos[] stationTracks, String[] names, List<Signal> signals) {
        this(helper, lastX, stationTracks, names, OptionalLong.empty(), null, null, signals, null, siding);
    }

    /**
     * @param lastX         the last track block of the first row of the line; it runs from {@link #FIRST_X}
     * @param stationTracks the track block of each station, in the order they are named; the train is built at the first
     * @param names         the stations' names
     */
    private TrackLine(GameTestHelper helper, int lastX, BlockPos[] stationTracks, String[] names, OptionalLong startClock,
        @Nullable Bend bend, @Nullable Turn turn, List<Signal> signals, @Nullable Loop loop, @Nullable Siding siding) {
        this.helper = helper;
        this.level = helper.getLevel();
        this.server = level.getServer();
        this.lastX = lastX;
        this.stationTracks = stationTracks;
        this.names = names;
        this.startClock = startClock;
        this.bend = bend;
        this.turn = turn;
        this.loop = loop;
        this.siding = siding;
        this.signals = List.copyOf(signals);
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

    /** The track blocks of stations on the first row of a loop, between its turns. */
    private static BlockPos[] onTheLoop(int[] stationX, Loop loop) {
        for (int x : stationX)
            if (!(x > loop.firstRowX() && x < loop.eastX()))
                throw new IllegalArgumentException("a station at x = " + x + " is not on the first row of " + loop);
        BlockPos[] tracks = new BlockPos[stationX.length];
        for (int i = 0; i < stationX.length; i++)
            tracks[i] = new BlockPos(stationX[i], TRACK_Y, loop.northZ());
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

    /**
     * Has another train assembled at the station {@code index} when the line is set up, as at the
     * first, which stands there, so that the platform has a train at it. The station has to be on the
     * first row, with track a block either side of its own. Called before {@link #setUp()}.
     */
    TrackLine parkATrainAt(int index) {
        BlockPos track = index <= 0 || index >= stationTracks.length ? null : stationTracks[index];
        if (track == null || southward(track) || track.getZ() != rowZ(track.getX()))
            throw new IllegalArgumentException("a train can be parked at a station of the first row other than the first, not at " + index);
        parkedAt.add(index);
        return this;
    }

    /** The train parked at the {@code i}-th station given to {@link #parkATrainAt}, once the line is set up. */
    Train parked(int i) {
        return parked.get(i);
    }

    GlobalStation station(int index) {
        return stations[index];
    }

    /**
     * The first x from {@code fromX} on at which the first row's blocks from {@code x - before} to
     * {@code x + after} are all in one chunk of the world, and so on one edge of Create's track graph,
     * which splits a straight line at chunk borders. Where the test is placed decides it.
     */
    static int inOneChunk(GameTestHelper helper, int fromX, int before, int after) {
        for (int x = fromX; ; x++)
            if (chunkAt(helper, x - before) == chunkAt(helper, x + after))
                return x;
    }

    private static int chunkAt(GameTestHelper helper, int x) {
        return Math.floorDiv(helper.absolutePos(new BlockPos(x, TRACK_Y, TRACK_Z)).getX(), 16);
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
        if (loop != null)
            return loop.northZ();
        return bend != null && x >= bend.toX() ? TRACK_Z + bend.shift() : TRACK_Z;
    }

    /** Whether there is a straight track block at {@code x} of the first row: everywhere but under the bend, and west of a loop's first turn. */
    private boolean straightAt(int x) {
        return (bend == null || x <= bend.fromX() || x >= bend.toX()) && (loop == null || x >= loop.firstRowX());
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
            boolean endsACurve = bend != null && (x == bend.fromX() || x == bend.toX()) || turn != null && x == turn.fromX()
                || loop != null && (x == loop.firstRowX() || x == loop.eastX()) || siding != null && (x == siding.outFromX() || x == siding.backToX());
            helper.setBlock(track(x), endsACurve ? curveEnd : track);
        }
        if (siding != null) {
            for (int x = siding.outToX(); x <= siding.backFromX(); x++)
                helper.setBlock(new BlockPos(x, TRACK_Y, siding.z()), x == siding.outToX() || x == siding.backFromX() ? curveEnd : track);
            Vec3 east = new Vec3(1, 0, 0), west = new Vec3(-1, 0, 0);
            layCurve(track(siding.outFromX()), east, new BlockPos(siding.outToX(), TRACK_Y, siding.z()), west);
            layCurve(new BlockPos(siding.backFromX(), TRACK_Y, siding.z()), east, track(siding.backToX()), west);
        }
        if (bend != null)
            layCurve(track(bend.fromX()), new Vec3(1, 0, 0), track(bend.toX()), new Vec3(-1, 0, 0));
        if (turn != null) {
            BlockState southward = AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, TrackShape.ZO);
            for (int z = turn.toZ(); z <= turn.lastZ(); z++)
                helper.setBlock(new BlockPos(turn.toX(), TRACK_Y, z), z == turn.toZ() ? southward.setValue(TrackBlock.HAS_BE, true) : southward);
            layCurve(track(turn.fromX()), new Vec3(1, 0, 0), new BlockPos(turn.toX(), TRACK_Y, turn.toZ()), new Vec3(0, 0, -1));
        }
        if (loop != null)
            buildTheRestOfTheLoop(loop, track, curveEnd);
        enter(Phase.WAIT_FOR_GRAPH);
    }

    /** The loop's two lines south, its row back and its four turns; the first row is laid already. */
    private void buildTheRestOfTheLoop(Loop loop, BlockState alongX, BlockState alongXCurveEnd) {
        int r = loop.radius();
        int westLine = loop.westX();
        int eastLine = loop.eastX() + r;
        int northZ = loop.northZ() + r;
        int southLineZ = loop.southZ() - r;
        BlockState alongZ = AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, TrackShape.ZO);
        for (int z = northZ; z <= southLineZ; z++) {
            BlockState state = z == northZ || z == southLineZ ? alongZ.setValue(TrackBlock.HAS_BE, true) : alongZ;
            helper.setBlock(new BlockPos(westLine, TRACK_Y, z), state);
            helper.setBlock(new BlockPos(eastLine, TRACK_Y, z), state);
        }
        for (int x = loop.firstRowX(); x <= loop.eastX(); x++)
            helper.setBlock(new BlockPos(x, TRACK_Y, loop.southZ()), x == loop.firstRowX() || x == loop.eastX() ? alongXCurveEnd : alongX);
        Vec3 east = new Vec3(1, 0, 0), west = new Vec3(-1, 0, 0), south = new Vec3(0, 0, 1), north = new Vec3(0, 0, -1);
        layCurve(track(loop.eastX()), east, new BlockPos(eastLine, TRACK_Y, northZ), north);
        layCurve(new BlockPos(eastLine, TRACK_Y, southLineZ), south, new BlockPos(loop.eastX(), TRACK_Y, loop.southZ()), east);
        layCurve(new BlockPos(loop.firstRowX(), TRACK_Y, loop.southZ()), west, new BlockPos(westLine, TRACK_Y, southLineZ), south);
        layCurve(new BlockPos(westLine, TRACK_Y, northZ), north, track(loop.firstRowX()), west);
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
        for (Signal signal : signals)
            placeSignal(signal);
        enter(Phase.WAIT_FOR_STATIONS);
    }

    private BlockPos signalBlock(Signal signal) {
        return signalTrack(signal).north();
    }

    /** The track block a signal stands beside and points at. */
    private BlockPos signalTrack(Signal signal) {
        return signal.z() == Signal.FIRST_ROW ? track(signal.x()) : new BlockPos(signal.x(), TRACK_Y, signal.z());
    }

    /** Does what placing the item does, as for a station; the arrow of a signal for trains running east points as a station's does. */
    private void placeSignal(Signal signal) {
        BlockPos at = helper.absolutePos(signalBlock(signal));
        BlockPos trackPos = helper.absolutePos(signalTrack(signal));
        level.setBlock(at, AllBlocks.TRACK_SIGNAL.getDefaultState(), 3);
        CompoundTag data = new CompoundTag();
        data.put("TargetTrack", NbtUtils.writeBlockPos(trackPos.subtract(at)));
        data.putBoolean("TargetDirection", !signal.eastward());
        level.getBlockEntity(at).loadCustomOnly(data, level.registryAccess());
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

    /** A station or signal block makes its place in the graph on its first tick; then the line gets its names and its train. */
    private void waitForStations() {
        for (int i = 0; i < stationTracks.length; i++)
            if (stationEntity(i).getStation() == null) {
                if (phaseTicks > 40)
                    stuckAfter(40, "the station at " + stationTracks[i].toShortString() + " has no place in the graph: " + whyNoPlace(i));
                return;
            }
        for (Signal signal : signals) {
            BlockEntity entity = level.getBlockEntity(helper.absolutePos(signalBlock(signal)));
            TrackTargetingBehaviour<?> target = entity == null ? null : BlockEntityBehaviour.get(entity, TrackTargetingBehaviour.TYPE);
            if (target == null || target.getEdgePoint() == null) {
                stuckAfter(40, "the signal " + signal + " has no place in the graph");
                return;
            }
        }
        for (int i = 0; i < stationTracks.length; i++) {
            helper.assertTrue(stationEntity(i).updateName(names[i]),
                "the station at " + stationTracks[i].toShortString() + " could not be named " + names[i]);
            stations[i] = stationEntity(i).getStation();
        }

        train = assembleAt(0);
        for (int index : parkedAt)
            parked.add(assembleAt(index));
        enter(Phase.WAIT_FOR_CONDUCTOR);
    }

    /**
     * What Create finds where station {@code index} is to go, for a failure message: why it may have
     * no place in the graph. Create gives a station none, and tries again each tick, when it finds no
     * place on the graph at its track, when that place is on no edge, or when the edge has a point there
     * already that it cannot share it with (another station, a signal).
     */
    private String whyNoPlace(int index) {
        StationBlockEntity entity = stationEntity(index);
        BlockPos block = helper.absolutePos(stationBlock(index));
        String state = "its block entity is " + (entity.isRemoved() ? "removed" : "there") + (level.getBlockEntity(block) == entity ? "" : " but not the level's")
            + ", its block " + (level.shouldTickBlocksAt(block) ? "ticks" : "does not tick") + "; ";
        TrackTargetingBehaviour<?> target = BlockEntityBehaviour.get(entity, TrackTargetingBehaviour.TYPE);
        if (target == null)
            return state + "the station has no track target";
        String track = target.getGlobalPosition().toShortString() + " facing " + target.getTargetDirection();
        if (!target.hasValidTrack())
            return state + "its target " + track + " is not a track";
        TrackGraphLocation at = target.determineGraphLocation();
        if (at == null)
            return state + "Create finds no place on the graph at " + track;
        TrackNode first = at.graph.locateNode(at.edge.getFirst());
        TrackNode second = at.graph.locateNode(at.edge.getSecond());
        TrackEdge edge = first == null || second == null ? null : at.graph.getConnectionsFrom(first).get(second);
        long graphs = Create.RAILWAYS.trackNetworks.values().stream().filter(graph -> graph.locateNode(at.edge.getFirst()) != null).count();
        StringBuilder text = new StringBuilder(state).append("Create places it at ").append(track).append(", ").append(at.position)
            .append(" along ").append(at.edge.getFirst()).append(" to ").append(at.edge.getSecond()).append(" of graph ").append(at.graph.id)
            .append(" (").append(graphs).append(" of the ").append(Create.RAILWAYS.trackNetworks.size()).append(" graphs have that node)");
        if (edge == null)
            return text.append(", which has no edge there").toString();
        text.append("; the points on that edge:");
        for (TrackEdgePoint point : edge.getEdgeData().getPoints())
            text.append(' ').append(point.getType().getId()).append(" at ").append(point.getLocationOn(edge));
        return text.toString();
    }

    /** The train assembled at the station {@code index}, standing there. */
    private Train assembleAt(int index) {
        placeTrainBlocks(index);
        stationEntity(index).assemble(owner);
        Train made = stations[index].getPresentTrain();
        if (made == null) {
            AssemblyException problem = AssemblyException.read(stationEntity(index).saveWithoutMetadata(level.registryAccess()), level.registryAccess());
            helper.fail("no train was assembled at " + names[index] + (problem == null ? "" : ": " + problem.component.getString()));
        }
        return made;
    }

    /**
     * A bogey on the track beside the station's, a blaze burner over the station's own block and
     * the train controls beyond it facing back at it, all glued together: the burner is the
     * conductor, which Create wants before it lets a train follow a schedule.
     */
    private void placeTrainBlocks(int index) {
        BlockPos trackFirst = stationTracks[index];
        BlockPos bogey = trackFirst.offset(-1, 1, 0);
        BlockPos burner = trackFirst.offset(0, 1, 0);
        BlockPos controls = trackFirst.offset(1, 1, 0);

        BlockPos absoluteTrack = helper.absolutePos(trackFirst);
        BlockState trackState = level.getBlockState(absoluteTrack);
        helper.setBlock(bogey, ((ITrackBlock) trackState.getBlock()).getBogeyAnchor(level, absoluteTrack, trackState));
        helper.setBlock(burner, AllBlocks.BLAZE_BURNER.getDefaultState().setValue(BlazeBurnerBlock.HEAT_LEVEL, BlazeBurnerBlock.HeatLevel.SMOULDERING));
        helper.setBlock(controls, AllBlocks.TRAIN_CONTROLS.getDefaultState().setValue(ControlsBlock.FACING, Direction.WEST));
        level.addFreshEntity(new SuperGlueEntity(level, SuperGlueEntity.span(helper.absolutePos(bogey), helper.absolutePos(controls))));
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

    /** Takes the trains, the stations and the track out of Create's railways and the world, and gives the clock back. */
    void cleanUp() {
        try {
            List<Train> trains = new ArrayList<>(parked);
            if (train != null)
                trains.add(train);
            for (Train each : trains) {
                for (var carriage : each.carriages)
                    carriage.forEachPresentEntity(Entity::discard);
                for (GlobalStation station : stations)
                    if (station != null)
                        station.cancelReservation(each);
                Create.RAILWAYS.removeTrain(each.id);
                CatnipServices.NETWORK.sendToAllClients(new RemoveTrainPacket(each));
                TrainSlotMemory.get(server).forget(each.id);
            }
            List<Entity> leftovers = new ArrayList<>(level.getEntitiesOfClass(Entity.class, helper.getBounds().inflate(1.0), e -> !(e instanceof Player)));
            leftovers.forEach(Entity::discard);
            for (int i = 0; i < stationTracks.length; i++)
                level.setBlock(helper.absolutePos(stationBlock(i)), Blocks.AIR.defaultBlockState(), 3);
            for (Signal signal : signals)
                level.setBlock(helper.absolutePos(signalBlock(signal)), Blocks.AIR.defaultBlockState(), 3);
            List<Integer> assembledAt = new ArrayList<>(parkedAt);
            assembledAt.add(0);
            for (int index : assembledAt)
                for (int dx = -1; dx <= 1; dx++)
                    level.setBlock(helper.absolutePos(stationTracks[index].offset(dx, 1, 0)), Blocks.AIR.defaultBlockState(), 3);
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
            if (loop != null)
                clear(loop.westX(), loop.eastX() + loop.radius(), loop.northZ(), loop.southZ());
            if (siding != null)
                clear(siding.outFromX(), siding.backToX(), TRACK_Z, siding.z());
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
