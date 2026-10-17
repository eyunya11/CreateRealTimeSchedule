package io.github.eyunya11.createrealtimeschedule.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.clock.ClockService;
import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.schedule.DeparturePlanner.SlotRecord;
import io.github.eyunya11.createrealtimeschedule.schedule.RealTimeOfDayCondition;
import io.github.eyunya11.createrealtimeschedule.schedule.TrainSlotMemory;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks on the {@code real_time_of_day} condition with a train that runs on track, run with
 * {@code ./gradlew runGameTestServer}.
 *
 * <p>{@link ScheduleTests} drives Create's waiting by hand with a train that has no track. Here the
 * train is the real thing: Create's own track blocks and station blocks build the track graph, the
 * station assembles a train out of a bogey, train controls and a blaze burner, and Create's own
 * schedule, navigation and movement take it from one station to the next. All the test does is set
 * the clock, as {@code /rtclock set} would, and watch.
 *
 * <p>The line is Alpha, Beta, Gamma, all in a row. The train is built at Alpha and given a schedule
 * of two stops: Beta, which waits on {@code real_time_of_day}, and Gamma, which has no condition
 * and is only somewhere to go. The train leaves Alpha at once, so the stop that is under test is
 * Beta, the first one it reaches by running. The test sets the clock just as the train gets to Beta,
 * so that it arrives early or late by an amount the test chooses, and then checks the clock at the
 * moment the train starts moving towards Gamma.
 *
 * <p>The tests share the clock and Create's railways with everything else, so each has a batch of
 * its own: tests in one batch run side by side, and each would move the other's clock. Each puts
 * back what it changed, whether it passes, fails or times out.
 */
@GameTestHolder(Createrealtimeschedule.MODID)
@PrefixGameTestTemplate(false)
public class TrackTests {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String TRACK_LINE = "track_line";
    private static final int TIMEOUT_TICKS = 1500;

    // The line, in the test's own coordinates. These count from the structure block, so the bottom
    // layer of the template is at y = 1.
    private static final int TRACK_Y = 2;
    private static final int TRACK_Z = 2;
    private static final int TRACK_FIRST_X = 0;
    private static final int TRACK_LAST_X = 50;
    private static final int ALPHA_X = 6;
    private static final int BETA_X = 26;
    private static final int GAMMA_X = 46;

    /** The slot Beta asks for: 08:00 every day. */
    private static final int SLOT_HOUR = 8;
    private static final int SLOT_MINUTE = 0;

    /**
     * Create moves a train on from a stop two ticks after the first tick its condition is met: one to
     * finish the stop and one to start the next. The train is therefore seen to leave two ticks after
     * its departure time, and never before it; two more ticks are allowed for Create's own bookkeeping
     * to shift.
     */
    private static final int LEAVING_TICKS = 4;

    /**
     * What a test arranges.
     *
     * @param arrival            the clock when the train starts to wait at Beta
     * @param minimumStopSeconds the minimum stop Beta asks for
     * @param departure          the clock at which the train must start moving towards Gamma
     */
    private record Scenario(long arrival, int minimumStopSeconds, long departure) {}

    // ------------------------------------------------------------------ the tests

    /**
     * Five seconds early with a two second minimum stop: the train must stand at Beta until 08:00:00
     * and go then, not two seconds after it arrived.
     */
    @GameTest(template = TRACK_LINE, batch = "realtimetrack_early", timeoutTicks = TIMEOUT_TICKS)
    public static void aTrainOnTrackThatArrivesEarlyWaitsForItsSlotAndLeavesOnIt(GameTestHelper helper) {
        new Run(helper, new Scenario(ScheduleTests.clock(2, 7, 59, 55), 2, ScheduleTests.clock(2, 8, 0, 0))).begin();
    }

    /**
     * Ten seconds late with a five second minimum stop: the train must stand at Beta for those five
     * seconds and go at 08:00:15, neither at once nor at the next 08:00.
     */
    @GameTest(template = TRACK_LINE, batch = "realtimetrack_late", timeoutTicks = TIMEOUT_TICKS)
    public static void aTrainOnTrackThatArrivesLateLeavesAfterTheMinimumStopAndNotBefore(GameTestHelper helper) {
        new Run(helper, new Scenario(ScheduleTests.clock(2, 8, 0, 10), 5, ScheduleTests.clock(2, 8, 0, 15))).begin();
    }

    // ------------------------------------------------------------------ one run

