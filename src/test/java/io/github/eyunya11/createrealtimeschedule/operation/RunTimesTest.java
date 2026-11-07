package io.github.eyunya11.createrealtimeschedule.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.SignalBlocks;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.traffic.TestLines;
import io.github.eyunya11.createrealtimeschedule.travel.SpeedSign;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpec;

class RunTimesTest {

    /** Running times between stations, as a map by "from-to", or "from-to-tag" for a train held to a track tag. */
    private static RunTimes times(Map<String, Long> ticks) {
        return (from, to, tag) -> {
            Long found = ticks.get(from + "-" + to + (tag.isEmpty() ? "" : "-" + tag));
            return found == null ? OptionalLong.empty() : OptionalLong.of(found);
        };
    }

    @Test
    void theFastestIsTheLeastThatAnyKnows() {
        RunTimes powered = times(Map.of("A-B", 100L, "B-C", 300L));
        RunTimes unpowered = times(Map.of("A-B", 140L, "B-C", 250L, "C-D", 80L));
        RunTimes fastest = RunTimes.fastest(List.of(powered, unpowered));
        assertEquals(OptionalLong.of(100), fastest.ticks("A", "B"));
        assertEquals(OptionalLong.of(250), fastest.ticks("B", "C"), "whichever is the faster there");
        assertEquals(OptionalLong.of(80), fastest.ticks("C", "D"), "what only one knows");
        assertEquals(OptionalLong.empty(), fastest.ticks("D", "A"), "what none knows");
        assertEquals(OptionalLong.empty(), RunTimes.fastest(List.of()).ticks("A", "B"));
    }

    /** A train held to a track tag has the times of its tag, which may be none where a train of no tag has one. */
    @Test
    void theTimesOfATagAreTheirOwn() {
        RunTimes times = times(Map.of("A-B", 100L, "A-B-local", 120L));
        assertEquals(OptionalLong.of(100), times.ticks("A", "B"));
        assertEquals(OptionalLong.of(120), times.ticks("A", "B", "local"));
        assertEquals(OptionalLong.empty(), times.ticks("A", "B", "express"));
        RunTimes remembered = RunTimes.remembered(times);
        assertEquals(OptionalLong.of(100), remembered.ticks("A", "B"));
        assertEquals(OptionalLong.of(120), remembered.ticks("A", "B", "local"), "remembered by the tag too");
        RunTimes fastest = RunTimes.fastest(List.of(times, times(Map.of("A-B-local", 110L))));
        assertEquals(OptionalLong.of(110), fastest.ticks("A", "B", "local"));
        assertEquals(OptionalLong.of(100), fastest.ticks("A", "B"));
    }

    @Test
    void rememberedTimesAreWorkedOutOnceForEachTwoStations() {
        int[] asked = {0};
        RunTimes remembered = RunTimes.remembered((from, to, tag) -> {
            asked[0]++;
            return from.equals("A") ? OptionalLong.of(100) : OptionalLong.empty();
        });
        for (int k = 0; k < 3; k++) {
            assertEquals(OptionalLong.of(100), remembered.ticks("A", "B"));
            assertEquals(OptionalLong.empty(), remembered.ticks("B", "A"), "what is not known is remembered too");
        }
        assertEquals(2, asked[0]);
    }

    /**
     * A 40 km/h sign 50 blocks into the 100 from A to B, and nothing more: a train that stopped at B is
     * still under it on the 1000 blocks to C, and slower there than one that comes to B under none.
     */
    private static final Map<List<String>, Leg> SIGNED = Map.of(
        List.of("A", "B", ""), new Leg("A", "B", 100, List.of(), List.of(), List.of(SpeedSign.limit(50, 40))),
        List.of("B", "C", ""), new Leg("B", "C", 1000, List.of(), List.of()));

    @Test
    void theTimesOfLegsCarryTheLimitOfTheLastSign() {
        RunTimes times = RunTimes.ofLegs(SIGNED, TrainSpec.STANDARD, 1);
        assertEquals(OptionalInt.of(40), times.limitAtEnd("A", "B", "", OptionalInt.empty()));
        assertEquals(OptionalInt.of(40), times.limitAtEnd("B", "C", "", OptionalInt.of(40)), "no sign: the limit goes on");
        assertEquals(OptionalInt.empty(), times.limitAtEnd("B", "C", "", OptionalInt.empty()));
        assertEquals(OptionalInt.of(60), times.limitAtEnd("C", "A", "", OptionalInt.of(60)), "a way it does not know keeps the limit");

        long free = times.ticks("B", "C").orElseThrow();
        long limited = times.ticks("B", "C", "", OptionalInt.of(40)).orElseThrow();
        assertEquals(SIGNED.get(List.of("B", "C", "")).ticks(TrainSpec.STANDARD, 1, OptionalInt.of(40)).orElseThrow(), limited);
        // 1000 blocks at 40 km/h (11.1 blocks a second) is 90 seconds; at the top speed of 28 a second, about 36.
        assertTrue(limited > free + 50 * 20, free + " and " + limited);
    }

