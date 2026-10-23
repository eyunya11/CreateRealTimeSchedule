package io.github.eyunya11.createrealtimeschedule.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.operation.DutyRun.Phase;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;

class DutyRunTest {

    private static final long DAY = 3;
    private static final long SECOND = RealClock.TICKS_PER_SECOND;
    private static final long MINIMUM = 20 * SECOND;

    private static final Timetable TIMETABLE = OperationTimetables.shuttle();
    private static final List<Call> CALLS = DutyPlan.calls(TIMETABLE, TIMETABLE.duties().getFirst());

    /** The clock value of a time of the timetable on {@link #DAY}. */
    private static long at(String time) {
        return DAY * RealClock.TICKS_PER_DAY + OperationTimetables.t(time) * SECOND;
    }

    private static DutyRun started() {
        return DutyRun.start("1A", DAY, CALLS, at("07:49"));
    }

    @Test
    void aSetGivenTheDutyEarlyLeavesTheDepotAtItsTime() {
        DutyRun run = started();
        assertEquals(Phase.STANDING, run.phase());
        assertEquals(at("07:50"), run.departure());
        assertFalse(run.dueToLeave(at("07:50") - 1));
        assertTrue(run.dueToLeave(at("07:50")));
        assertEquals("A", run.nextStation());
    }

    @Test
    void aSetGivenTheDutyLateLeavesTheDepotAtOnce() {
        DutyRun run = DutyRun.start("1A", DAY, CALLS, at("07:51"));
        assertEquals(at("07:51"), run.departure());
        assertEquals(60 * SECOND, run.delay());
    }

    @Test
    void anEarlySetWaitsForItsTime() {
        DutyRun run = started();
        run.left(at("07:50"));
        assertEquals(Phase.RUNNING, run.phase());
        assertEquals("A", run.nextStation());
        run.arrived(at("07:53"), MINIMUM);
        assertEquals(Phase.STANDING, run.phase());
        assertEquals(1, run.call());
        assertEquals(at("08:00"), run.departure());
        assertEquals("M", run.nextStation());
    }

    @Test
    void aLateSetStopsForTheMinimumAndGoes() {
        DutyRun run = started();
        run.left(at("07:50"));
        run.arrived(at("08:00"), MINIMUM);
        assertEquals(at("08:00") + MINIMUM, run.departure());
        assertEquals(MINIMUM, run.delay());
    }

    @Test
    void theDutyEndsAtTheLastCall() {
        DutyRun run = started();
        for (int call = 1; call < CALLS.size(); call++) {
            run.left(run.departure());
            run.arrived(run.plannedArrival(call), MINIMUM);
        }
        assertTrue(run.finished());
        assertEquals(CALLS.size() - 1, run.call());
        assertEquals(0, run.delay());
    }

    @Test
    void recordsWhenTheSetReallyArrivedAndLeft() {
        DutyRun run = started();
        run.left(at("07:50") + 3);
        run.arrived(at("07:54"), MINIMUM);
        assertEquals(List.of(DutyRun.NOT_YET, at("07:54"), DutyRun.NOT_YET, DutyRun.NOT_YET, DutyRun.NOT_YET, DutyRun.NOT_YET), run.arrivals());
        assertEquals(List.of(at("07:50") + 3, DutyRun.NOT_YET, DutyRun.NOT_YET, DutyRun.NOT_YET, DutyRun.NOT_YET, DutyRun.NOT_YET), run.departures());
    }

    @Test
    void aTimePastMidnightIsOnTheNextDay() {
        DutyRun run = DutyRun.start("N", DAY, List.of(new Call("A", -1, OperationTimetables.t("24:10"), "", "N1"),
            new Call("B", OperationTimetables.t("24:20"), -1, "N1", "")), at("23:00"));
        assertEquals((DAY + 1) * RealClock.TICKS_PER_DAY + 10 * RealClock.TICKS_PER_MINUTE, run.departure());
    }

    @Test
    void aRunIsMadeAgainFromWhatIsSaved() {
        DutyRun run = started();
        run.left(at("07:50"));
        run.arrived(at("07:56"), MINIMUM);
        DutyRun again = DutyRun.restore(run.state(), CALLS);
        assertEquals(run.state(), again.state());
        again.left(again.departure());
        assertEquals("M", DutyRun.restore(again.state(), CALLS).nextStation());
    }

    @Test
    void aSavedRunDoesNotFitCallsThatHaveChanged() {
        DutyRun.State state = started().state();
        assertThrows(IllegalArgumentException.class, () -> DutyRun.restore(state, CALLS.subList(0, 3)));
    }

    @Test
    void theSetCannotLeaveWhileItIsRunningNorArriveWhileItStands() {
        DutyRun run = started();
        assertThrows(IllegalStateException.class, () -> run.arrived(at("07:50"), MINIMUM));
        run.left(at("07:50"));
        assertThrows(IllegalStateException.class, () -> run.left(at("07:51")));
    }
}