    private enum Phase {
        BUILD_TRACK, WAIT_FOR_GRAPH, WAIT_FOR_STATIONS, WAIT_FOR_CONDUCTOR, TO_BETA, AT_BETA, TO_GAMMA
    }

    /** One train on one line, stepped once a tick by the game test. */
    private static final class Run {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final MinecraftServer server;
        private final Scenario scenario;
        private final long savedOffset;
        private final UUID owner = UUID.randomUUID();

        private Phase phase = Phase.BUILD_TRACK;
        private int phaseTicks;

        private Train train;
        private GlobalStation alpha;
        private GlobalStation beta;
        private GlobalStation gamma;

        // what the train did at Beta
        private Vec3 stoodAt;
        private boolean departureSeen;
        private long departureBooked;

        Run(GameTestHelper helper, Scenario scenario) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.server = level.getServer();
            this.scenario = scenario;
            this.savedOffset = ClockService.data(server).offset();
        }

        void begin() {
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
            helper.onEachTick(this::tick);
        }

        // -------------------------------------------------------------- the clock

        private long now() {
            return ClockService.now(server);
        }

        /** A clock value to the tick, which is as close as the game can tell a train's moves apart. */
        private static String exact(long clock) {
            long ticks = Math.floorMod(clock, (long) RealClock.TICKS_PER_SECOND);
            return ScheduleTests.show(clock) + (ticks == 0 ? "" : " + " + ticks + " ticks");
        }

        private static String seconds(long ticks) {
            return String.format(Locale.ROOT, "%.2f s", ticks / (double) RealClock.TICKS_PER_SECOND);
        }

        /**
         * Sets the clock to read {@code clock} on the next tick. The game test runs at the end of a
         * tick, after the level and its trains, so the next thing to read the clock is the next tick's.
         */
        private void clockOnNextTick(long clock) {
            long next = server.overworld().getGameTime() + 1;
            ClockService.data(server).setOffset(RealClock.offsetFor(next, clock));
        }

        // -------------------------------------------------------------- the steps

        void tick() {
            phaseTicks++;
            switch (phase) {
                case BUILD_TRACK -> buildTrack();
                case WAIT_FOR_GRAPH -> waitForGraph();
                case WAIT_FOR_STATIONS -> waitForStations();
                case WAIT_FOR_CONDUCTOR -> waitForConductor();
                case TO_BETA -> toBeta();
                case AT_BETA -> atBeta();
                case TO_GAMMA -> toGamma();
            }
        }

        private void enter(Phase next) {
            phase = next;
            phaseTicks = 0;
        }

        private void stuckAfter(int ticks, String what) {
            if (phaseTicks > ticks)
                helper.fail(what + " after " + phaseTicks + " ticks (" + describe() + ")");
        }

        private String describe() {
            StringBuilder text = new StringBuilder("phase " + phase + ", clock " + ScheduleTests.show(now())
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
            // A clock the test knows from the start, so that a failure reads in times that mean something.
            clockOnNextTick(ScheduleTests.clock(2, 7, 0, 0));
            BlockState track = AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, TrackShape.XO);
            for (int x = TRACK_FIRST_X; x <= TRACK_LAST_X; x++)
                helper.setBlock(track(x), track);
            enter(Phase.WAIT_FOR_GRAPH);
        }

        /** A track block lays its part of the graph a tick after it is placed, so the stations wait for it. */
        private void waitForGraph() {
            for (int x : new int[] {ALPHA_X, BETA_X, GAMMA_X})
                if (TrackGraphHelper.getGraphLocationAt(level, helper.absolutePos(track(x)), AxisDirection.NEGATIVE, new Vec3(1, 0, 0)) == null) {
                    stuckAfter(40, "the track graph does not reach x = " + x);
                    return;
                }
            for (int x : new int[] {ALPHA_X, BETA_X, GAMMA_X})
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
            for (int x : new int[] {ALPHA_X, BETA_X, GAMMA_X})
                if (stationEntity(x).getStation() == null) {
                    stuckAfter(40, "the station at x = " + x + " has no place in the graph");
                    return;
                }
            name(ALPHA_X, "Alpha");
            name(BETA_X, "Beta");
            name(GAMMA_X, "Gamma");
            alpha = stationEntity(ALPHA_X).getStation();
            beta = stationEntity(BETA_X).getStation();
            gamma = stationEntity(GAMMA_X).getStation();

            assembleAtAlpha();
            train = alpha.getPresentTrain();
            if (train == null) {
                AssemblyException problem = AssemblyException.read(stationEntity(ALPHA_X).saveWithoutMetadata(level.registryAccess()), level.registryAccess());
                helper.fail("no train was assembled at Alpha" + (problem == null ? "" : ": " + problem.component.getString()));
            }
            enter(Phase.WAIT_FOR_CONDUCTOR);
        }

