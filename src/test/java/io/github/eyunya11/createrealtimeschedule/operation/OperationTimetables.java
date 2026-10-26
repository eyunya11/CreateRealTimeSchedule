package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.ArrayList;
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

    /**
     * The duty "1A": out of the depot to A, three times to B and back, 13 minutes each way and 2 at
     * each end, and into the depot again. Its calls are Depot 07:50, A 08:00, B 08:15, A 08:30, B 08:45,
     * A 09:00, B 09:15, A 09:30 and Depot 09:35, by the departures.
     *
     * @param minimumStop the minimum stop of A and B, in seconds, or {@link Stop#NONE} for the server's
     * @param keep        the trains that are never cancelled
     * @param moreTrains  more trains on the line
     * @param moreDuties  the duties that run them
     */
    static Timetable laps(int minimumStop, List<String> keep, List<Train> moreTrains, List<Duty> moreDuties) {
        Line line = new Line("l", "Line", List.of("Depot", "A", "B"), false);
        List<Train> trains = new ArrayList<>();
        trains.add(new Train("D1", "l", "", List.of(Stop.leaving("Depot", t("07:50")), Stop.arriving("A", t("07:55"))), true));
        String[][] laps = {
            {"1", "A", "08:00", "B", "08:13"}, {"2", "B", "08:15", "A", "08:28"},
            {"3", "A", "08:30", "B", "08:43"}, {"4", "B", "08:45", "A", "08:58"},
            {"5", "A", "09:00", "B", "09:13"}, {"6", "B", "09:15", "A", "09:28"}};
        for (String[] lap : laps)
            trains.add(new Train(lap[0], "l", "local", List.of(Stop.leaving(lap[1], t(lap[2])), Stop.arriving(lap[3], t(lap[4]))), false,
                keep.contains(lap[0])));
        trains.add(new Train("D2", "l", "", List.of(Stop.leaving("A", t("09:30")), Stop.arriving("Depot", t("09:35"))), true));
        trains.addAll(moreTrains);
        List<Duty> duties = new ArrayList<>();
        duties.add(new Duty("1A", "Depot", List.of("D1", "1", "2", "3", "4", "5", "6", "D2")));
        duties.addAll(moreDuties);
        List<Timetable.Station> stations = minimumStop == Stop.NONE ? List.of()
            : List.of(new Timetable.Station("A", Stop.NONE, minimumStop), new Timetable.Station("B", Stop.NONE, minimumStop));
        Timetable timetable = new Timetable("", List.of(line), stations, trains, duties);
        if (!timetable.problems().isEmpty())
            throw new IllegalStateException(timetable.problems().toString());
        return timetable;
    }

    /** {@link #laps(int, List, List, List)} with the server's minimum stop, no train kept, and nothing else. */
    static Timetable laps() {
        return laps(Stop.NONE, List.of(), List.of(), List.of());
    }
}
