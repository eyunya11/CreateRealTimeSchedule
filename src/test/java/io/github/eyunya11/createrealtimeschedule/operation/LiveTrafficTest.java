package io.github.eyunya11.createrealtimeschedule.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.operation.LiveTraffic.Coming;
import io.github.eyunya11.createrealtimeschedule.operation.LiveTraffic.Key;
import io.github.eyunya11.createrealtimeschedule.operation.LiveTraffic.Running;
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

class LiveTrafficTest {

    private static final TrainSpec SPEC = TrainSpec.STANDARD;
    private static final long MINIMUM_STOP = 100;
    private static final int LENGTH = 10;
    private static final long DAY = 3;

    /** Stations A at 10 and B at 900 of a line of five-aspect signals. */
    private static final RailNetwork NETWORK = TestLines.line(5, SignalBlocks.LightSpeeds.DEFAULT, 10, 900);

    /** Duty 1 leaves A at 0:00:00 and duty 2 at 0:01:45, each for B a minute later; duty 3 leaves an hour after. */
    private static final Timetable TIMETABLE = new Timetable("t", List.of(),
        List.of(train("1", 0), train("2", 105), train("3", 105 + 3600)),
        List.of(new Duty("1", "A", List.of("1")), new Duty("2", "A", List.of("2")), new Duty("3", "A", List.of("3"))));

    /**
     * A train from A to B, and on to C, which is not on the track: its set is taken off at B, and does
     * not stand there in the way of the one behind.
     */
    private static Train train(String number, int leaves) {
        return new Train(number, "L", "",
            List.of(Stop.leaving("A", leaves), Stop.calling("B", leaves + 60, leaves + 70), Stop.arriving("C", leaves + 120)));
    }

    private static Map<List<String>, Leg> legs() {
        Map<List<String>, Leg> legs = new HashMap<>();
        for (Leg leg : NETWORK.legs())
            legs.put(List.of(leg.from(), leg.to(), ""), leg);
        return legs;
    }

    private static List<Call> calls(String duty) {
        return DutyPlan.calls(TIMETABLE, TIMETABLE.duties().stream().filter(d -> d.id().equals(duty)).findFirst().orElseThrow());
    }

    private static long at(int seconds) {
        return DutyPlan.at(DAY, seconds);
    }

    /** The set of {@code duty} standing at its depot at {@code now}, to leave at its time or now. */
    private static DutyRun standing(String duty, long now) {
        return DutyRun.start(duty, DAY, calls(duty), now);
    }

    private static Running running(DutyRun run, double throttle, long now) {
        return Running.of(run, now, SPEC, throttle);
    }

    private static LiveTraffic live(List<Running> running, List<Coming> coming, long now) {
        return LiveTraffic.of(TIMETABLE, running, coming, legs(), NETWORK.blocks(), SPEC, MINIMUM_STOP, LENGTH, now, () -> false);
    }

    /**
     * Duty 1, 100 seconds late, leaves A five seconds before duty 2, which runs on time behind it and is
     * held up by the signals; on time, the two are far enough apart for neither to be.
     */
    @Test
    void aSetOnTimeIsHeldUpBehindALateOne() {
        long now = at(100);
        LiveTraffic live = live(List.of(running(standing("1", now), 1, now), running(standing("2", now), 1, now)), List.of(), now);
        assertTrue(live.finished());
        assertEquals(now, live.from());
        Trip late = live.trip(new Key("1", DAY, 0)).orElseThrow();
        assertEquals(now, late.departed(), "it leaves now, late as it is");
        assertEquals(0, live.signalTicks(new Key("1", DAY, 0)).getAsLong());
        assertEquals(at(105), live.trip(new Key("2", DAY, 0)).orElseThrow().departed());
        assertTrue(live.signalTicks(new Key("2", DAY, 0)).getAsLong() > 0, "it runs up behind the late one");
        TimetableTraffic onTime = TimetableTraffic.of(TIMETABLE, legs(), NETWORK.blocks(), SPEC, MINIMUM_STOP, LENGTH);
        assertEquals(0, onTime.signalTicks("2", 0), "on time it is not held up");
    }

    /**
     * A set on its way is put on the line from when it left the call before, at its own throttle: duty
     * 1, which left A late, holds up duty 2 as it would standing there to leave then.
     */
    @Test
    void aSetOnItsWayIsPutOnTheLineFromWhenItLeft() {
        DutyRun first = standing("1", at(100));
        first.sent();
        first.left(at(100));
        long now = at(102);
        LiveTraffic live = live(List.of(running(first, 0.5, now), running(standing("2", now), 1, now)), List.of(), now);
        Trip trip = live.trip(new Key("1", DAY, 0)).orElseThrow();
        assertEquals(at(100), trip.departed());
        Leg leg = legs().get(List.of("A", "B", ""));
        assertEquals(leg.ticks(SPEC, 0.5, OptionalInt.empty()).getAsInt(), trip.free(), "at its own throttle");
        assertTrue(live.signalTicks(new Key("2", DAY, 0)).getAsLong() > 0);
    }

