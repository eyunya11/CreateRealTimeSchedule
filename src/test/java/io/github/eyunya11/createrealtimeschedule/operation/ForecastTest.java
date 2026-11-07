package io.github.eyunya11.createrealtimeschedule.operation;

import static io.github.eyunya11.createrealtimeschedule.operation.OperationTimetables.t;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.OptionalInt;
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
    static final RunTimes TRACK = (from, to, tag) -> Set.of(from, to).equals(Set.of("A", "B")) ? OptionalLong.of(720 * SECOND)
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

    /** The time of a way is that of the track tag the set leaves its call on. */
    @Test
    void aSetRunsInTheTimeOfTheTagItLeavesOn() {
        RunTimes byTag = (from, to, tag) -> OptionalLong.of((tag.equals("local") ? 600 : 500) * SECOND);
        List<Call> calls = List.of(new Call("A", Stop.NONE, t("08:00"), "", "1", Stop.NONE, List.of(), "local"),
            new Call("B", t("08:13"), Stop.NONE, "1", ""));
        assertEquals(600 * SECOND, Forecast.runTicks(calls, 0, byTag));
        assertEquals(500 * SECOND, Forecast.runTicks(List.of(new Call("A", Stop.NONE, t("08:00"), "", "1"), calls.get(1)), 0, byTag));
    }

    /**
     * A 40 km/h sign before B, and none after it: from B on, train 1 is still under it. Train 2 leaves C
     * as a train of its own, which is taken to start under no limit.
     */
    private static final RunTimes SIGNED = new RunTimes() {
        @Override
        public OptionalLong ticks(String from, String to, String tag) {
            return ticks(from, to, tag, OptionalInt.empty());
        }

        @Override
        public OptionalLong ticks(String from, String to, String tag, OptionalInt limitAtStart) {
            long seconds = switch (from) {
                case "A" -> 100;
                case "B" -> limitAtStart.isPresent() ? 300 : 200;
                default -> limitAtStart.isPresent() ? 500 : 400;
            };
            return OptionalLong.of(seconds * SECOND);
        }

        @Override
        public OptionalInt limitAtEnd(String from, String to, String tag, OptionalInt limitAtStart) {
            return from.equals("A") ? OptionalInt.of(40) : limitAtStart;
        }
    };

    private static final List<Call> SIGNED_CALLS = List.of(new Call("A", Stop.NONE, t("08:00"), "", "1"),
        new Call("B", t("08:05"), t("08:06"), "1", "1"), new Call("C", t("08:15"), t("08:20"), "1", "2"),
        new Call("D", t("08:30"), Stop.NONE, "2", ""));

    @Test
    void aSetStaysUnderTheLimitOfASignItPassedBeforeAStop() {
        assertEquals(OptionalInt.empty(), Forecast.limitAt(SIGNED_CALLS, 0, SIGNED));
        assertEquals(OptionalInt.of(40), Forecast.limitAt(SIGNED_CALLS, 1, SIGNED));
        assertEquals(300 * SECOND, Forecast.runTicks(SIGNED_CALLS, 1, SIGNED));
        // Leaving B ten minutes late, at C at 08:21 (not 08:19:20 as under no limit), and 20 seconds there.
        assertEquals(at("08:21:20"), Forecast.leaves(SIGNED_CALLS, DAY, 1, at("08:16"), 2, SIGNED, MINIMUM));
    }

    @Test
    void eachTrainOfADutyStartsUnderNoLimit() {
        assertEquals(OptionalInt.empty(), Forecast.limitAt(SIGNED_CALLS, 2, SIGNED));
        assertEquals(400 * SECOND, Forecast.runTicks(SIGNED_CALLS, 2, SIGNED));
    }

    /**
     * SIGNED, with the signals adding 7 seconds to train 1's way from B, its stop 1, and 3 seconds to
     * train 2's from C, its stop 0, running on time with the others.
     */
    private static final RunTimes SIGNALLED = new RunTimes() {
        @Override
        public OptionalLong ticks(String from, String to, String tag) {
            return SIGNED.ticks(from, to, tag);
        }

        @Override
        public OptionalLong ticks(String from, String to, String tag, OptionalInt limitAtStart) {
            return SIGNED.ticks(from, to, tag, limitAtStart);
        }

        @Override
        public OptionalInt limitAtEnd(String from, String to, String tag, OptionalInt limitAtStart) {
            return SIGNED.limitAtEnd(from, to, tag, limitAtStart);
        }

        @Override
        public long signalTicks(String train, int stop) {
            return train.equals("1") && stop == 1 ? 7 * SECOND : train.equals("2") && stop == 0 ? 3 * SECOND : 0;
        }
    };

    /** What the signals add to a way is that of the train the set leaves as, counted by the train's own stops. */
    @Test
    void theSignalsAddToTheWayOfTheTrainASetLeavesAs() {
        assertEquals(100 * SECOND, Forecast.runTicks(SIGNED_CALLS, 0, SIGNALLED));
        assertEquals(307 * SECOND, Forecast.runTicks(SIGNED_CALLS, 1, SIGNALLED));
        assertEquals(403 * SECOND, Forecast.runTicks(SIGNED_CALLS, 2, SIGNALLED), "train 2's first way, though the duty's third");
        // Leaving B ten minutes late, at C at 08:21:07, and 20 seconds there.
        assertEquals(at("08:21:27"), Forecast.leaves(SIGNED_CALLS, DAY, 1, at("08:16"), 2, SIGNALLED, MINIMUM));
    }

    /** Where the track says nothing of a way, the timetable's time is taken, with nothing added. */
    @Test
    void theSignalsAddNothingToTheTimetablesTime() {
        RunTimes unknown = new RunTimes() {
            @Override
            public OptionalLong ticks(String from, String to, String tag) {
                return OptionalLong.empty();
            }

            @Override
            public long signalTicks(String train, int stop) {
                return 60 * SECOND;
            }
        };
        assertEquals(780 * SECOND, Forecast.runTicks(CALLS, 1, unknown));
    }

    @Test
    void aForecastOverTwoTrainsCarriesTheLimitOnlyWithinEach() {
        // Train 1 runs A, B, C under the sign from B on; train 2 leaves C under none, on to D and E.
        List<Call> calls = List.of(new Call("A", Stop.NONE, t("08:00"), "", "1"), new Call("B", t("08:05"), t("08:06"), "1", "1"),
            new Call("C", t("08:15"), t("08:20"), "1", "2"), new Call("D", t("08:25"), t("08:25"), "2", "2"),
            new Call("E", t("08:35"), Stop.NONE, "2", ""));
        // Leaving B at 08:16, at C at 08:21 and off at 08:21:20; 400 seconds to D (500 under the limit), 08:28 and 20 seconds there.
        assertEquals(at("08:28:20"), Forecast.leaves(calls, DAY, 1, at("08:16"), 3, SIGNED, MINIMUM));
        assertEquals(OptionalInt.of(40), Forecast.limitAfter(calls, 0, OptionalInt.empty(), SIGNED));
        assertEquals(OptionalInt.empty(), Forecast.limitAfter(calls, 1, OptionalInt.of(40), SIGNED), "train 2 leaves C under none");
    }

    @Test
    void aForecastAsksForTheLimitOnceForEachWay() {
        List<Call> calls = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++)
            calls.add(new Call("S" + i, i == 0 ? Stop.NONE : t("08:00") + 60 * i - 10, i == 9 ? Stop.NONE : t("08:00") + 60 * i,
                i == 0 ? "" : "1", i == 9 ? "" : "1"));
        int[] asked = {0};
        RunTimes counting = new RunTimes() {
            @Override
            public OptionalLong ticks(String from, String to, String tag) {
                return OptionalLong.of(30 * SECOND);
            }

            @Override
            public OptionalInt limitAtEnd(String from, String to, String tag, OptionalInt limitAtStart) {
                asked[0]++;
                return OptionalInt.of(40);
            }
        };
        Forecast.leaves(calls, DAY, 0, at("08:00"), 8, counting, MINIMUM);
        // Each way from S1 to S8 starts under the limit the way before ends under; worked out again from S0 for each, it is 28.
        assertEquals(7, asked[0]);
    }

    @Test
    void runningTimesThatKnowOfNoSignsCarryNoLimit() {
        assertEquals(OptionalInt.empty(), Forecast.limitAt(CALLS, 2, TRACK));
        assertEquals(720 * SECOND, Forecast.runTicks(CALLS, 2, TRACK));
    }

    @Test
    void aCallWithOnlyADepartureIsComeInToThen() {
        List<Call> calls = List.of(new Call("A", Stop.NONE, t("08:00"), "", "1"), new Call("M", Stop.NONE, t("08:06"), "1", "1"),
            new Call("B", t("08:10"), Stop.NONE, "1", ""));
        assertEquals(360 * SECOND, Forecast.plannedRunTicks(calls, 0));
        assertEquals(240 * SECOND, Forecast.plannedRunTicks(calls, 1));
    }
}
