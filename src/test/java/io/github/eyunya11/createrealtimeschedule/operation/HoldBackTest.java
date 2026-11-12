package io.github.eyunya11.createrealtimeschedule.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.operation.HoldBack.Step;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;

/** The shuttle of {@link OperationTimetables#shuttle}: its set is to leave A at 08:00, 10 minutes the threshold. */
class HoldBackTest {

    private static final long DAY = 3;
    private static final long SECOND = RealClock.TICKS_PER_SECOND;
    private static final long THRESHOLD = 600 * SECOND;
    private static final long MINIMUM = 20 * SECOND;

    private static final Timetable TIMETABLE = OperationTimetables.shuttle();
    private static final List<Call> CALLS = DutyPlan.calls(TIMETABLE, TIMETABLE.duties().getFirst());

    private static long at(String time) {
        return DutyPlan.at(DAY, OperationTimetables.t(time));
    }

    /** The set, come into A at {@code time}. */
    private static DutyRun cameIntoA(String time) {
        DutyRun run = DutyRun.start("1A", DAY, CALLS, at("07:49"));
        run.left(at("07:50"));
        run.arrived(at(time), MINIMUM);
        return run;
    }

    @Test
    void aSetNoLaterThanTheThresholdIsNotHeldBack() {
        DutyRun run = cameIntoA("08:05");
        assertEquals(Step.FORGET, HoldBack.step(run, THRESHOLD, at("08:05"), at("08:05"), Long.MIN_VALUE, true));
        assertEquals(Step.FORGET, HoldBack.step(cameIntoA("08:15"), 0, at("08:15"), at("08:15"), Long.MIN_VALUE, true),
            "with no threshold, no set is held");
    }

    /** A set too late waits for the run going on, and is decided once the sets have been run on from when it came in. */
    @Test
    void aSetTooLateWaitsForTheSetsToBeRunOnFromWhenItCameIn() {
        DutyRun run = cameIntoA("08:15");
        long cameIn = at("08:15");
        assertEquals(Step.WAIT, HoldBack.step(run, THRESHOLD, cameIn, cameIn + 5, cameIn - 100, true), "the sets were run on before");
        assertEquals(Step.DECIDE, HoldBack.step(run, THRESHOLD, cameIn, cameIn + 5, cameIn, true), "from when it came in");
        assertEquals(Step.DECIDE, HoldBack.step(run, THRESHOLD, cameIn, cameIn + 5, cameIn + 3, false), "from later");
    }

    /** It is decided when it is to leave, whatever the run of the sets has got to. */
    @Test
    void aSetDueToLeaveIsDecidedAtOnce() {
        DutyRun run = cameIntoA("08:15");
        assertEquals(Step.DECIDE, HoldBack.step(run, THRESHOLD, at("08:15"), at("08:15") + MINIMUM, Long.MIN_VALUE, true));
        assertEquals(Step.WAIT, HoldBack.step(run, THRESHOLD, at("08:15"), at("08:15") + MINIMUM - 1, Long.MIN_VALUE, true));
    }

    /** With no run going, as after the server was started again, the sets are to be run on. */
    @Test
    void aSetTooLateWithNoRunGoingHasTheSetsRunOn() {
        DutyRun run = cameIntoA("08:15");
        assertEquals(Step.RUN, HoldBack.step(run, THRESHOLD, at("08:15"), at("08:15") + 5, Long.MIN_VALUE, false));
    }

    /** A set that has left since is not held. */
    @Test
    void aSetThatHasLeftIsForgotten() {
        DutyRun run = cameIntoA("08:15");
        run.left(at("08:16"));
        assertEquals(Step.FORGET, HoldBack.step(run, THRESHOLD, at("08:15"), at("08:16"), Long.MIN_VALUE, true));
    }
}
