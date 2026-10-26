package io.github.eyunya11.createrealtimeschedule.operation;

import static io.github.eyunya11.createrealtimeschedule.operation.OperationTimetables.t;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Stop;

class ForecastTest {

    private static final long DAY = 3;
    private static final long SECOND = RealClock.TICKS_PER_SECOND;
    private static final long MINIMUM = 20 * SECOND;

    /** 12 minutes between A and B, which the timetable gives 13. */
    static final RunTimes TRACK = (from, to) -> Set.of(from, to).equals(Set.of("A", "B")) ? OptionalLong.of(720 * SECOND)
        : Set.of(from, to).equals(Set.of("Depot", "A")) ? OptionalLong.of(240 * SECOND) : OptionalLong.empty();

    private static final Timetable LAPS = OperationTimetables.laps();
    private static final List<Call> CALLS = DutyPlan.calls(LAPS, LAPS.duties().getFirst());

    private static long at(String time) {
        return DutyPlan.at(DAY, t(time));
    }

    @Test
    void aLateSetRunsAsFastAsTheTrackLetsItAndStandsForItsMinimumStop() {
        // A 08:25:20, B 08:37:20 to 08:37:40, A 08:49:40, and it leaves A 20 seconds later.
        assertEquals(at("08:50:00"), Forecast.leaves(CALLS, DAY, 1, at("08:25:20"), 3, TRACK, MINIMUM));
    }

    @Test
    void aSetThatGetsBackOnTimeWaitsForTheTimetable() {
        assertEquals(at("08:30"), Forecast.leaves(CALLS, DAY, 1, at("08:00"), 3, TRACK, MINIMUM));
        assertEquals(at("08:30"), Forecast.leaves(CALLS, DAY, 1, at("08:05"), 3, TRACK, MINIMUM), "5 minutes made up by the slack");
    }

    @Test
    void whereTheTrackIsNotKnownTheSetTakesTheTimetablesTime() {
        // 13 minutes to B, 08:38:20 to 08:38:40, 13 minutes back, 08:51:40, and 20 seconds there.
        assertEquals(at("08:52:00"), Forecast.leaves(CALLS, DAY, 1, at("08:25:20"), 3, RunTimes.NONE, MINIMUM));
        assertEquals(780 * SECOND, Forecast.runTicks(CALLS, 1, RunTimes.NONE));
        assertEquals(720 * SECOND, Forecast.runTicks(CALLS, 1, TRACK));
    }

    @Test
    void aCallWithOnlyADepartureIsComeInToThen() {
        List<Call> calls = List.of(new Call("A", Stop.NONE, t("08:00"), "", "1"), new Call("M", Stop.NONE, t("08:06"), "1", "1"),
            new Call("B", t("08:10"), Stop.NONE, "1", ""));
        assertEquals(360 * SECOND, Forecast.plannedRunTicks(calls, 0));
        assertEquals(240 * SECOND, Forecast.plannedRunTicks(calls, 1));
    }
}