        private void name(int x, String name) {
            helper.assertTrue(stationEntity(x).updateName(name), "the station at x = " + x + " could not be named " + name);
        }

        /**
         * A bogey on the track beside the station's, a blaze burner over the station's own block and
         * the train controls beyond it facing back at it, all glued together: the burner is the
         * conductor, which Create wants before it lets a train follow a schedule.
         */
        private void assembleAtAlpha() {
            BlockPos trackAlpha = track(ALPHA_X);
            BlockPos bogey = trackAlpha.offset(-1, 1, 0);
            BlockPos burner = trackAlpha.offset(0, 1, 0);
            BlockPos controls = trackAlpha.offset(1, 1, 0);

            BlockPos absoluteTrack = helper.absolutePos(trackAlpha);
            BlockState trackState = level.getBlockState(absoluteTrack);
            helper.setBlock(bogey, ((ITrackBlock) trackState.getBlock()).getBogeyAnchor(level, absoluteTrack, trackState));
            helper.setBlock(burner, AllBlocks.BLAZE_BURNER.getDefaultState().setValue(BlazeBurnerBlock.HEAT_LEVEL, BlazeBurnerBlock.HeatLevel.SMOULDERING));
            helper.setBlock(controls, AllBlocks.TRAIN_CONTROLS.getDefaultState().setValue(ControlsBlock.FACING, Direction.WEST));
            level.addFreshEntity(new SuperGlueEntity(level, SuperGlueEntity.span(helper.absolutePos(bogey), helper.absolutePos(controls))));

            stationEntity(ALPHA_X).assemble(owner);
        }

        /** Create finds out who is driving a tick after the train's entities are made, so the schedule waits for that. */
        private void waitForConductor() {
            if (!train.hasForwardConductor()) {
                stuckAfter(40, "the train has no driver");
                return;
            }
            Schedule schedule = ScheduleTests.schedule(
                ScheduleTests.stop("Beta", ScheduleTests.condition(SLOT_HOUR, SLOT_MINUTE, ScheduleTests.DAILY, scenario.minimumStopSeconds())),
                ScheduleTests.stop("Gamma"));
            train.runtime.setSchedule(schedule, false);
            enter(Phase.TO_BETA);
        }

        /**
         * Waits for the train to come to Beta by running there. At the moment it has come, before the
         * condition has looked at the clock, the clock is set to make the train early or late.
         */
        private void toBeta() {
            GlobalStation at = train.getCurrentStation();
            if (at == gamma)
                helper.fail("the train ran past Beta (" + describe() + ")");
            if (at != beta || train.runtime.state != ScheduleRuntime.State.POST_TRANSIT) {
                stuckAfter(600, "the train has not arrived at Beta");
                return;
            }
            helper.assertFalse(train.runtime.conditionContext.get(0).contains(RealTimeOfDayCondition.CONTEXT_DEPARTURE),
                "the condition had already looked at the clock before the test could set it");
            Vec3 markerAtBeta = Vec3.atCenterOf(helper.absolutePos(track(BETA_X)));
            stoodAt = frontOfTrain();
            helper.assertTrue(Math.abs(stoodAt.x - markerAtBeta.x) < 1.0,
                "the train stopped at x = " + stoodAt.x + " and not at Beta's x = " + markerAtBeta.x);
            clockOnNextTick(scenario.arrival());
            enter(Phase.AT_BETA);
        }

        private Vec3 frontOfTrain() {
            return train.carriages.get(0).getLeadingPoint().getPosition(train.graph);
        }

