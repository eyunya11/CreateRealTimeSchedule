package io.github.eyunya11.createrealtimeschedule.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.SignalBlocks;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Duty;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Stop;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Train;
import io.github.eyunya11.createrealtimeschedule.traffic.TestLines;
import io.github.eyunya11.createrealtimeschedule.traffic.Traffic.Trip;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpec;

class TimetableTrafficTest {

    private static final TrainSpec SPEC = TrainSpec.STANDARD;
    private static final long MINIMUM_STOP = 100;
    private static final int LENGTH = 10;

    private static Map<List<String>, Leg> legs(RailNetwork network) {
        Map<List<String>, Leg> legs = new HashMap<>();
        for (Leg leg : network.legs())
            legs.put(List.of(leg.from(), leg.to(), ""), leg);
        return legs;
    }

    private static Train train(String number, Stop... stops) {
        return new Train(number, "L", "", List.of(stops));
    }

    private static TimetableTraffic run(RailNetwork network, List<Train> trains, List<Duty> duties) {
        return TimetableTraffic.of(new Timetable("t", List.of(), trains, duties), legs(network), network.blocks(), SPEC, MINIMUM_STOP, LENGTH);
    }

    /** Stations A at 10 and B at 900 of a line of five-aspect signals, and C nowhere on it. */
    private static RailNetwork line() {
        return TestLines.line(5, SignalBlocks.LightSpeeds.DEFAULT, 10, 900);
    }

    @Test
    void theSecondOfTwoTrainsCloseTogetherIsSlowed() {
        TimetableTraffic traffic = run(line(),
            List.of(train("1", Stop.leaving("A", 0), Stop.arriving("B", 60)), train("2", Stop.leaving("A", 10), Stop.arriving("B", 70))),
            List.of());
        Trip first = traffic.trip("1", 0).orElseThrow();
        Trip second = traffic.trip("2", 0).orElseThrow();
        assertEquals(first.free(), first.ticks());
        assertEquals(200, second.departed(), "the timetable's time, in ticks");
        assertTrue(second.ticks() > second.free());
        assertEquals(second.ticks(), traffic.ticks("2", 0).getAsLong());
    }

    @Test
    void aTrainOffTheTrackRunsNowhere() {
        TimetableTraffic traffic = run(line(), List.of(train("1", Stop.leaving("A", 0), Stop.arriving("C", 60))), List.of());
        assertTrue(traffic.trip("1", 0).isEmpty());
        assertTrue(traffic.ticks("1", 0).isEmpty());
    }

    /**
     * A duty is one set: the second train leaves where the first came in, at its time or after the
     * minimum stop if the first is late.
     */
    @Test
    void theTrainsOfADutyAreOneSet() {
        RailNetwork network = TestLines.line(5, SignalBlocks.LightSpeeds.DEFAULT, 10, 450, 900);
        Train first = train("1", Stop.leaving("A", 0), Stop.calling("B", 5, 6), Stop.arriving("C", 60));
        Train second = train("2", Stop.leaving("C", 61), Stop.arriving("A", 120));
        TimetableTraffic traffic = run(network, List.of(first, second, train("3", Stop.leaving("A", 300), Stop.arriving("C", 360))),
            List.of(new Duty("d", "A", List.of("1", "2"))));
        Trip toB = traffic.trip("1", 0).orElseThrow();
        Trip toC = traffic.trip("1", 1).orElseThrow();
        assertEquals(toB.arrived() + MINIMUM_STOP, toC.departed(), "late at B, it stands its minimum stop");
        assertTrue(traffic.trip("2", 0).isEmpty(), "there is no way back from C on a line one way");
        assertTrue(traffic.trip("3", 0).isPresent(), "a train in no duty is a set of its own");
    }

    /**
     * A train as long as the timetable says reaches back past the signal behind the station it stands
     * at, so that a train for beyond it stops at that signal and not at the next. (Its body is followed
     * back along the way it came; the first station it leaves has only the block it stands in behind it.)
     */
    @Test
    void theLengthOfATrainIsTheTimetables() {
        RailNetwork network = TestLines.line(0, null, 10, 450, 650, 700, 950);
        for (int length : new int[] {Stop.NONE, 100}) {
            Train standing = new Train("1", "L", "", List.of(Stop.leaving("B", 0), Stop.calling("C", 20, 300), Stop.arriving("D", 360)),
                false, false, "", length);
            Train behind = train("2", Stop.leaving("A", 10), Stop.arriving("E", 120));
            Trip trip = run(network, List.of(standing, behind), List.of()).trip("2", 0).orElseThrow();
            assertEquals(1, trip.waits().size(), trip.waits().toString());
            assertEquals(length == Stop.NONE ? 4 : 3, trip.waits().getFirst().signal(), "length " + length);
        }
        assertFalse(TimetableTraffic.of(new Timetable("t", List.of(), List.of(), List.of()), Map.of(), SignalBlocks.NONE, SPEC, 0, LENGTH)
            .trip("1", 0).isPresent());
    }
}
