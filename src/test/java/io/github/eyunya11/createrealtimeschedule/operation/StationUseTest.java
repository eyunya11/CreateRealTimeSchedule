package io.github.eyunya11.createrealtimeschedule.operation;

import static io.github.eyunya11.createrealtimeschedule.operation.OperationTimetables.t;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.diagram.LineAxis;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Duty;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Line;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Stop;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Train;

class StationUseTest {

    private static final long DAY = 3;
    private static final long LEAD = 60 * RealClock.TICKS_PER_SECOND;

    private static long at(String time) {
        return DutyPlan.at(DAY, t(time));
    }

    static Map<String, List<Call>> calls(Timetable timetable) {
        Map<String, List<Call>> calls = new HashMap<>();
        for (Duty duty : timetable.duties())
            calls.put(duty.id(), DutyPlan.calls(timetable, duty));
        return calls;
    }

    /** The laps of 1A, and the express of 2A, which runs from the depot to B through A and back, from 08:20 to 08:55. */
    static Timetable withExpress(String from) {
        int start = t(from);
        Train out = new Train("E1", "l", "rapid", List.of(Stop.leaving("Depot", start), Stop.arriving("B", start + 15 * 60)));
        Train back = new Train("E2", "l", "rapid", List.of(Stop.leaving("B", start + 20 * 60), Stop.arriving("Depot", start + 35 * 60)));
        return OperationTimetables.laps(Stop.NONE, List.of(), List.of(out, back), List.of(new Duty("2A", "Depot", List.of("E1", "E2"))));
    }

    @Test
    void aTrainThatRunsThroughTheStationMeanwhileIsFound() {
        Timetable timetable = withExpress("08:20");
        Map<String, List<Call>> calls = calls(timetable);
        assertTrue(StationUse.passedBy(timetable, calls, Map.of(), "1A", DAY, "A", at("08:25"), at("08:30")));
        assertFalse(StationUse.passedBy(timetable, calls, Map.of(), "1A", DAY, "A", at("08:36"), at("08:39")),
            "E1 has got to B and E2 has not left it");
        assertTrue(StationUse.passedBy(timetable, calls, Map.of(), "1A", DAY, "A", at("08:36"), at("08:45")), "E2 runs back through A");
        assertFalse(StationUse.passedBy(timetable, calls, Map.of(), "1A", DAY, "B", at("08:00"), at("09:30")),
            "the express ends at B and starts there again; it does not run through it");
        assertFalse(StationUse.passedBy(timetable, calls, Map.of(), "1A", DAY, "A", at("10:00"), at("11:00")));
        // A train of the day before, or the day after, runs through on its own day only.
        assertFalse(StationUse.passedBy(timetable, calls, Map.of(), "1A", DAY, "A", at("08:25") + RealClock.TICKS_PER_DAY / 2,
            at("08:30") + RealClock.TICKS_PER_DAY / 2));
        assertTrue(StationUse.passedBy(timetable, calls, Map.of(), "1A", DAY, "A", at("08:25") + RealClock.TICKS_PER_DAY,
            at("08:30") + RealClock.TICKS_PER_DAY));
    }

    @Test
    void theHeldDutyItselfIsLeftOutButNotOnAnotherDay() {
        Timetable timetable = withExpress("08:20");
        Map<String, List<Call>> calls = calls(timetable);
        assertFalse(StationUse.passedBy(timetable, calls, Map.of(), "2A", DAY, "A", at("08:25"), at("08:30")), "the express is the one held");
        assertTrue(StationUse.passedBy(timetable, calls, Map.of(), "2A", DAY - 1, "A", at("08:25"), at("08:30")),
            "held on the day before, it is the express of this day that runs through");
    }

    @Test
    void aTrainOnALineThatIsNotALoopRunsThroughTheStationsBetweenItsStops() {
        Timetable timetable = OperationTimetables.shuttle();
        Train down = timetable.train("1").orElseThrow();
        assertTrue(StationUse.runsThrough(timetable, Map.of(), down, "A", "B", "M"));
        assertFalse(StationUse.runsThrough(timetable, Map.of(), down, "A", "M", "B"));
        assertFalse(StationUse.runsThrough(timetable, Map.of(), down, "A", "B", "A"), "it stops at A");
        assertTrue(StationUse.runsThrough(timetable, Map.of(), down, "B", "A", "M"), "either way along the line");
    }

