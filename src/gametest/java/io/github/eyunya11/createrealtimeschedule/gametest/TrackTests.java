package io.github.eyunya11.createrealtimeschedule.gametest;

import java.util.Locale;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.station.GlobalStation;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.clock.ClockService;
import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.schedule.DeparturePlanner.SlotRecord;
import io.github.eyunya11.createrealtimeschedule.schedule.RealTimeOfDayCondition;
import io.github.eyunya11.createrealtimeschedule.schedule.TrainSlotMemory;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks on the {@code real_time_of_day} condition with a train that runs on track, run with
 * {@code ./gradlew runGameTestServer}.
 *
 * <p>{@link ScheduleTests} drives Create's waiting by hand with a train that has no track. Here the
 * train is the real thing, on a {@link TrackLine}: Create's own track blocks and station blocks
 * build the track graph, the station assembles a train, and Create's own schedule, navigation and
 * movement take it from one station to the next. All the test does is set the clock, as
 * {@code /rtclock set} would, and watch.
 *
 * <p>The line is Alpha, Beta, Gamma, all in a row. The train is built at Alpha and given a schedule
 * of two stops: Beta, which waits on {@code real_time_of_day}, and Gamma, which has no condition
 * and is only somewhere to go. The train leaves Alpha at once, so the stop that is under test is
 * Beta, the first one it reaches by running. The test sets the clock just as the train gets to Beta,
 * so that it arrives early or late by an amount the test chooses, and then checks the clock at the
 * moment the train starts moving towards Gamma.
 *
 * <p>Each test has a batch of its own (see {@link TrackLine}) and puts back what it changed,
 * whether it passes, fails or times out.
 */
@GameTestHolder(Createrealtimeschedule.MODID)
@PrefixGameTestTemplate(false)
public class TrackTests {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String TRACK_LINE = "track_line";
    private static final int TIMEOUT_TICKS = 1500;

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
        SET_UP, TO_BETA, AT_BETA, TO_GAMMA
    }

    /** One train on one line, stepped once a tick by the game test. */
    private static final class Run {
        private final GameTestHelper helper;
        private final MinecraftServer server;
        private final Scenario scenario;
        private final TrackLine line;

        private Phase phase = Phase.SET_UP;
        private int phaseTicks;

        private Train train;
        private GlobalStation beta;
        private GlobalStation gamma;

        // what the train did at Beta
        private Vec3 stoodAt;
        private boolean departureSeen;
        private long departureBooked;

        Run(GameTestHelper helper, Scenario scenario) {
            this.helper = helper;
            this.server = helper.getLevel().getServer();
            this.scenario = scenario;
            this.line = new TrackLine(helper, TRACK_LAST_X, new int[] {ALPHA_X, BETA_X, GAMMA_X},
                new String[] {"Alpha", "Beta", "Gamma"}, ScheduleTests.clock(2, 7, 0, 0));
        }

        void begin() {
            helper.testInfo.addListener(new GameTestListener() {
                @Override
                public void testStructureLoaded(GameTestInfo test) {}

                @Override
                public void testPassed(GameTestInfo test, GameTestRunner runner) {
                    line.cleanUp();
                }

                @Override
                public void testFailed(GameTestInfo test, GameTestRunner runner) {
                    line.cleanUp();
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

        // -------------------------------------------------------------- the steps

        void tick() {
            phaseTicks++;
            switch (phase) {
                case SET_UP -> setUp();
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
                helper.fail(what + " after " + phaseTicks + " ticks (phase " + phase + ", " + line.describe() + ")");
        }

        private void setUp() {
            if (!line.setUp())
                return;
            train = line.train();
            beta = line.station(1);
            gamma = line.station(2);
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
                helper.fail("the train ran past Beta (" + line.describe() + ")");
            if (at != beta || train.runtime.state != ScheduleRuntime.State.POST_TRANSIT) {
                stuckAfter(600, "the train has not arrived at Beta");
                return;
            }
            helper.assertFalse(train.runtime.conditionContext.get(0).contains(RealTimeOfDayCondition.CONTEXT_DEPARTURE),
                "the condition had already looked at the clock before the test could set it");
            Vec3 markerAtBeta = Vec3.atCenterOf(helper.absolutePos(line.track(BETA_X)));
            stoodAt = line.frontOfTrain();
            helper.assertTrue(Math.abs(stoodAt.x - markerAtBeta.x) < 1.0,
                "the train stopped at x = " + stoodAt.x + " and not at Beta's x = " + markerAtBeta.x);
            line.clockOnNextTick(scenario.arrival());
            enter(Phase.AT_BETA);
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
                helper.assertTrue(line.frontOfTrain().distanceToSqr(stoodAt) < 1.0e-9, "the train has moved at " + exact(now) + " while it waits at Beta");
                if (now > scenario.departure() + LEAVING_TICKS)
                    helper.fail("the train is still at Beta at " + exact(now) + ", more than " + LEAVING_TICKS
                        + " ticks after its departure time " + exact(scenario.departure()) + " (" + line.describe() + ")");
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
    }
}
