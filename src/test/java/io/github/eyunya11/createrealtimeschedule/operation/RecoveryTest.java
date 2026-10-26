package io.github.eyunya11.createrealtimeschedule.operation;

import static io.github.eyunya11.createrealtimeschedule.operation.OperationTimetables.t;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.operation.Recovery.Hold;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Stop;

/** The laps of {@link OperationTimetables#laps}, 10 minutes the threshold, 20 seconds the server's minimum stop. */
class RecoveryTest {

    private static final long DAY = 3;
    private static final long SECOND = RealClock.TICKS_PER_SECOND;
    private static final long THRESHOLD = 600 * SECOND;
    private static final long MINIMUM = 20 * SECOND;
    private static final long LEAD = 60 * SECOND;

    private static long at(String time) {
        return DutyPlan.at(DAY, t(time));
    }

    /**
     * The track around the set, as the game would tell: 12 minutes between A and B and 4 between the
     * depot and A, the platforms of each station, the trains standing there, and how long Create
     * would take the set on from where it stands, or empty if it would find no way.
     */
    private record Around(RunTimes times, int platforms, int standing, OptionalLong onward) implements Reach {
        Around(int platforms, int standing, OptionalLong onward) {
            this(ForecastTest.TRACK, platforms, standing, onward);
        }

        @Override
        public OptionalLong ticks(String from, String to) {
            return times.ticks(from, to);
        }

        @Override
        public int platforms(String station) {
            return platforms;
        }

        @Override
        public int standing(String station) {
            return standing;
        }

        @Override
        public OptionalLong ticksTo(String station) {
            return onward;
        }
    }

    /** One platform, no other train, and on to B in 12 minutes, as the track takes. */
    private static final Around CLEAR = new Around(1, 0, OptionalLong.of(720 * SECOND));

    private static Recovery.Rules rules(Timetable timetable) {
        return new Recovery.Rules(timetable, StationUseTest.calls(timetable), Map.of(), THRESHOLD, MINIMUM, LEAD);
    }

    /** The run of 1A, its set come into A, call 1, at {@code arrived}. */
    private static DutyRun arrivedAtA(Timetable timetable, long arrived) {
        DutyRun run = DutyRun.start("1A", DAY, DutyPlan.calls(timetable, timetable.duties().getFirst()), at("07:49"));
        run.left(at("07:50"));
        run.arrived(arrived, MINIMUM);
        return run;
    }

    private static Optional<Hold> onArrival(Timetable timetable, long arrived, Reach reach) {
        return Recovery.onArrival(rules(timetable), arrivedAtA(timetable, arrived), arrived, reach);
    }

    @Test
    void aSetNoLaterThanTheThresholdRunsOn() {
        // A and B have a minimum stop of 2 minutes, all the time they stand, and the track takes 14
        // minutes, a minute more than the timetable: the set loses time on the way, and makes none up.
        Timetable timetable = OperationTimetables.laps(120, List.of(), List.of(), List.of());
        RunTimes slow = (from, to) -> OptionalLong.of(840 * SECOND);
        Around slowTrack = new Around(slow, 1, 0, OptionalLong.of(840 * SECOND));
        assertEquals(Optional.empty(), onArrival(timetable, at("08:08"), slowTrack), "it leaves at 08:10, 10 minutes late");
        assertEquals(Optional.of(new Hold(3, at("08:30"), List.of("1", "2"), List.of())), onArrival(timetable, at("08:08") + 1, slowTrack),
            "a tick later than that, it is held at A until 08:30, and 1 and 2 do not run");
    }

    @Test
    void aSetThatMakesUpEnoughOnTheWayIsNotHeld() {
        Timetable timetable = OperationTimetables.laps();
        // 12 minutes 20 late at A, it is back at A to leave at 08:37, 7 minutes late.
        assertEquals(Optional.empty(), onArrival(timetable, at("08:12"), CLEAR));
        // 25 minutes 20 late, it would leave A again 20 minutes late, at 08:50.
        assertEquals(Optional.of(new Hold(3, at("08:30"), List.of("1", "2"), List.of())), onArrival(timetable, at("08:25"), CLEAR));
    }

    @Test
    void theSetIsHeldUntilTheFirstLaterCallItCanLeaveWithinTheThreshold() {
        Timetable timetable = OperationTimetables.laps();
        assertEquals(Optional.of(new Hold(3, at("08:35:20"), List.of("1", "2"), List.of())), onArrival(timetable, at("08:35"), CLEAR),
            "5 minutes 20 late at 08:30 is within the threshold");
        assertEquals(Optional.of(new Hold(5, at("09:00"), List.of("1", "2", "3", "4"), List.of())), onArrival(timetable, at("08:41"), CLEAR),
            "11 minutes 20 late at 08:30 is not, so it waits for 09:00");
        assertEquals(Optional.empty(), onArrival(timetable, at("09:41"), CLEAR), "no call at A is left to leave in time");
    }