    @Test
    void aTrainOnALoopRunsThroughTheStationsTheWayRoundItGoes() {
        Line loop = new Line("o", "Loop", List.of("A", "B", "C", "D"), true);
        Train train = new Train("1", "o", "", List.of(Stop.leaving("A", t("08:00")), Stop.arriving("C", t("08:10"))));
        Timetable timetable = new Timetable("", List.of(loop), List.of(train), List.of());
        // With nothing from the track, A to C passes as many stations either way, and goes down: through B.
        assertTrue(StationUse.runsThrough(timetable, Map.of(), train, "A", "C", "B"));
        assertFalse(StationUse.runsThrough(timetable, Map.of(), train, "A", "C", "D"));
        // The track takes it up the axis from A to C: through D.
        LineAxis up = new LineAxis("o", true, List.of("A", "B", "C", "D", "A"), List.of(0.0, 10.0, 20.0, 30.0, 40.0),
            List.of(new LineAxis.Way("A", "C", false)));
        assertFalse(StationUse.runsThrough(timetable, Map.of("o", up), train, "A", "C", "B"));
        assertTrue(StationUse.runsThrough(timetable, Map.of("o", up), train, "A", "C", "D"));
    }

    /** The laps of 1A, and the set of 3A, which comes from the depot to stand at A from 08:26 to 08:29. */
    static Timetable withSetStandingAtA() {
        Train in = new Train("S1", "l", "", List.of(Stop.leaving("Depot", t("08:20")), Stop.arriving("A", t("08:26"))), true);
        Train out = new Train("S2", "l", "", List.of(Stop.leaving("A", t("08:29")), Stop.arriving("Depot", t("08:35"))), true);
        return OperationTimetables.laps(Stop.NONE, List.of(), List.of(in, out), List.of(new Duty("3A", "Depot", List.of("S1", "S2"))));
    }

    @Test
    void theSetsThatComeToStandAtTheStationMeanwhileAreCounted() {
        Map<String, List<Call>> calls = calls(withSetStandingAtA());
        assertEquals(1, StationUse.mostStanding(calls, "1A", DAY, "A", at("08:25"), at("08:30"), at("08:25"), LEAD));
        assertEquals(0, StationUse.mostStanding(calls, "1A", DAY, "A", at("08:30"), at("08:40"), at("08:30"), LEAD));
        assertEquals(0, StationUse.mostStanding(calls, "1A", DAY, "A", at("08:25"), at("08:30"), at("08:27"), LEAD),
            "one that has come in already is a train standing there");
        assertEquals(1, StationUse.mostStanding(calls, "1A", DAY, "A", at("08:25"), at("08:27"), at("08:25"), LEAD));
        assertEquals(0, StationUse.mostStanding(calls, "3A", DAY, "A", at("08:25"), at("08:27"), at("08:25"), LEAD), "3A itself");
        // 1A's own set stands at A from 07:55 to 08:00 on its own day; held as 3A, it is in the way.
        assertEquals(1, StationUse.mostStanding(calls, "3A", DAY, "A", at("07:50"), at("08:10"), at("07:50"), LEAD));
    }

    @Test
    void setsAtTheDepotStandThereALeadBeforeTheyLeaveAndAfterTheyComeBack() {
        Map<String, List<Call>> calls = calls(withSetStandingAtA());
        // 1A leaves the depot at 07:50, 3A at 08:20: given their sets a minute before.
        assertEquals(1, StationUse.mostStanding(calls, "x", DAY, "Depot", at("07:48"), at("07:49:30"), at("07:00"), LEAD));
        assertEquals(0, StationUse.mostStanding(calls, "x", DAY, "Depot", at("07:40"), at("07:48"), at("07:00"), LEAD));
        // 3A is back at 08:35 and stays; 1A comes back at 09:35 and stays too.
        assertEquals(1, StationUse.mostStanding(calls, "x", DAY, "Depot", at("09:00"), at("09:10"), at("08:30"), LEAD));
        assertEquals(2, StationUse.mostStanding(calls, "x", DAY, "Depot", at("09:00"), at("09:40"), at("08:30"), LEAD));
    }

    @Test
    void theMostAtOnceIsCountedNotAllOfThem() {
        // Two sets that stand at A one after the other need one platform, not two.
        Train in = new Train("S1", "l", "", List.of(Stop.leaving("Depot", t("08:20")), Stop.arriving("A", t("08:26"))), true);
        Train out = new Train("S2", "l", "", List.of(Stop.leaving("A", t("08:27")), Stop.arriving("Depot", t("08:33"))), true);
        Train in2 = new Train("S3", "l", "", List.of(Stop.leaving("Depot", t("08:21")), Stop.arriving("A", t("08:28"))), true);
        Train out2 = new Train("S4", "l", "", List.of(Stop.leaving("A", t("08:29")), Stop.arriving("Depot", t("08:35"))), true);
        Timetable timetable = OperationTimetables.laps(Stop.NONE, List.of(), List.of(in, out, in2, out2),
            List.of(new Duty("3A", "Depot", List.of("S1", "S2")), new Duty("4A", "Depot", List.of("S3", "S4"))));
        assertEquals(1, StationUse.mostStanding(calls(timetable), "1A", DAY, "A", at("08:25"), at("08:30"), at("08:25"), LEAD));
    }
}