    /** A duty to come within the hour starts on time; one later than the hour is not run. */
    @Test
    void theDutiesToComeWithinTheHourStartOnTime() {
        long now = at(100);
        LiveTraffic live = live(List.of(running(standing("1", now), 1, now)),
            List.of(new Coming("2", DAY, calls("2")), new Coming("3", DAY, calls("3"))), now);
        assertEquals(at(105), live.trip(new Key("2", DAY, 0)).orElseThrow().departed());
        assertTrue(live.signalTicks(new Key("2", DAY, 0)).getAsLong() > 0);
        assertTrue(live.trip(new Key("3", DAY, 0)).isEmpty(), "it leaves after the hour");
        assertTrue(live.signalTicks(new Key("3", DAY, 0)).isEmpty());
    }

    /**
     * A set is run on from the call it stands at, as late as it is to leave there, or from the one it
     * last left, as late as it left; a finished one has no way left.
     */
    @Test
    void aSetIsRunOnFromWhereItStandsOrLastLeft() {
        DutyRun first = standing("1", at(100));
        Running standing = running(first, 1, at(102));
        assertEquals(0, standing.from());
        assertEquals(at(102) - at(0), standing.lateBy(), "it leaves now, held past its time");
        first.sent();
        first.left(at(103));
        Running onItsWay = running(first, 1, at(110));
        assertEquals(0, onItsWay.from());
        assertEquals(at(103) - at(0), onItsWay.lateBy(), "as late as it left");
        first.arrived(at(170), MINIMUM_STOP);
        first.left(at(180));
        first.arrived(at(230), MINIMUM_STOP);
        assertTrue(first.finished());
        assertEquals(-1, running(first, 1, at(240)).from());
    }

    /** The sets are run on from where they are only if one is more than a second late. */
    @Test
    void theSetsAreRunOnOnlyIfOneIsLate() {
        DutyRun first = standing("1", at(0));
        DutyRun second = standing("2", at(0));
        assertFalse(LiveTraffic.anyLate(List.of(first, second), at(1)), "on time, held a second past its time");
        assertTrue(LiveTraffic.anyLate(List.of(first, second), at(1) + 1));
        first.left(at(0));
        first.arrived(at(170), MINIMUM_STOP);
        first.left(at(180));
        first.arrived(at(230), MINIMUM_STOP);
        assertTrue(first.finished());
        assertFalse(LiveTraffic.anyLate(List.of(first), at(300)), "a finished set, late as it came in");
        assertFalse(LiveTraffic.anyLate(List.of(), at(300)));
    }

    /** A run told to give up is not known, as one that cannot be worked out. */
    @Test
    void aRunToldToGiveUpSaysNothing() {
        long now = at(100);
        List<Running> running = List.of(running(standing("1", now), 1, now), running(standing("2", now), 1, now));
        LiveTraffic live = LiveTraffic.of(TIMETABLE, running, List.of(), legs(), NETWORK.blocks(), SPEC, MINIMUM_STOP, LENGTH, now, () -> true);
        assertFalse(live.finished());
        assertEquals(now, live.from());
        assertTrue(live.signalTicks(new Key("2", DAY, 0)).isEmpty());
    }

    /** A finished set, or one at no throttle, is not on the line. */
    @Test
    void aSetThatDoesNotMoveIsNotRun() {
        long now = at(100);
        LiveTraffic live = live(List.of(running(standing("1", now), 0, now), running(standing("2", now), 1, now)), List.of(), now);
        assertTrue(live.trip(new Key("1", DAY, 0)).isEmpty());
        assertEquals(0, live.signalTicks(new Key("2", DAY, 0)).getAsLong());
    }

    /**
     * The running times of a set take what the signals add from its ways here, and from the times given
     * where there are none: another day, another train, or nothing at all.
     */
    @Test
    void theTimesOfASetTakeItsWaysAndElseTheTimesGiven() {
        long now = at(100);
        LiveTraffic live = live(List.of(running(standing("1", now), 1, now), running(standing("2", now), 1, now)), List.of(), now);
        RunTimes given = new RunTimes() {
            @Override
            public OptionalLong ticks(String from, String to, String tag) {
                return OptionalLong.of(1);
            }

            @Override
            public long signalTicks(String train, int stop) {
                return 7;
            }
        };
        long held = live.signalTicks(new Key("2", DAY, 0)).getAsLong();
        assertEquals(held, live.on(given, "2", DAY, calls("2")).signalTicks("2", 0));
        assertEquals(1, live.on(given, "2", DAY, calls("2")).ticks("A", "B", "").getAsLong());
        assertEquals(7, live.on(given, "2", DAY + 1, calls("2")).signalTicks("2", 0), "the duty of another day");
        assertEquals(7, live.on(given, "2", DAY, calls("2")).signalTicks("9", 0), "a train not of the duty");
        assertEquals(7, live.on(given, "2", DAY, calls("2")).signalTicks("2", 1), "a stop it has no way from");
        assertEquals(7, LiveTraffic.NONE.on(given, "2", DAY, calls("2")).signalTicks("2", 0));
        assertFalse(LiveTraffic.NONE.signalTicks(new Key("2", DAY, 0)).isPresent());
    }
}