        /**
         * The train has to stay where it is until its departure time and go soon after it. What it
         * does is the test; what the condition booked is only looked at once it has gone, and only
         * to say why if it went wrong.
         */
        private void atBeta() {
            long now = now();
            if (train.getCurrentStation() == beta) {
                if (!departureSeen) {
                    CompoundTag context = train.runtime.conditionContext.get(0);
                    if (context.contains(RealTimeOfDayCondition.CONTEXT_DEPARTURE, Tag.TAG_LONG)) {
                        departureSeen = true;
                        departureBooked = context.getLong(RealTimeOfDayCondition.CONTEXT_DEPARTURE);
                    }
                }
                helper.assertTrue(Math.abs(train.speed) < 1.0e-9, "the train is moving at " + exact(now) + " while it waits at Beta");
                helper.assertTrue(frontOfTrain().distanceToSqr(stoodAt) < 1.0e-9, "the train has moved at " + exact(now) + " while it waits at Beta");
                if (now > scenario.departure() + LEAVING_TICKS)
                    helper.fail("the train is still at Beta at " + exact(now) + ", more than " + LEAVING_TICKS
                        + " ticks after its departure time " + exact(scenario.departure()) + " (" + describe() + ")");
                return;
            }

            long left = now;
            helper.assertTrue(left >= scenario.departure(), "the train left Beta at " + exact(left) + ", "
                + seconds(scenario.departure() - left) + " before its departure time " + exact(scenario.departure()));
            helper.assertTrue(left <= scenario.departure() + LEAVING_TICKS, "the train left Beta at " + exact(left)
                + ", " + seconds(left - scenario.departure()) + " after its departure time " + exact(scenario.departure()));

            SlotRecord booked = TrainSlotMemory.get(server).get(train.id);
            helper.assertTrue(booked != null, "the condition never booked a slot for the train");
            LOGGER.info("[{}] arrived at {}, slot {}, departure {}; left at {}", helper.testInfo.getTestName(),
                exact(booked.stamp()), exact(booked.slot()), departureSeen ? exact(departureBooked) : "not seen", exact(left));
            helper.assertValueEqual(booked.stamp(), scenario.arrival(), "arrival the condition saw");
            helper.assertValueEqual(booked.slot(), ScheduleTests.clock(2, SLOT_HOUR, SLOT_MINUTE, 0), "slot the condition booked");
            helper.assertTrue(departureSeen, "the condition never held a departure time: it let the train go on the tick it arrived");
            helper.assertValueEqual(departureBooked, scenario.departure(), "departure the condition booked");
            helper.assertValueEqual(train.runtime.currentEntry, 1, "stop the schedule is on once the train has left Beta");
            enter(Phase.TO_GAMMA);
        }

        /** And it has to be a real departure: the train runs on and stops at the next station. */
        private void toGamma() {
            if (train.getCurrentStation() != gamma || train.runtime.state != ScheduleRuntime.State.POST_TRANSIT) {
                stuckAfter(600, "the train has not arrived at Gamma");
                return;
            }
            helper.succeed();
        }

        // -------------------------------------------------------------- putting it all back

        private BlockPos track(int x) {
            return new BlockPos(x, TRACK_Y, TRACK_Z);
        }

        private BlockPos stationBlock(int x) {
            return new BlockPos(x, TRACK_Y, TRACK_Z + 1);
        }

        /** Takes the train, the stations and the track out of Create's railways and the world, and gives the clock back. */
        private void cleanUp() {
            try {
                if (train != null) {
                    for (var carriage : train.carriages)
                        carriage.forEachPresentEntity(Entity::discard);
                    for (GlobalStation station : new GlobalStation[] {alpha, beta, gamma})
                        if (station != null)
                            station.cancelReservation(train);
                    Create.RAILWAYS.removeTrain(train.id);
                    CatnipServices.NETWORK.sendToAllClients(new RemoveTrainPacket(train));
                    TrainSlotMemory.get(server).forget(train.id);
                }
                List<Entity> leftovers = new ArrayList<>(level.getEntitiesOfClass(Entity.class, helper.getBounds().inflate(1.0), e -> !(e instanceof Player)));
                leftovers.forEach(Entity::discard);
                for (int x : new int[] {ALPHA_X, BETA_X, GAMMA_X})
                    level.setBlock(helper.absolutePos(stationBlock(x)), Blocks.AIR.defaultBlockState(), 3);
                BlockPos trackAlpha = track(ALPHA_X);
                for (int dx = -1; dx <= 1; dx++)
                    level.setBlock(helper.absolutePos(trackAlpha.offset(dx, 1, 0)), Blocks.AIR.defaultBlockState(), 3);
                for (int x = TRACK_FIRST_X; x <= TRACK_LAST_X; x++)
                    level.setBlock(helper.absolutePos(track(x)), Blocks.AIR.defaultBlockState(), 3);
            } catch (RuntimeException e) {
                LOGGER.warn("[{}] could not clean up after the track test", helper.testInfo.getTestName(), e);
            } finally {
                ClockService.data(server).setOffset(savedOffset);
            }
        }
    }
}
