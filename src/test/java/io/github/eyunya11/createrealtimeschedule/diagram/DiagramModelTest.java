package io.github.eyunya11.createrealtimeschedule.diagram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.diagram.DiagramModel.PlannedTrain;
import io.github.eyunya11.createrealtimeschedule.diagram.DiagramModel.RanDuty;
import io.github.eyunya11.createrealtimeschedule.diagram.DiagramModel.Segment;
import io.github.eyunya11.createrealtimeschedule.operation.DutyRun;
import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import io.github.eyunya11.createrealtimeschedule.timetable.ServiceTime;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Duty;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Line;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Stop;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Train;

class DiagramModelTest {

    private static final long DAY = 5;
    private static final long X = DutyRun.NOT_YET;

    private static int t(String time) {
        return ServiceTime.of(time);
    }

    /** The clock value of {@code time} on {@link #DAY}. */
    private static long at(String time) {
        return DAY * RealClock.TICKS_PER_DAY + t(time) * (long) RealClock.TICKS_PER_SECOND;
    }

    private static final Line LINE = new Line("l", "Line", List.of("Depot", "A", "M", "B"), false);
    /** The depot is 20 blocks from A, A 40 from M, and M 60 from B. */
    private static final List<Leg> LEGS = List.of(
        new Leg("Depot", "A", 20, List.of(), List.of()), new Leg("A", "M", 40, List.of(), List.of()), new Leg("M", "B", 60, List.of(), List.of()));
    private static final LineAxis AXIS = LineAxis.of(LINE, LEGS);

    /**
     * Out of the depot to A, train 1 to B calling at M, train 2 back to A, and into the depot: the duty
     * 1A. Train 2 is on {@code upLine}.
     */
    private static Timetable timetable(String upLine) {
        List<Line> lines = upLine.equals("l") ? List.of(LINE) : List.of(LINE, new Line(upLine, "Up", List.of("B", "A"), false));
        Train out = new Train("D1", "l", "", List.of(Stop.leaving("Depot", t("07:50")), Stop.arriving("A", t("07:55"))), true);
        Train down = new Train("1", "l", "local", List.of(
            Stop.leaving("A", t("08:00")), Stop.calling("M", t("08:05"), t("08:06")), Stop.arriving("B", t("08:10"))));
        Train up = new Train("2", upLine, "local", List.of(Stop.leaving("B", t("08:15")), Stop.arriving("A", t("08:25"))));
        Train in = new Train("D2", "l", "", List.of(Stop.leaving("A", t("08:30")), Stop.arriving("Depot", t("08:35"))), true);
        Timetable timetable = new Timetable("", lines, List.of(out, down, up, in),
            List.of(new Duty("1A", "Depot", List.of("D1", "1", "2", "D2"))));
        assertEquals(List.of(), timetable.problems());
        return timetable;
    }

    private static DutyTimes times(DutyTimes.Status status, long[] arrivals, long[] departures) {
        return new DutyTimes("1A", DAY, "Train 1", status, Arrays.stream(arrivals).boxed().toList(), Arrays.stream(departures).boxed().toList());
    }

    private static PlannedTrain planned(DiagramModel model, String number) {
        return model.planned().stream().filter(train -> train.number().equals(number)).findFirst().orElseThrow();
    }

    @Test
    void aPlannedTrainSlopesBetweenStationsAndIsLevelWhereItStands() {
        DiagramModel model = DiagramModel.of(timetable("l"), AXIS, DAY, List.of(), at("08:00"));
        PlannedTrain one = planned(model, "1");
        assertEquals(List.of(
            new Segment(t("08:00"), 20, t("08:05"), 60),
            new Segment(t("08:05"), 60, t("08:06"), 60),
            new Segment(t("08:06"), 60, t("08:10"), 120)), one.segments());
        assertEquals(t("08:00"), one.labelSecond());
        assertEquals(20, one.labelPosition());
        assertFalse(one.deadhead());
        assertTrue(planned(model, "D1").deadhead());
        assertEquals(List.of(new Segment(t("08:15"), 120, t("08:25"), 20)), planned(model, "2").segments());
        assertEquals(4, model.planned().size());
    }

