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

    @Test
    void aTrainThatIsNotThereIsAnError() {
        Timetable timetable = OperationTimetables.shuttle();
        assertThrows(IllegalArgumentException.class, () -> DutyPlan.calls(timetable, new Duty("x", "A", List.of("9"))));
    }
}
