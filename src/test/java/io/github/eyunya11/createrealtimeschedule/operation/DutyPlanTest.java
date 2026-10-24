package io.github.eyunya11.createrealtimeschedule.operation;

import static io.github.eyunya11.createrealtimeschedule.operation.OperationTimetables.t;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Duty;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Stop;

class DutyPlanTest {

    @Test
    void aDutyCallsAtEveryStopOfItsTrainsFromTheDepotAndBack() {
        Timetable timetable = OperationTimetables.shuttle();
        assertEquals(List.of(
            new Call("Depot", Stop.NONE, t("07:50"), "", "D1"),
            new Call("A", t("07:55"), t("08:00"), "D1", "1"),
            new Call("M", t("08:05"), t("08:06"), "1", "1"),
            new Call("B", t("08:10"), t("08:15"), "1", "2"),
            new Call("A", t("08:25"), t("08:30"), "2", "D2"),
            new Call("Depot", t("08:35"), Stop.NONE, "D2", "")),
            DutyPlan.calls(timetable, timetable.duties().getFirst()));
    }

    @Test
    void theFirstDepartureIsTheDepots() {
        Timetable timetable = OperationTimetables.shuttle();
        assertEquals(t("07:50"), DutyPlan.firstDeparture(DutyPlan.calls(timetable, timetable.duties().getFirst())));
    }

    @Test
    void aDutyOfOneTrainCallsAtItsStops() {
        Timetable timetable = OperationTimetables.shuttle();
        Duty one = new Duty("x", "B", List.of("2"));
        assertEquals(List.of(new Call("B", Stop.NONE, t("08:15"), "", "2"), new Call("A", t("08:25"), Stop.NONE, "2", "")),
            DutyPlan.calls(timetable, one));
    }

    /**
     * A call's minimum stop is the stop's own, or else the station's; where one train hands over to the
     * next, it is the stop the set leaves from, and at the end, where the set does not leave, there is none.
     */
    @Test
    void aCallHasTheMinimumStopOfTheStopItLeavesFromOrElseOfItsStation() {
        Timetable shuttle = OperationTimetables.shuttle();
        List<Timetable.Train> trains = shuttle.trains().stream().map(train -> switch (train.number()) {
            case "1" -> new Timetable.Train("1", "l", "local", List.of(
                new Stop("A", Stop.NONE, t("08:00"), 45), new Stop("M", t("08:05"), t("08:06"), 5), Stop.arriving("B", t("08:10"))));
            default -> train;
        }).toList();
        Timetable timetable = new Timetable("", shuttle.lines(),
            List.of(new Timetable.Station("A", 30, 15), new Timetable.Station("B", Stop.NONE, 10), new Timetable.Station("M", 40, Stop.NONE)),
            trains, shuttle.duties());
        assertEquals(List.of(), timetable.problems());
        assertEquals(List.of(Stop.NONE, 45, 5, 10, 15, Stop.NONE),
            DutyPlan.calls(timetable, timetable.duties().getFirst()).stream().map(Call::minimumStop).toList());
    }

    @Test
    void aTrainThatIsNotThereIsAnError() {
        Timetable timetable = OperationTimetables.shuttle();
        assertThrows(IllegalArgumentException.class, () -> DutyPlan.calls(timetable, new Duty("x", "A", List.of("9"))));
    }
}
