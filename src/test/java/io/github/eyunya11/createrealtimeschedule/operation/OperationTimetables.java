package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.List;

import io.github.eyunya11.createrealtimeschedule.timetable.ServiceTime;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Duty;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Line;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Stop;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Train;

/** Timetables for the tests of the operation. */
final class OperationTimetables {

    private OperationTimetables() {}

    static int t(String time) {
        return ServiceTime.of(time);
    }

    /**
     * Out of the depot to A, to B calling at M, back to A, and into the depot again: the duty "1A",
     * as a diagram draws it.
     */
    static Timetable shuttle() {
        Line line = new Line("l", "Line", List.of("Depot", "A", "M", "B"), false);
        Train out = new Train("D1", "l", "", List.of(Stop.leaving("Depot", t("07:50")), Stop.arriving("A", t("07:55"))), true);
        Train down = new Train("1", "l", "local", List.of(
            Stop.leaving("A", t("08:00")), Stop.calling("M", t("08:05"), t("08:06")), Stop.arriving("B", t("08:10"))));
        Train up = new Train("2", "l", "local", List.of(Stop.leaving("B", t("08:15")), Stop.arriving("A", t("08:25"))));
        Train in = new Train("D2", "l", "", List.of(Stop.leaving("A", t("08:30")), Stop.arriving("Depot", t("08:35"))), true);
        Timetable timetable = new Timetable("", List.of(line), List.of(out, down, up, in),
            List.of(new Duty("1A", "Depot", List.of("D1", "1", "2", "D2"))));
        if (!timetable.problems().isEmpty())
            throw new IllegalStateException(timetable.problems().toString());
        return timetable;
    }
}
