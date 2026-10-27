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
        assertEquals(60 * SECOND, run.delay(at("07:51")));
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
        assertEquals(MINIMUM, run.delay(at("08:00")));
    }

    /** A set held at a call past its time to leave, as by a train it waits for, is as late as it is held. */
    @Test
    void aSetStandingPastItsTimeIsAsLateAsNow() {
        DutyRun run = started();
        run.left(at("07:50"));
        run.arrived(at("07:58"), MINIMUM);
        assertEquals(0, run.delay(at("07:59")));
        assertEquals(3 * RealClock.TICKS_PER_MINUTE, run.delay(at("08:03")));
    }

    @Test
    void aLateSetStopsForTheMinimumTheTimetableGivesTheCall() {
        List<Call> calls = List.of(new Call("Depot", -1, OperationTimetables.t("07:50"), "", "D1"),
            new Call("A", OperationTimetables.t("07:55"), OperationTimetables.t("08:00"), "D1", "1", 45),
            new Call("B", OperationTimetables.t("08:10"), OperationTimetables.t("08:15"), "1", "2", 0),
            new Call("Depot", OperationTimetables.t("08:20"), -1, "2", ""));
        DutyRun run = DutyRun.start("1A", DAY, calls, at("07:49"));
        run.left(at("07:50"));
        run.arrived(at("08:00"), MINIMUM);
        assertEquals(at("08:00") + 45 * SECOND, run.departure(), "the call's own minimum, not the server's");
        run.left(run.departure());
        run.arrived(at("08:16"), MINIMUM);
        assertEquals(at("08:16"), run.departure(), "a minimum stop of nothing lets the set go at once");
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
        assertEquals(0, run.delay(at("09:00")));
    }

    /** Create holds a train at the platform until the signal ahead is green; the set has left only when the train has. */
    @Test
    void aSetSentOnButHeldAtThePlatformHasNotLeftYet() {
        DutyRun run = started();
        run.sent();
        assertEquals(Phase.DEPARTING, run.phase());
        assertEquals("A", run.nextStation());
        assertFalse(run.dueToLeave(at("07:51")), "a set already sent on is not sent again");
        assertEquals(0, run.delay(at("07:50")));
        assertEquals(2 * RealClock.TICKS_PER_MINUTE, run.delay(at("07:52")), "held for two minutes, it is two minutes late");
        run.left(at("07:52"));
        assertEquals(at("07:52"), run.departures().get(0));
        assertEquals(Phase.RUNNING, run.phase());
        assertEquals(2 * RealClock.TICKS_PER_MINUTE, run.delay(at("07:53")));
        assertThrows(IllegalStateException.class, run::sent);
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

    /** The shuttle's set, late at A, held there until the call at A the duty comes back to (call 4), so that 1 and 2 do not run. */
    private static DutyRun heldAtA() {
        DutyRun run = started();
        run.left(at("07:50"));
        run.arrived(at("08:15"), MINIMUM);
        run.hold(4, at("08:30"));
        return run;
    }

    @Test
    void aSetHeldAtAStationLeavesFromTheLaterCallThereAndMakesNoneInBetween() {
        DutyRun run = heldAtA();
        assertEquals(4, run.call());
        assertEquals(Phase.STANDING, run.phase());
        assertEquals(at("08:30"), run.departure());
        assertEquals(0, run.delay(at("08:20")), "it leaves on time from the call it is held to");
        assertEquals("Depot", run.nextStation());
        assertEquals(List.of(2, 3), run.skipped());
        assertTrue(run.skipped(2) && run.skipped(3) && !run.skipped(1) && !run.skipped(4));
        assertFalse(run.dueToLeave(at("08:30") - 1));
        run.left(at("08:30"));
        assertEquals(5, run.call());
        assertEquals(0, run.delay(at("08:31")), "on the way, it is as late as it left the call it was held to");
        run.arrived(at("08:35"), MINIMUM);
        assertTrue(run.finished());
        // It came into A at 08:15 and left it at 08:30; it neither left call 1 nor came into call 4.
        assertEquals(List.of(DutyRun.NOT_YET, at("08:15"), DutyRun.NOT_YET, DutyRun.NOT_YET, DutyRun.NOT_YET, at("08:35")), run.arrivals());
        assertEquals(List.of(at("07:50"), DutyRun.NOT_YET, DutyRun.NOT_YET, DutyRun.NOT_YET, at("08:30"), DutyRun.NOT_YET), run.departures());
    }

    @Test
    void aSetIsHeldOnlyToALaterCallAtTheSameStationThatItCanLeave() {
        DutyRun run = started();
        run.left(at("07:50"));
        run.arrived(at("08:15"), MINIMUM);
        assertThrows(IllegalArgumentException.class, () -> run.hold(3, at("08:15")), "B is another station");
        assertThrows(IllegalArgumentException.class, () -> run.hold(5, at("08:40")), "the last call is not left");
        assertThrows(IllegalArgumentException.class, () -> run.hold(1, at("08:00")), "the call it stands at is no later one");
        run.left(at("08:15"));
        assertThrows(IllegalStateException.class, () -> run.hold(4, at("08:30")), "a running set is not held");
    }

    @Test
    void aHeldRunIsMadeAgainFromWhatIsSaved() {
        DutyRun run = heldAtA();
        DutyRun again = DutyRun.restore(run.state(), CALLS);
        assertEquals(run.state(), again.state());
        assertEquals(List.of(2, 3), again.state().skipped());
        assertEquals("Depot", again.nextStation());
        again.left(again.departure());
        assertEquals(5, again.call());
        assertEquals(List.of(), started().state().skipped(), "a run that makes every call leaves none out");
    }

    @Test
    void aSavedRunThatLeavesOutCallsItCannotIsRefused() {
        DutyRun.State held = heldAtA().state();
        for (List<Integer> skipped : List.of(List.of(2), List.of(1, 2, 3), List.of(3, 2), List.of(0), List.of(5), List.of(2, 3, 4)))
            assertThrows(IllegalArgumentException.class, () -> DutyRun.restore(withSkipped(held, skipped), CALLS), skipped::toString);
        // Saved before calls could be left out: none are.
        DutyRun.State old = new DutyRun.State(held.duty(), held.day(), 1, Phase.STANDING, held.departure(), held.arrivals(), held.departures());
        assertEquals(List.of(), DutyRun.restore(old, CALLS).skipped());
    }

    private static DutyRun.State withSkipped(DutyRun.State state, List<Integer> skipped) {
        return new DutyRun.State(state.duty(), state.day(), state.call(), state.phase(), state.departure(), state.arrivals(), state.departures(),
            skipped);
    }

    @Test
    void theStationsASetRunsThroughAreKeptOnceAndSaved() {
        DutyRun run = started();
        run.left(at("07:50"));
        run.arrived(at("07:55"), MINIMUM);
        run.left(at("08:00"));
        run.passed(1, "X", at("08:02"));
        run.passed(1, "X", at("08:03"));
        assertTrue(run.passedBy(1, "X"));
        assertFalse(run.passedBy(2, "X"));
        assertEquals(List.of(new DutyRun.Pass(1, "X", at("08:02"))), run.passes(), "the first time it is seen");
        DutyRun again = DutyRun.restore(run.state(), CALLS);
        assertEquals(run.state(), again.state());
        assertTrue(again.passedBy(1, "X"));
        DutyRun.State state = run.state();
        assertThrows(IllegalArgumentException.class, () -> DutyRun.restore(new DutyRun.State(state.duty(), state.day(), state.call(), state.phase(),
            state.departure(), state.arrivals(), state.departures(), state.skipped(), List.of(new DutyRun.Pass(5, "X", 0))), CALLS),
            "the last call has no way on");
    }

    @Test
    void theSetCannotLeaveWhileItIsRunningNorArriveWhileItStands() {
        DutyRun run = started();
        assertThrows(IllegalStateException.class, () -> run.arrived(at("07:50"), MINIMUM));
        run.left(at("07:50"));
        assertThrows(IllegalStateException.class, () -> run.left(at("07:51")));
    }
}