    @Test
    void aStopWithOnlyADepartureIsPassedThroughAtThatTime() {
        Train through = new Train("3", "l", "", List.of(Stop.leaving("A", t("09:00")), Stop.leaving("M", t("09:04")), Stop.arriving("B", t("09:10"))));
        Timetable timetable = new Timetable("", List.of(LINE), List.of(through), List.of());
        PlannedTrain three = DiagramModel.of(timetable, AXIS, DAY, List.of(), 0).planned().getFirst();
        assertEquals(List.of(new Segment(t("09:00"), 20, t("09:04"), 60), new Segment(t("09:04"), 60, t("09:10"), 120)), three.segments());
    }

    @Test
    void aTrainPastMidnightRunsOnPast24Hours() {
        Train late = new Train("9", "l", "", List.of(Stop.leaving("A", t("23:58")), Stop.arriving("M", t("24:03"))));
        Timetable timetable = new Timetable("", List.of(LINE), List.of(late), List.of());
        assertEquals(List.of(new Segment(t("23:58"), 20, 24 * 3600 + 180, 60)),
            DiagramModel.of(timetable, AXIS, DAY, List.of(), 0).planned().getFirst().segments());
        assertEquals(24 * 3600 + 180, DiagramModel.clockSeconds((DAY + 1) * RealClock.TICKS_PER_DAY + 180 * RealClock.TICKS_PER_SECOND, DAY));
    }

    @Test
    void aRunningDutyIsDrawnAsFarAsItHasGot() {
        // Out of the depot a second late, in at A half a minute late, and away from A on time: on the way to M.
        DutyTimes running = times(DutyTimes.Status.RUNNING,
            new long[] {X, at("07:55:30"), X, X, X, X}, new long[] {at("07:50:01"), at("08:00:01"), X, X, X, X});
        DiagramModel model = DiagramModel.of(timetable("l"), AXIS, DAY, List.of(running), at("08:02"));
        assertEquals(1, model.ran().size());
        RanDuty ran = model.ran().getFirst();
        assertEquals(running, ran.times());
        assertEquals(List.of(
            new Segment(t("07:50:01"), 0, t("07:55:30"), 20),
            new Segment(t("07:55:30"), 20, t("08:00:01"), 20)), ran.segments());
    }

    @Test
    void aSetStandingAtAStationIsDrawnThereUntilNow() {
        DutyTimes standing = times(DutyTimes.Status.RUNNING,
            new long[] {X, at("07:55"), at("08:05:10"), X, X, X}, new long[] {at("07:50"), at("08:00"), X, X, X, X});
        RanDuty ran = DiagramModel.of(timetable("l"), AXIS, DAY, List.of(standing), at("08:05:40")).ran().getFirst();
        assertEquals(new Segment(t("08:05:10"), 60, t("08:05:40"), 60), ran.segments().getLast());
        // A duty given up there is not drawn on to now.
        DutyTimes givenUp = times(DutyTimes.Status.GIVEN_UP, standing.arrivals().stream().mapToLong(Long::longValue).toArray(),
            standing.departures().stream().mapToLong(Long::longValue).toArray());
        RanDuty stopped = DiagramModel.of(timetable("l"), AXIS, DAY, List.of(givenUp), at("08:05:40")).ran().getFirst();
        assertEquals(new Segment(t("08:00"), 20, t("08:05:10"), 60), stopped.segments().getLast());
    }

    @Test
    void onlyThePartOfADutyOnTheLineIsDrawn() {
        DutyTimes done = times(DutyTimes.Status.FINISHED,
            new long[] {X, at("07:55"), at("08:05"), at("08:10"), at("08:25"), at("08:35")},
            new long[] {at("07:50"), at("08:00"), at("08:06"), at("08:15"), at("08:30"), X});
        DiagramModel model = DiagramModel.of(timetable("up"), AXIS, DAY, List.of(done), at("09:00"));
        assertEquals(List.of(
            new Segment(t("07:50"), 0, t("07:55"), 20),
            new Segment(t("07:55"), 20, t("08:00"), 20),
            new Segment(t("08:00"), 20, t("08:05"), 60),
            new Segment(t("08:05"), 60, t("08:06"), 60),
            new Segment(t("08:06"), 60, t("08:10"), 120),
            // standing at B came in as train 1 on the line; the way back as train 2 is on the other line
            new Segment(t("08:10"), 120, t("08:15"), 120),
            new Segment(t("08:25"), 20, t("08:30"), 20),
            new Segment(t("08:30"), 20, t("08:35"), 0)), model.ran().getFirst().segments());
        assertEquals(3, model.planned().size(), "train 2 is drawn on the line it is not on");
    }

