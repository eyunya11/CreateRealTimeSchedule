package io.github.eyunya11.createrealtimeschedule.timetable;

import java.util.List;

import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Duty;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Line;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Stop;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Train;

/** The timetable of {@code example.json}, written out in code. */
final class TimetableExample {

    private TimetableExample() {}

    static Timetable central() {
        Line line = new Line("central", "Central Line", List.of("Central Depot", "Central 1", "North Gate", "Harbor"), false);
        Train out = new Train("D101", "central", "", List.of(
            Stop.leaving("Central Depot", ServiceTime.of("08:02")),
            Stop.arriving("Central 1", ServiceTime.of("08:06"))), true);
        Train down = new Train("101", "central", "local", List.of(
            Stop.leaving("Central 1", ServiceTime.of("08:10")),
            Stop.calling("North Gate", ServiceTime.of("08:25"), ServiceTime.of("08:26")),
            Stop.arriving("Harbor", ServiceTime.of("08:41"))));
        Train up = new Train("102", "central", "local", List.of(
            Stop.leaving("Harbor", ServiceTime.of("08:50")),
            Stop.calling("North Gate", ServiceTime.of("09:05"), ServiceTime.of("09:06")),
            Stop.arriving("Central 1", ServiceTime.of("09:21"))));
        Train back = new Train("D102", "central", "", List.of(
            Stop.leaving("Central 1", ServiceTime.of("09:25")),
            Stop.arriving("Central Depot", ServiceTime.of("09:29"))), true);
        Duty duty = new Duty("1A", "Central Depot", List.of("D101", "101", "102", "D102"));
        return new Timetable("Central Line, weekdays", List.of(line), List.of(out, down, up, back), List.of(duty));
    }
}
