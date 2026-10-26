package io.github.eyunya11.createrealtimeschedule.operation;

import static io.github.eyunya11.createrealtimeschedule.operation.OperationTimetables.t;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.operation.Waits.State;
import io.github.eyunya11.createrealtimeschedule.operation.Waits.Target;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Duty;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Line;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Stop;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Train;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Wait;

/**
 * A local, 1A, that stands at S2 from 08:05 to 08:10 and waits there for the express 3001 (2A) to run
 * through S1, and for the local 2 (3A) to leave S2, where it calls from 08:00 to 08:06.
 */
class WaitsTest {

    private static final long DAY = 3;
    private static final long SECOND = RealClock.TICKS_PER_SECOND;
    private static final long MINIMUM = 20 * SECOND;

    private static long at(String time) {
        return DutyPlan.at(DAY, t(time));
    }

    private static Train train(String number, boolean deadhead, Stop... stops) {
        return new Train(number, "l", deadhead ? "" : "local", List.of(stops), deadhead);
    }

    static Timetable overtaking() {
        Line line = new Line("l", "Line", List.of("Depot", "A", "S1", "S2", "B"), false);
        Stop waits = new Stop("S2", t("08:05"), t("08:10"), Stop.NONE, List.of(new Wait("3001", "S1", Stop.NONE), new Wait("2")));
        List<Train> trains = List.of(
            train("D1", true, Stop.leaving("Depot", t("07:50")), Stop.arriving("A", t("07:55"))),
            train("1", false, Stop.leaving("A", t("08:00")), waits, Stop.arriving("B", t("08:20"))),
            train("R1", true, Stop.leaving("B", t("08:30")), Stop.arriving("Depot", t("08:50"))),
            train("DE1", true, Stop.leaving("Depot", t("07:55")), Stop.arriving("A", t("08:00"))),
            train("3001", false, Stop.leaving("A", t("08:02")), Stop.arriving("B", t("08:12"))),
            train("R3", true, Stop.leaving("B", t("08:20")), Stop.arriving("Depot", t("08:40"))),
            train("2", false, Stop.leaving("Depot", t("07:50")), Stop.calling("S2", t("08:00"), t("08:06")), Stop.arriving("B", t("08:14"))),
            train("R2", true, Stop.leaving("B", t("08:25")), Stop.arriving("Depot", t("08:45"))),
            train("9", false, Stop.leaving("A", t("07:00")), Stop.arriving("B", t("07:10"))));
        Timetable timetable = new Timetable("", List.of(line), trains, List.of(
            new Duty("1A", "Depot", List.of("D1", "1", "R1")),
            new Duty("2A", "Depot", List.of("DE1", "3001", "R3")),
            new Duty("3A", "Depot", List.of("2", "R2"))));
        assertEquals(List.of(), timetable.problems());
        return timetable;
    }

    private static final Timetable TIMETABLE = overtaking();
    private static final Map<String, List<Call>> CALLS = calls(TIMETABLE);
    private static final Waits WAITS = Waits.of(TIMETABLE, CALLS);
    /** 1A's call at S2, which waits. */
    private static final Call AT_S2 = CALLS.get("1A").get(2);

    private static Map<String, List<Call>> calls(Timetable timetable) {
        Map<String, List<Call>> calls = new HashMap<>();
        for (Duty duty : timetable.duties())
            calls.put(duty.id(), DutyPlan.calls(timetable, duty));
        return calls;
    }

    @Test
    void aCallHasTheWaitsOfTheStopItLeavesFromWithTheirStations() {
        assertEquals(List.of(new Wait("3001", "S1", Stop.NONE), new Wait("2", "S2", Stop.NONE)), AT_S2.waits());
        assertEquals(List.of(), CALLS.get("1A").get(1).waits());
    }