    @Test
    void dutiesOfAnotherDayOrNotInTheTimetableAreLeftOut() {
        DutyTimes done = times(DutyTimes.Status.FINISHED,
            new long[] {X, at("07:55"), X, X, X, X}, new long[] {at("07:50"), X, X, X, X, X});
        DutyTimes yesterday = new DutyTimes("1A", DAY - 1, "", DutyTimes.Status.FINISHED, done.arrivals(), done.departures());
        DutyTimes other = new DutyTimes("9Z", DAY, "", DutyTimes.Status.FINISHED, done.arrivals(), done.departures());
        DutyTimes tooShort = new DutyTimes("1A", DAY, "", DutyTimes.Status.FINISHED, List.of(X), List.of(X));
        assertEquals(1, DiagramModel.of(timetable("l"), AXIS, DAY, List.of(done, yesterday, other, tooShort), at("09:00")).ran().size());
    }

    @Test
    void onALoopLineTheWayBackToTheFirstStationGoesDownToItsSecondRow() {
        Line loop = new Line("loop", "Loop", List.of("Depot", "A", "B"), true);
        LineAxis axis = LineAxis.of(loop, List.of(
            new Leg("Depot", "A", 30, List.of(), List.of()), new Leg("A", "B", 30, List.of(), List.of()), new Leg("B", "Depot", 40, List.of(), List.of())));
        Train round = new Train("1", "loop", "", List.of(
            Stop.leaving("A", t("10:00")), Stop.calling("B", t("10:02"), t("10:03")), Stop.calling("Depot", t("10:05"), t("10:06")),
            Stop.arriving("A", t("10:08"))));
        Timetable timetable = new Timetable("", List.of(loop), List.of(round), List.of());
        assertEquals(List.of(
            new Segment(t("10:00"), 30, t("10:02"), 60),
            new Segment(t("10:02"), 60, t("10:03"), 60),
            new Segment(t("10:03"), 60, t("10:05"), 100),
            new Segment(t("10:05"), 100, t("10:06"), 100),
            new Segment(t("10:05"), 0, t("10:06"), 0),
            new Segment(t("10:06"), 0, t("10:08"), 30)), DiagramModel.of(timetable, axis, DAY, List.of(), 0).planned().getFirst().segments());
    }

    @Test
    void aWayAcrossTheFirstStationOfALoopIsDrawnToTheBottomAndOnFromTheTop() {
        Line loop = new Line("loop", "Loop", List.of("Depot", "A", "B", "C"), true);
        LineAxis axis = LineAxis.of(loop, List.of(new Leg("Depot", "A", 30, List.of(), List.of()), new Leg("A", "B", 30, List.of(), List.of()),
            new Leg("B", "C", 30, List.of(), List.of()), new Leg("C", "Depot", 30, List.of(), List.of())));
        Train past = new Train("X", "loop", "", List.of(Stop.leaving("C", t("10:00")), Stop.arriving("A", t("10:04"))), true);
        Timetable timetable = new Timetable("", List.of(loop), List.of(past), List.of());
        assertEquals(List.of(new Segment(t("10:00"), 90, t("10:02"), 120), new Segment(t("10:02"), 0, t("10:04"), 30)),
            DiagramModel.of(timetable, axis, DAY, List.of(), 0).planned().getFirst().segments());
    }

    @Test
    void aDeadheadThatPassesStationsGoesTheWayTheTrackGoes() {
        // Fewer stations are passed going up from the depot to B, but the track goes down through A.
        Line loop = new Line("loop", "Loop", List.of("Depot", "A", "B"), true);
        LineAxis axis = LineAxis.of(loop, List.of(new Leg("Depot", "A", 30, List.of(), List.of()), new Leg("A", "B", 30, List.of(), List.of()),
            new Leg("B", "Depot", 40, List.of(), List.of()), new Leg("Depot", "B", 60, List.of(), List.of("A"))));
        Train out = new Train("X", "loop", "", List.of(Stop.leaving("Depot", t("10:00")), Stop.arriving("B", t("10:02"))), true);
        Timetable timetable = new Timetable("", List.of(loop), List.of(out), List.of());
        assertEquals(List.of(new Segment(t("10:00"), 0, t("10:02"), 60)),
            DiagramModel.of(timetable, axis, DAY, List.of(), 0).planned().getFirst().segments());
    }
}