    /** Running times with the signals say what the trains of the timetable are held up by, and pass the rest on. */
    @Test
    void timesWithTheSignalsSayWhatTheTrafficSays() {
        RunTimes track = RunTimes.ofLegs(SIGNED, TrainSpec.STANDARD, 1);
        TimetableTraffic traffic = TimetableTraffic.of(new Timetable("t", List.of(),
                List.of(new Timetable.Train("1", "L", "", List.of(Timetable.Stop.leaving("A", 0), Timetable.Stop.arriving("B", 60))),
                    new Timetable.Train("2", "L", "", List.of(Timetable.Stop.leaving("A", 10), Timetable.Stop.arriving("B", 70)))),
                List.of()),
            legsOf(TestLines.line(5, SignalBlocks.LightSpeeds.DEFAULT, 10, 900)), TestLines.line(5, SignalBlocks.LightSpeeds.DEFAULT, 10, 900).blocks(),
            TrainSpec.STANDARD, 0, 10);
        RunTimes times = RunTimes.withSignals(track, traffic);
        assertEquals(traffic.signalTicks("2", 0), times.signalTicks("2", 0));
        assertTrue(times.signalTicks("2", 0) > 0);
        assertEquals(track.ticks("A", "B", "", OptionalInt.empty()), times.ticks("A", "B", "", OptionalInt.empty()));
        assertEquals(track.limitAtEnd("A", "B", "", OptionalInt.empty()), times.limitAtEnd("A", "B", "", OptionalInt.empty()));
        assertEquals(times.signalTicks("2", 0), RunTimes.remembered(times).signalTicks("2", 0));
        assertEquals(times.signalTicks("2", 0), RunTimes.fastest(List.of(times, track)).signalTicks("2", 0));
        assertEquals(0, track.signalTicks("2", 0), "running times that know nothing of the signals");

        // A simulation still being worked out is asked for again each time, so that times kept for a train take it once it is done.
        TimetableTraffic[] now = {TimetableTraffic.NONE};
        RunTimes later = RunTimes.remembered(RunTimes.withSignals(track, () -> now[0]));
        assertEquals(0, later.signalTicks("2", 0), "nothing while it is being worked out");
        now[0] = traffic;
        assertEquals(traffic.signalTicks("2", 0), later.signalTicks("2", 0), "the run once it is done");
    }

    private static Map<List<String>, Leg> legsOf(RailNetwork network) {
        Map<List<String>, Leg> legs = new HashMap<>();
        for (Leg leg : network.legs())
            legs.put(List.of(leg.from(), leg.to(), ""), leg);
        return legs;
    }

    @Test
    void rememberedAndFastestTimesPassTheLimitOn() {
        RunTimes times = RunTimes.ofLegs(SIGNED, TrainSpec.STANDARD, 1);
        long limited = times.ticks("B", "C", "", OptionalInt.of(40)).orElseThrow();
        for (RunTimes wrapped : List.of(RunTimes.remembered(times), RunTimes.fastest(List.of(times)))) {
            assertEquals(OptionalLong.of(limited), wrapped.ticks("B", "C", "", OptionalInt.of(40)));
            assertEquals(times.ticks("B", "C"), wrapped.ticks("B", "C"), "remembered apart from the limited time");
            assertEquals(OptionalInt.of(40), wrapped.limitAtEnd("A", "B", "", OptionalInt.empty()));
        }
        assertEquals(OptionalInt.of(25), RunTimes.fastest(List.of()).limitAtEnd("A", "B", "", OptionalInt.of(25)));
    }

    @Test
    void rememberedLimitsAreWorkedOutOnceForEachWayAndLimit() {
        int[] asked = {0};
        RunTimes remembered = RunTimes.remembered(new RunTimes() {
            @Override
            public OptionalLong ticks(String from, String to, String tag) {
                return OptionalLong.empty();
            }

            @Override
            public OptionalInt limitAtEnd(String from, String to, String tag, OptionalInt limitAtStart) {
                asked[0]++;
                return from.equals("A") ? OptionalInt.of(40) : limitAtStart;
            }
        });
        for (int k = 0; k < 3; k++) {
            assertEquals(OptionalInt.of(40), remembered.limitAtEnd("A", "B", "", OptionalInt.empty()));
            assertEquals(OptionalInt.of(25), remembered.limitAtEnd("B", "C", "", OptionalInt.of(25)));
            assertEquals(OptionalInt.empty(), remembered.limitAtEnd("B", "C", "", OptionalInt.empty()), "by the limit too");
        }
        assertEquals(3, asked[0]);
    }
}