    @Test
    void aWaitIsForTheCallTheTrainLeavesOrTheWayItRunsThroughTheStationOn() {
        assertEquals(Optional.of(new Target("3001", "2A", 1, true, "S1")), WAITS.target(AT_S2, AT_S2.waits().get(0)));
        assertEquals(Optional.of(new Target("2", "3A", 1, false, "S2")), WAITS.target(AT_S2, AT_S2.waits().get(1)));
        assertEquals(Set.of("S1"), WAITS.watched("2A", 1), "2A's set is watched for running through S1 on its way from A");
        assertEquals(Set.of(), WAITS.watched("2A", 0));
        assertEquals(Set.of(), WAITS.watched("3A", 0), "2 calls at S2, and is not watched there");
    }

    @Test
    void aTrainInNoDutyIsNotWaitedFor() {
        assertEquals(Optional.empty(), WAITS.target(AT_S2, new Wait("9", "S1", Stop.NONE)));
    }

    @Test
    void aTrainThatDoesNotRunThatDayIsNotWaitedFor() {
        Target target = WAITS.target(AT_S2, AT_S2.waits().get(0)).orElseThrow();
        assertEquals(State.OVER, Waits.state(target, null));
    }

    /** 2's set at S2: waited for until it leaves, and not once it is held past it. */
    @Test
    void aTrainThatCallsAtTheStationIsWaitedForUntilItLeaves() {
        Target target = WAITS.target(AT_S2, AT_S2.waits().get(1)).orElseThrow();
        DutyRun two = DutyRun.start("3A", DAY, CALLS.get("3A"), at("07:49"));
        assertEquals(State.WAITING, Waits.state(target, two), "not there yet");
        two.left(at("07:50"));
        two.arrived(at("08:00"), MINIMUM);
        assertEquals(State.WAITING, Waits.state(target, two), "there, and not gone");
        two.left(at("08:06"));
        assertEquals(State.OVER, Waits.state(target, two));
    }

    @Test
    void aTrainThatRunsThroughTheStationIsWaitedForUntilItIsSeenToOrComesInAfterIt() {
        Target target = WAITS.target(AT_S2, AT_S2.waits().get(0)).orElseThrow();
        DutyRun express = DutyRun.start("2A", DAY, CALLS.get("2A"), at("07:54"));
        express.left(at("07:55"));
        express.arrived(at("08:00"), MINIMUM);
        express.left(at("08:02"));
        assertEquals(State.WAITING, Waits.state(target, express), "on its way, not past S1 yet");
        express.passed(1, "S1", at("08:04"));
        assertEquals(State.OVER, Waits.state(target, express));
        DutyRun unseen = DutyRun.restore(express.state(), CALLS.get("2A"));
        DutyRun.State state = unseen.state();
        unseen = DutyRun.restore(new DutyRun.State(state.duty(), state.day(), state.call(), state.phase(), state.departure(), state.arrivals(),
            state.departures(), state.skipped(), List.of()), CALLS.get("2A"));
        unseen.arrived(at("08:12"), MINIMUM);
        assertEquals(State.OVER_UNSEEN, Waits.state(target, unseen), "at B without being seen to run through S1");
    }

    @Test
    void theForecastIsWhenTheTrainLeavesItsCallIfNothingHoldsItUp() {
        Target express = WAITS.target(AT_S2, AT_S2.waits().get(0)).orElseThrow();
        DutyRun run = DutyRun.start("2A", DAY, CALLS.get("2A"), at("07:54"));
        // Out of the depot at 07:55, at A by the timetable's 5 minutes, it leaves at 08:02.
        assertEquals(at("08:02"), Waits.forecast(express, run, at("07:54"), RunTimes.NONE, MINIMUM));
        run.left(at("08:10"));
        // 15 minutes late out of the depot: at A at 08:15, and gone 20 seconds later.
        assertEquals(at("08:15:20"), Waits.forecast(express, run, at("08:11"), RunTimes.NONE, MINIMUM));
        run.arrived(at("08:15"), MINIMUM);
        run.left(at("08:15:20"));
        assertEquals(at("08:15:20"), Waits.forecast(express, run, at("08:16"), RunTimes.NONE, MINIMUM), "once it has left, when it did");
        assertTrue(Waits.forecast(express, run, at("08:16"), RunTimes.NONE, MINIMUM) <= at("08:16"));
    }
}