    @Test
    void aTrainMarkedKeepIsNeverCancelled() {
        assertEquals(Optional.empty(), onArrival(OperationTimetables.laps(Stop.NONE, List.of("2"), List.of(), List.of()), at("08:25"), CLEAR));
        Timetable fourKept = OperationTimetables.laps(Stop.NONE, List.of("4"), List.of(), List.of());
        assertEquals(Optional.of(new Hold(3, at("08:30"), List.of("1", "2"), List.of())), onArrival(fourKept, at("08:25"), CLEAR),
            "a kept train after the ones cancelled is no matter");
        assertEquals(Optional.empty(), onArrival(fourKept, at("08:41"), CLEAR), "it is no matter until the set would have to be held past it");
    }

    @Test
    void theSetIsNotHeldToACallItCannotGetAwayFromInTime() {
        Timetable timetable = OperationTimetables.laps();
        assertEquals(Optional.empty(), onArrival(timetable, at("08:25"), new Around(1, 0, OptionalLong.empty())), "Create finds no way on");
        long inTime = (13 * 60 + 600) * SECOND; // the timetable's 13 minutes, and the threshold
        assertTrue(onArrival(timetable, at("08:25"), new Around(1, 0, OptionalLong.of(inTime))).isPresent());
        assertEquals(Optional.empty(), onArrival(timetable, at("08:25"), new Around(1, 0, OptionalLong.of(inTime + 1))));
    }

    @Test
    void theSetIsNotHeldWhereATrainRunsThroughMeanwhile() {
        assertEquals(Optional.empty(), onArrival(StationUseTest.withExpress("08:20"), at("08:25"), CLEAR),
            "the express runs through A from 08:20 to 08:55");
        assertTrue(onArrival(StationUseTest.withExpress("10:00"), at("08:25"), CLEAR).isPresent());
    }

    @Test
    void theSetIsNotHeldWhereItWouldTakeAPlatformAnotherSetNeeds() {
        Timetable timetable = StationUseTest.withSetStandingAtA();
        assertEquals(Optional.empty(), onArrival(timetable, at("08:25"), CLEAR), "3A's set comes to stand at A from 08:26 to 08:29");
        assertTrue(onArrival(timetable, at("08:25"), new Around(2, 0, OptionalLong.of(720 * SECOND))).isPresent());
        assertEquals(Optional.empty(), onArrival(timetable, at("08:25"), new Around(2, 1, OptionalLong.of(720 * SECOND))),
            "and a train stands there already");
        assertTrue(onArrival(timetable, at("08:25"), new Around(3, 1, OptionalLong.of(720 * SECOND))).isPresent());
    }

    @Test
    void nothingIsHeldWithTheThresholdAtNothing() {
        Timetable timetable = OperationTimetables.laps();
        Recovery.Rules off = new Recovery.Rules(timetable, StationUseTest.calls(timetable), Map.of(), 0, MINIMUM, LEAD);
        assertEquals(Optional.empty(), Recovery.onArrival(off, arrivedAtA(timetable, at("08:25")), at("08:25"), CLEAR));
        List<Call> calls = DutyPlan.calls(timetable, timetable.duties().getFirst());
        assertFalse(Recovery.canStartLate(off, "1A", calls, DAY, at("07:51"), ForecastTest.TRACK, null));
    }

    @Test
    void aDutyWithNoSetByItsTimeCanStartLateWhileItCanGetBackOnTime() {
        Timetable timetable = OperationTimetables.laps();
        Recovery.Rules rules = rules(timetable);
        List<Call> calls = DutyPlan.calls(timetable, timetable.duties().getFirst());
        assertTrue(Recovery.canStartLate(rules, "1A", calls, DAY, at("07:51"), ForecastTest.TRACK, null), "at A by 07:55, in time");
        assertTrue(Recovery.canStartLate(rules, "1A", calls, DAY, at("08:20"), ForecastTest.TRACK, null), "held at A until 08:30");
        assertTrue(Recovery.canStartLate(rules, "1A", calls, DAY, at("08:50"), ForecastTest.TRACK, null), "held at A until 09:00");
        assertFalse(Recovery.canStartLate(rules, "1A", calls, DAY, at("09:10"), ForecastTest.TRACK, null),
            "only the deadhead back to the depot is left to run from A in time");
        assertFalse(Recovery.canStartLate(rules, "1A", calls, DAY, at("09:40"), ForecastTest.TRACK, null));
    }

