package io.github.eyunya11.createrealtimeschedule.operation;

import static io.github.eyunya11.createrealtimeschedule.operation.OperationTimetables.t;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.diagram.LineAxis;
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
 * through S1, for the local 2 (3A) to leave S2, where it calls from 08:00 to 08:06, and for 9, which
 * is in no duty.
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
        Stop waits = new Stop("S2", t("08:05"), t("08:10"), Stop.NONE,
            List.of(new Wait("3001", "S1", Stop.NONE), new Wait("2"), new Wait("9", "S1", Stop.NONE)));
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
    private static final Waits WAITS = Waits.of(TIMETABLE, CALLS, Map.of());
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
        assertEquals(List.of(new Wait("3001", "S1", Stop.NONE), new Wait("2", "S2", Stop.NONE), new Wait("9", "S1", Stop.NONE)), AT_S2.waits());
        assertEquals(List.of(), CALLS.get("1A").get(1).waits());
    }

    @Test
    void aWaitIsForTheCallTheTrainLeavesOrTheWayItRunsThroughTheStationOn() {
        assertEquals(Optional.of(new Target("3001", "2A", 1, true, "S1")), WAITS.target("1A", 2, 0));
        assertEquals(Optional.of(new Target("2", "3A", 1, false, "S2")), WAITS.target("1A", 2, 1));
        assertEquals(Optional.empty(), WAITS.target("1A", 1, 0), "the call at A waits for nothing");
        assertEquals(Set.of("S1"), WAITS.watched("2A", 1), "2A's set is watched for running through S1 on its way from A");
        assertEquals(Set.of(), WAITS.watched("2A", 0));
        assertEquals(Set.of(), WAITS.watched("3A", 0), "2 calls at S2, and is not watched there");
    }

    @Test
    void aTrainInNoDutyIsNotWaitedFor() {
        assertEquals(Optional.empty(), WAITS.target("1A", 2, 2));
        assertEquals(List.of(new Waits.Unmet("1", "S2", new Wait("9", "S1", Stop.NONE), true)), WAITS.unmet(),
            "a wait that waits for nothing, so that the maker can be told");
    }

    /**
     * On a loop line, the express X goes from A to C, which is as far either way round: which way it goes,
     * and so whether it runs through D, where a local waits for it, is the track's.
     */
    @Test
    void onALoopTheTrainWaitedForGoesTheWayRoundTheTrackTakesIt() {
        Line loop = new Line("o", "Loop", List.of("A", "B", "C", "D"), true);
        Timetable timetable = new Timetable("", List.of(loop), List.of(
            new Train("X", "o", "", List.of(Stop.leaving("A", t("08:00")), Stop.arriving("C", t("08:10"))), false),
            new Train("L", "o", "", List.of(new Stop("B", Stop.NONE, t("08:05"), Stop.NONE, List.of(new Wait("X", "D", Stop.NONE))),
                Stop.arriving("C", t("08:15"))), false)),
            List.of(new Duty("E", "A", List.of("X")), new Duty("W", "B", List.of("L"))));
        Map<String, List<Call>> calls = calls(timetable);
        Waits byTheList = Waits.of(timetable, calls, Map.of());
        assertEquals(Optional.empty(), byTheList.target("W", 0, 0), "with nothing from the track, down the list: through B");
        assertEquals(List.of(new Waits.Unmet("L", "B", new Wait("X", "D", Stop.NONE), false)), byTheList.unmet(),
            "X does not run through D before L leaves B");
        LineAxis up = new LineAxis("o", true, List.of("A", "B", "C", "D", "A"), List.of(0.0, 10.0, 20.0, 30.0, 40.0),
            List.of(new LineAxis.Way("A", "C", false)));
        Waits byTheTrack = Waits.of(timetable, calls, Map.of("o", up));
        assertEquals(Optional.of(new Target("X", "E", 0, true, "D")), byTheTrack.target("W", 0, 0));
        assertEquals(Set.of("D"), byTheTrack.watched("E", 0));
        assertEquals(List.of(), byTheTrack.unmet());
    }

    @Test
    void aTrainThatDoesNotRunThatDayIsNotWaitedFor() {
        Target target = WAITS.target("1A", 2, 0).orElseThrow();
        assertEquals(State.OVER, Waits.state(target, null));
    }

    /** 2's set at S2: waited for until it leaves, and not once it is held past it. */
    @Test
    void aTrainThatCallsAtTheStationIsWaitedForUntilItLeaves() {
        Target target = WAITS.target("1A", 2, 1).orElseThrow();
        DutyRun two = DutyRun.start("3A", DAY, CALLS.get("3A"), at("07:49"));
        assertEquals(State.WAITING, Waits.state(target, two), "not there yet");
        two.left(at("07:50"));
        two.arrived(at("08:00"), MINIMUM);
        assertEquals(State.WAITING, Waits.state(target, two), "there, and not gone");
        two.left(at("08:06"));
        assertEquals(State.OVER, Waits.state(target, two));
    }

    /**
     * The express is waited for until it is seen to run through S1, or comes in at the stop after the
     * next without being seen to: at its next stop, B, the end of a long train may still be short of S1.
     */
    @Test
    void aTrainThatRunsThroughTheStationIsWaitedForUntilItIsSeenToOrComesInAtTheStopAfterTheNext() {
        Target target = WAITS.target("1A", 2, 0).orElseThrow();
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
        assertEquals(State.WAITING, Waits.state(target, unseen), "at B, its end may not be past S1 yet");
        unseen.left(at("08:20"));
        unseen.arrived(at("08:40"), MINIMUM);
        assertEquals(State.OVER_UNSEEN, Waits.state(target, unseen), "back at the depot without being seen to run through S1");
        assertFalse(unseen.passedBy(1, "S1"));
    }

    /**
     * The local L of 1A waits at P for the express X of 2A to leave P, and to run through M. X's set
     * comes in at P 20 minutes late and is held there until its next call at P, as the recovery does,
     * so X does not run: the waits are over, and stay so once the set has left P as X2.
     */
    @Test
    void aTrainHeldAtTheStationUntilALaterCallIsNotWaitedFor() {
        Line line = new Line("l", "Line", List.of("Depot", "P", "M", "B"), false);
        Stop waits = new Stop("P", Stop.NONE, t("08:12"), Stop.NONE, List.of(new Wait("X", "", 3600), new Wait("X", "M", 3600)));
        Timetable timetable = new Timetable("", List.of(line), List.of(
            train("E0", true, Stop.leaving("Depot", t("08:00")), Stop.arriving("P", t("08:05"))),
            train("X", false, Stop.leaving("P", t("08:10")), Stop.arriving("B", t("08:20"))),
            train("Y", false, Stop.leaving("B", t("08:25")), Stop.arriving("P", t("08:35"))),
            train("X2", false, Stop.leaving("P", t("08:40")), Stop.arriving("B", t("08:50"))),
            train("E9", true, Stop.leaving("B", t("08:55")), Stop.arriving("Depot", t("09:05"))),
            train("L0", true, Stop.leaving("Depot", t("08:01")), Stop.arriving("P", t("08:06"))),
            train("L", false, waits, Stop.arriving("B", t("08:25"))),
            train("L9", true, Stop.leaving("B", t("08:30")), Stop.arriving("Depot", t("08:40")))),
            List.of(new Duty("2A", "Depot", List.of("E0", "X", "Y", "X2", "E9")), new Duty("1A", "Depot", List.of("L0", "L", "L9"))));
        assertEquals(List.of(), timetable.problems());
        Map<String, List<Call>> calls = calls(timetable);
        Waits all = Waits.of(timetable, calls, Map.of());
        Target leaves = all.target("1A", 1, 0).orElseThrow();
        Target runsThrough = all.target("1A", 1, 1).orElseThrow();
        assertEquals(new Target("X", "2A", 1, false, "P"), leaves);
        assertEquals(new Target("X", "2A", 1, true, "M"), runsThrough);

        DutyRun express = DutyRun.start("2A", DAY, calls.get("2A"), at("07:59"));
        express.left(at("08:00"));
        express.arrived(at("08:25"), MINIMUM);
        assertEquals(State.WAITING, Waits.state(leaves, express), "at P, and not gone");
        assertEquals(State.WAITING, Waits.state(runsThrough, express));
        express.hold(3, at("08:40"));
        assertEquals(State.OVER, Waits.state(leaves, express), "held at P until X2: X does not leave P");
        assertEquals(State.OVER, Waits.state(runsThrough, express), "nor run through M");
        express.left(at("08:40"));
        assertEquals(State.OVER, Waits.state(leaves, express), "gone from P as X2, not as X");
    }

    @Test
    void theForecastIsWhenTheTrainLeavesItsCallIfNothingHoldsItUp() {
        Target express = WAITS.target("1A", 2, 0).orElseThrow();
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
