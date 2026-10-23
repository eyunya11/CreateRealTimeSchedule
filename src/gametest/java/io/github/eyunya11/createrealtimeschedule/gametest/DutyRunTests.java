package io.github.eyunya11.createrealtimeschedule.gametest;

import java.util.List;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.clock.ClockService;
import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.gametest.TrackLine.Loop;
import io.github.eyunya11.createrealtimeschedule.operation.Call;
import io.github.eyunya11.createrealtimeschedule.operation.DutyPlan;
import io.github.eyunya11.createrealtimeschedule.operation.DutyRun;
import io.github.eyunya11.createrealtimeschedule.operation.TrainDriver;
import io.github.eyunya11.createrealtimeschedule.operation.TrainDriver.Outcome;
import io.github.eyunya11.createrealtimeschedule.timetable.ServiceTime;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Duty;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Line;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Stop;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Train;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Runs a duty of a timetable on real track, with {@code ./gradlew runGameTestServer}: a train of
 * Create's, with no schedule of Create's, taken by {@link TrainDriver} out of the depot to A, on to B,
 * and back to the depot, as the timetable has it. The line is a loop (see {@link TrackLine}), because
 * the test's train only runs forwards; the way back to the depot is round it.
 *
 * <p>A departure is when the train really left the platform. Create moves a train it has been sent
 * on its next tick, so a set sent on its time leaves {@link #SENT_TO_GONE} ticks after it.
 *
 * <p>Each test has a batch of its own (see {@link TrackLine}).
 */
@GameTestHolder(Createrealtimeschedule.MODID)
@PrefixGameTestTemplate(false)
public class DutyRunTests {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String TURN = "track_turn";
    private static final int TIMEOUT_TICKS = 2400;
    private static final Loop LOOP = new Loop(100, 12, 40);
    private static final String[] NAMES = {"Depot", "A", "B"};
    private static final int[] STATION_X = {20, 50, 80};
    /** The clock when the set is given the duty. */
    private static final long START = TestTimes.clock(10, 12, 0, 0);
    /** How many ticks after it is sent on a train leaves the platform, when nothing holds it. */
    static final long SENT_TO_GONE = 1;

    /**
     * Out of the depot to A, as train 1 to B, and back round the loop to the depot. Each leg takes the
     * train less time than the timetable gives it (about 6, 6 and 19 seconds), so it is early
     * everywhere and leaves every call on the timetable's time.
     */
    @GameTest(template = TURN, batch = "realtimeduty_on_time", timeoutTicks = TIMEOUT_TICKS)
    public static void aDutyRunsByTheTimetableFromTheDepotAndBack(GameTestHelper helper) {
        Timetable timetable = timetable("12:00:20", "12:00:35");
        new DutyTrip(helper, timetable, 0, (run, minimumStop) -> {
            for (int call = 0; call < run.calls().size() - 1; call++)
                helper.assertTrue(run.departures().get(call) == run.plannedDeparture(call) + SENT_TO_GONE,
                    "left " + run.calls().get(call).station() + " at " + TestTimes.show(run.departures().get(call)) + " + "
                        + run.departures().get(call) % RealClock.TICKS_PER_SECOND + " ticks, not on its time "
                        + TestTimes.show(run.plannedDeparture(call)));
        }).begin();
    }

    /**
     * The timetable gives the set 3 seconds from the depot to A, which takes it about 6: it is late at
     * A, stops there for the minimum and goes, and is on time again at B.
     */
    @GameTest(template = TURN, batch = "realtimeduty_late", timeoutTicks = TIMEOUT_TICKS)
    public static void aLateSetStopsForTheMinimumAndGoes(GameTestHelper helper) {
        Timetable timetable = timetable("12:00:08", "12:00:35");
        long minimumStop = 2 * RealClock.TICKS_PER_SECOND;
        new DutyTrip(helper, timetable, minimumStop, (run, stop) -> {
            long arrivedAtA = run.arrivals().get(1);
            helper.assertTrue(arrivedAtA > run.plannedDeparture(1), "the set was not late at A, arriving at " + TestTimes.show(arrivedAtA));
            helper.assertTrue(run.departures().get(1) == arrivedAtA + stop + SENT_TO_GONE,
                "the set left A at " + TestTimes.show(run.departures().get(1)) + ", not the minimum stop after it arrived at "
                    + TestTimes.show(arrivedAtA));
            helper.assertTrue(run.departures().get(2) == run.plannedDeparture(2) + SENT_TO_GONE, "the set did not leave B on time");
        }).begin();
    }

    /**
     * The depot, A and B on a loop line, and the one duty: deadhead D1 to A at 12:00:05, train 1 from
     * A at {@code leavesA} to B, deadhead D2 from B at {@code leavesB} round to the depot.
     */
    private static Timetable timetable(String leavesA, String leavesB) {
        Line line = new Line("loop", "Loop", List.of(NAMES), true);
        Train out = new Train("D1", "loop", "", List.of(Stop.leaving("Depot", t("12:00:05")), Stop.arriving("A", t("12:00:07"))), true);
        Train one = new Train("1", "loop", "local", List.of(Stop.leaving("A", t(leavesA)), Stop.arriving("B", t("12:00:27"))));
        Train in = new Train("D2", "loop", "", List.of(Stop.leaving("B", t(leavesB)), Stop.arriving("Depot", t("12:01:00"))), true);
        Timetable timetable = new Timetable("", List.of(line), List.of(out, one, in),
            List.of(new Duty("1A", "Depot", List.of("D1", "1", "D2"))));
        if (!timetable.problems().isEmpty())
            throw new IllegalStateException(timetable.problems().toString());
        return timetable;
    }

    private static int t(String time) {
        return ServiceTime.of(time);
    }

    /** What a test checks of the run once the set is back at the depot. */
    private interface Check {
        void check(DutyRun run, long minimumStop);
    }

    /** The test's line, set up, then the duty given to its train and stepped once a tick to the end. */
    private static final class DutyTrip {
        private final GameTestHelper helper;
        private final Timetable timetable;
        private final long minimumStop;
        private final Check check;
        private final TrackLine line;
        private boolean clockSet;
        private DutyRun run;

        DutyTrip(GameTestHelper helper, Timetable timetable, long minimumStop, Check check) {
            this.helper = helper;
            this.timetable = timetable;
            this.minimumStop = minimumStop;
            this.check = check;
            this.line = new TrackLine(helper, LOOP, STATION_X, NAMES);
        }

        void begin() {
            line.cleanUpWhenTheTestEnds();
            helper.onEachTick(this::tick);
        }

        private void tick() {
            if (!line.setUp())
                return;
            if (!clockSet) {
                line.clockOnNextTick(START);
                clockSet = true;
                return;
            }
            long now = ClockService.now(helper.getLevel().getServer());
            com.simibubi.create.content.trains.entity.Train train = line.train();
            if (run == null) {
                List<Call> calls = DutyPlan.calls(timetable, timetable.duties().getFirst());
                run = DutyRun.start("1A", RealClock.day(now), calls, now);
            }
            String letGo = TrainDriver.whyLetGo(train);
            if (letGo != null)
                helper.fail(letGo + " (" + line.describe() + ")");
            Outcome outcome = TrainDriver.step(run, train, now, minimumStop);
            if (outcome == Outcome.NO_WAY)
                helper.fail("no way to " + run.nextStation() + " (" + line.describe() + ")");
            // A departure is recorded once the train has gone: it is not at the platform any more.
            for (int call = 0; call < run.call(); call++)
                if (run.departures().get(call) == now)
                    helper.assertTrue(train.getCurrentStation() == null, "the set is recorded as gone from "
                        + run.calls().get(call).station() + " while the train is still there");
            if (outcome != Outcome.FINISHED)
                return;
            for (int call = 0; call < run.calls().size(); call++)
                LOGGER.info("[{}] {}: planned {} / {}, arrived {}, left {}", helper.testInfo.getTestName(), run.calls().get(call).station(),
                    show(run.calls().get(call).hasArrive() ? run.plannedArrival(call) : DutyRun.NOT_YET),
                    show(run.calls().get(call).hasDepart() ? run.plannedDeparture(call) : DutyRun.NOT_YET),
                    show(run.arrivals().get(call)), show(run.departures().get(call)));
            helper.assertTrue(TrainDriver.standsAt(train, "Depot"), "the set is not back at the depot (" + line.describe() + ")");
            check.check(run, minimumStop);
            helper.succeed();
        }

        private static String show(long clock) {
            return clock == DutyRun.NOT_YET ? "-" : TestTimes.show(clock);
        }
    }
}