    /** At 09:15 the only call at A it can leave in time is 09:30, from which only the deadhead to the depot runs. */
    @Test
    void aSetIsNotHeldOnlyToRunADeadheadBackToTheDepot() {
        assertEquals(Optional.empty(), onArrival(OperationTimetables.laps(), at("09:15"), CLEAR));
    }

    /** The laps of 1A, and the set of 3A, which comes from the depot to stand at A from 08:22 to 08:29. */
    private static Timetable withSetComingToA() {
        Timetable.Train in = new Timetable.Train("S1", "l", "", List.of(Stop.leaving("Depot", t("08:16")), Stop.arriving("A", t("08:22"))), true);
        Timetable.Train out = new Timetable.Train("S2", "l", "", List.of(Stop.leaving("A", t("08:29")), Stop.arriving("Depot", t("08:35"))), true);
        return OperationTimetables.laps(Stop.NONE, List.of(), List.of(in, out),
            List.of(new Timetable.Duty("3A", "Depot", List.of("S1", "S2"))));
    }

    /**
     * A set that would get to A at 08:24 counts the set of 3A, which comes in at 08:22 while it is on
     * its way, and not the trains at A now, which may have gone by then.
     */
    @Test
    void aDutyStartingLateCountsTheSetsAtTheStationWhenItGetsThere() {
        Timetable timetable = withSetComingToA();
        List<Call> calls = DutyPlan.calls(timetable, timetable.duties().getFirst());
        assertFalse(Recovery.canStartLate(rules(timetable), "1A", calls, DAY, at("08:20"), ForecastTest.TRACK, CLEAR));
        assertTrue(Recovery.canStartLate(rules(timetable), "1A", calls, DAY, at("08:20"), ForecastTest.TRACK,
            new Around(2, 0, OptionalLong.of(720 * SECOND))));
        Timetable laps = OperationTimetables.laps();
        assertTrue(Recovery.canStartLate(rules(laps), "1A", DutyPlan.calls(laps, laps.duties().getFirst()), DAY, at("08:20"), ForecastTest.TRACK,
            new Around(1, 1, OptionalLong.of(720 * SECOND))), "a train standing at A now is gone by 08:24 as far as anyone knows");
    }

    @Test
    void aDutyStartsLateOnlyWhereItsSetCouldBeHeld() {
        Timetable timetable = StationUseTest.withExpress("08:20");
        Recovery.Rules rules = rules(timetable);
        List<Call> calls = DutyPlan.calls(timetable, timetable.duties().getFirst());
        assertTrue(Recovery.canStartLate(rules, "1A", calls, DAY, at("08:20"), ForecastTest.TRACK, null),
            "with no set yet, it is worth waiting for one");
        assertFalse(Recovery.canStartLate(rules, "1A", calls, DAY, at("08:20"), ForecastTest.TRACK, CLEAR),
            "but the express runs through A while the set would be held there");
        assertTrue(Recovery.canStartLate(rules, "1A", calls, DAY, at("07:51"), ForecastTest.TRACK, CLEAR), "in time, it is not held at all");
    }

    @Test
    void aDutyDoesNotStartLateIfThatCancelsAKeptTrain() {
        Timetable timetable = OperationTimetables.laps(Stop.NONE, List.of("1"), List.of(), List.of());
        List<Call> calls = DutyPlan.calls(timetable, timetable.duties().getFirst());
        assertFalse(Recovery.canStartLate(rules(timetable), "1A", calls, DAY, at("08:20"), ForecastTest.TRACK, null));
        assertTrue(Recovery.canStartLate(rules(timetable), "1A", calls, DAY, at("07:51"), ForecastTest.TRACK, null));
    }

    @Test
    void theNoteSaysHowLateTheSetIsAndWhichTrainsDoNotRun() {
        Timetable timetable = OperationTimetables.laps();
        DutyRun run = arrivedAtA(timetable, at("08:25"));
        Hold hold = Recovery.onArrival(rules(timetable), run, at("08:25"), CLEAR).orElseThrow();
        assertEquals("held at A, 25 min 20 s late: trains 1, 2 cancelled; it leaves at 08:30:00", Recovery.note(run, hold));
        assertEquals("held at A, 25 min 20 s late: train 1 cancelled, train 2 cut short; it leaves at 08:30:00",
            Recovery.note(run, new Hold(3, at("08:30"), List.of("1"), List.of("2"))));
        assertEquals("40 s", Recovery.duration(40 * SECOND));
    }
}
