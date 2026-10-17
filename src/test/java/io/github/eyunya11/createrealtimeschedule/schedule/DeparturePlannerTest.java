package io.github.eyunya11.createrealtimeschedule.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.schedule.DeparturePlanner.SlotRecord;

class DeparturePlannerTest {

    private static final long DAILY = RealClock.TICKS_PER_DAY;
    private static final long QUARTER_HOUR = 15 * RealClock.TICKS_PER_MINUTE;
    private static final long SECOND = RealClock.TICKS_PER_SECOND;
    private static final long MINUTE = RealClock.TICKS_PER_MINUTE;
    private static final int SIGNATURE = 77;

    private static long at(long day, int hour, int minute, int second) {
        return day * RealClock.TICKS_PER_DAY + RealClock.ticksOf(hour, minute, second);
    }

    private static long timeOfDay(int hour, int minute) {
        return RealClock.ticksOf(hour, minute, 0);
    }

    // ------------------------------------------------------------------ the departure rule

    @Test
    void anEarlyTrainWaitsForItsSlot() {
        assertEquals(at(0, 8, 10, 0), DeparturePlanner.departure(at(0, 8, 10, 0), at(0, 8, 8, 0), 30 * SECOND));
    }

    @Test
    void aLateTrainStopsForTheMinimumAndGoes() {
        assertEquals(at(0, 8, 11, 30), DeparturePlanner.departure(at(0, 8, 10, 0), at(0, 8, 11, 0), 30 * SECOND));
    }

    @Test
    void aTrainThatArrivesInTimeForItsSlotButTooLateForTheMinimumStopsForTheMinimum() {
        // Slot 08:10:00, arrival 08:09:45, stop 30 s: the slot is only 15 s away, so the stop wins.
        assertEquals(at(0, 8, 10, 15), DeparturePlanner.departure(at(0, 8, 10, 0), at(0, 8, 9, 45), 30 * SECOND));
    }

    @Test
    void aMinimumStopOfNothingLetsALateTrainLeaveAtOnce() {
        assertEquals(at(0, 8, 11, 0), DeparturePlanner.departure(at(0, 8, 10, 0), at(0, 8, 11, 0), 0));
    }

    @Test
    void theExampleTableOfTheProposalComesOut() {
        // Slot 8:10, minimum stop 30 s: on time, one minute late, three minutes late.
        long slot = at(0, 8, 10, 0);
        assertEquals(at(0, 8, 10, 0), DeparturePlanner.departure(slot, at(0, 8, 8, 0), 30 * SECOND));
        assertEquals(at(0, 8, 10, 0), DeparturePlanner.departure(slot, at(0, 8, 9, 0), 30 * SECOND));
        assertEquals(at(0, 8, 11, 30), DeparturePlanner.departure(slot, at(0, 8, 11, 0), 30 * SECOND));
    }

    // ------------------------------------------------------------------ choosing the slot

    @Test
    void withNothingToChainOffTheTrainTakesTheNearestSlot() {
        long slot = DeparturePlanner.chooseSlot(OptionalLong.empty(), at(0, 8, 8, 0), timeOfDay(8, 10), DAILY);
        assertEquals(at(0, 8, 10, 0), slot);
        // A train that turns up ten minutes after its slot has missed it: it does not wait a day.
        slot = DeparturePlanner.chooseSlot(OptionalLong.empty(), at(0, 8, 20, 0), timeOfDay(8, 10), DAILY);
        assertEquals(at(0, 8, 10, 0), slot);
    }

    @Test
    void aSlotChainsOffTheLastOne() {
        // Left the 08:10 stop on its slot; this stop asks for 08:25.
        long slot = DeparturePlanner.chooseSlot(OptionalLong.of(at(0, 8, 10, 0)), at(0, 8, 14, 0), timeOfDay(8, 25), DAILY);
        assertEquals(at(0, 8, 25, 0), slot);
    }

    @Test
    void aLateTrainKeepsItsOwnSlotInsteadOfSkippingAhead() {
        // Left 08:10 four minutes late, reaches the 08:15 stop at 08:19. Its slot is 08:15, now in the
        // past, not 08:15 tomorrow: it goes after the minimum stop.
        long slot = DeparturePlanner.chooseSlot(OptionalLong.of(at(0, 8, 10, 0)), at(0, 8, 19, 0), timeOfDay(8, 15), DAILY);
        assertEquals(at(0, 8, 15, 0), slot);
        assertEquals(at(0, 8, 19, 30), DeparturePlanner.departure(slot, at(0, 8, 19, 0), 30 * SECOND));
    }

    @Test
    void theSameTimeAskedForTwiceInARoundBooksTheNextDayTheSecondTime() {
        // First stop leaves on 08:00; the second asks for 08:00 as well. It must not read the train's
        // own departure as "already past 08:00" and leave at once.
        long slot = DeparturePlanner.chooseSlot(OptionalLong.of(at(0, 8, 0, 0)), at(0, 8, 5, 0), timeOfDay(8, 0), DAILY);
        assertEquals(at(1, 8, 0, 0), slot);
    }

    @Test
    void aChainedSlotAWholeRotationBehindStartsAgainFromTheNearestSlot() {
        // Every 15 minutes: stop B asks for :13. The train left stop A on 08:10, but turns up at B at 08:40.
        // Chained, it would leave B on the 08:13 slot, 27 minutes ago, and run late for good.
        long b = timeOfDay(8, 13);
        long slot = DeparturePlanner.chooseSlot(OptionalLong.of(at(0, 8, 10, 0)), at(0, 8, 40, 0), b, QUARTER_HOUR);
        assertEquals(at(0, 8, 43, 0), slot);
    }

    @Test
    void theEdgeOfTheRotationBehindIsExact() {
        long b = timeOfDay(8, 13);
        long chained = at(0, 8, 13, 0);
        OptionalLong previous = OptionalLong.of(at(0, 8, 10, 0));
        // One tick short of a whole rotation behind: still chained.
        assertEquals(chained, DeparturePlanner.chooseSlot(previous, chained + QUARTER_HOUR - 1, b, QUARTER_HOUR));
        // A whole rotation behind: the next slot has passed too, so start again.
        assertEquals(chained + QUARTER_HOUR, DeparturePlanner.chooseSlot(previous, chained + QUARTER_HOUR, b, QUARTER_HOUR));
    }

    @Test
    void aChainedSlotMoreThanARotationAheadStartsAgainFromTheNearestSlot() {
        // The clock was set back an hour while the train ran: its chained slot is an hour ahead of it.
        long b = timeOfDay(8, 13);
        long slot = DeparturePlanner.chooseSlot(OptionalLong.of(at(0, 8, 10, 0)), at(0, 7, 0, 0), b, QUARTER_HOUR);
        assertEquals(at(0, 6, 58, 0), slot);
    }

    @Test
    void theEdgeOfTheRotationAheadIsExact() {
        long b = timeOfDay(8, 13);
        long chained = at(0, 8, 13, 0);
        OptionalLong previous = OptionalLong.of(at(0, 8, 10, 0));
        // A whole rotation ahead: still chained. One tick more: start again.
        assertEquals(chained, DeparturePlanner.chooseSlot(previous, chained - QUARTER_HOUR, b, QUARTER_HOUR));
        assertEquals(RealClock.nearestSlot(chained - QUARTER_HOUR - 1, b, QUARTER_HOUR),
            DeparturePlanner.chooseSlot(previous, chained - QUARTER_HOUR - 1, b, QUARTER_HOUR));
    }

    @Test
    void aDailyTimetableTrainThatIsHoursLateStillKeepsItsSlot() {
        // Daily slots only start again once a train is a whole day out, so an hour late is still the same trip.
        long slot = DeparturePlanner.chooseSlot(OptionalLong.of(at(0, 8, 10, 0)), at(0, 9, 30, 0), timeOfDay(8, 25), DAILY);
        assertEquals(at(0, 8, 25, 0), slot);
    }

    @Test
    void aTrainThatSatForMoreThanADayStartsAgain() {
        long slot = DeparturePlanner.chooseSlot(OptionalLong.of(at(0, 8, 10, 0)), at(3, 8, 20, 0), timeOfDay(8, 25), DAILY);
        assertEquals(at(3, 8, 25, 0), slot);
    }

    // ------------------------------------------------------------------ what is remembered

    @Test
    void nothingIsChainedOffForATrainWeHaveNotSeen() {
        assertFalse(DeparturePlanner.previousSlot(null, SIGNATURE, at(0, 8, 0, 0)).isPresent());
    }

    @Test
    void aSlotBookedUnderAnotherScheduleIsNotChainedOff() {
        SlotRecord record = DeparturePlanner.remember(at(0, 8, 10, 0), at(0, 8, 8, 0), OptionalLong.empty(), SIGNATURE);
        assertFalse(DeparturePlanner.previousSlot(record, SIGNATURE + 1, at(0, 8, 14, 0)).isPresent());
        assertEquals(at(0, 8, 10, 0), DeparturePlanner.previousSlot(record, SIGNATURE, at(0, 8, 14, 0)).getAsLong());
    }

    @Test
    void conditionsOfOneStopTriedSideBySideAllChainOffTheSameSlot() {
        long before = at(0, 8, 0, 0);
        long arrival = at(0, 8, 4, 0);
        SlotRecord afterFirst = DeparturePlanner.remember(at(0, 8, 10, 0), arrival, OptionalLong.of(before), SIGNATURE);
        // The second column of the same stop starts in the same tick and must see the slot from before the stop.
        assertEquals(before, DeparturePlanner.previousSlot(afterFirst, SIGNATURE, arrival).getAsLong());
        // A later stop sees the slot the stop booked.
        assertEquals(at(0, 8, 10, 0), DeparturePlanner.previousSlot(afterFirst, SIGNATURE, arrival + 1).getAsLong());
    }

    @Test
    void aStopThatChainedOffNothingGivesNothingToItsOtherColumns() {
        long arrival = at(0, 8, 4, 0);
        SlotRecord afterFirst = DeparturePlanner.remember(at(0, 8, 10, 0), arrival, OptionalLong.empty(), SIGNATURE);
        assertFalse(DeparturePlanner.previousSlot(afterFirst, SIGNATURE, arrival).isPresent());
        assertTrue(DeparturePlanner.previousSlot(afterFirst, SIGNATURE, arrival + 1).isPresent());
    }

    // ------------------------------------------------------------------ a whole round

    /** One stop of a round: the time it asks for, and how long the train takes to reach it from the stop before. */
    private record Stop(long timeOfDay, long rotation, long minimumStop, long legBefore) {}

    /**
     * Runs a train round the given stops, starting by reaching the first at {@code firstArrival},
     * and returns the clock value it leaves each stop at. The memory is kept the way the mod keeps it.
     */
    private static List<Long> run(List<Stop> stops, long firstArrival, int rounds) {
        List<Long> departures = new ArrayList<>();
        SlotRecord memory = null;
        long arrival = firstArrival;
        for (int i = 0; i < rounds * stops.size(); i++) {
            Stop stop = stops.get(i % stops.size());
            if (i > 0)
                arrival = departures.get(i - 1) + stop.legBefore();
            OptionalLong previous = DeparturePlanner.previousSlot(memory, SIGNATURE, arrival);
            long slot = DeparturePlanner.chooseSlot(previous, arrival, stop.timeOfDay(), stop.rotation());
            memory = DeparturePlanner.remember(slot, arrival, previous, SIGNATURE);
            departures.add(DeparturePlanner.departure(slot, arrival, stop.minimumStop()));
        }
        return departures;
    }

    /** Three stops a quarter of an hour apart, on a daily timetable, four minutes between them. */
    private static List<Stop> dailyLine() {
        long stopTime = 30 * SECOND;
        return List.of(
            new Stop(timeOfDay(8, 10), DAILY, stopTime, 4 * MINUTE),
            new Stop(timeOfDay(8, 25), DAILY, stopTime, 4 * MINUTE),
            new Stop(timeOfDay(8, 40), DAILY, stopTime, 4 * MINUTE));
    }

    @Test
    void aTrainOnTimeLeavesEveryStopOnItsSlot() {
        List<Long> departures = run(dailyLine(), at(0, 8, 8, 0), 1);
        assertEquals(List.of(at(0, 8, 10, 0), at(0, 8, 25, 0), at(0, 8, 40, 0)), departures);
    }

    @Test
    void aTrainThatIsLateAtFirstWinsItBackByStoppingLess() {
        // Three minutes late at the first stop: it stops for 30 s only, leaves at 08:13:30, reaches
        // the second stop at 08:17:30 (4 minutes of running), which is in time for the 08:25 slot.
        List<Long> departures = run(dailyLine(), at(0, 8, 13, 0), 1);
        assertEquals(at(0, 8, 13, 30), departures.get(0));
        assertEquals(at(0, 8, 25, 0), departures.get(1));
        assertEquals(at(0, 8, 40, 0), departures.get(2));
    }

    @Test
    void lateByMoreThanTheStopCanGiveBackLeavesLateAndAtTheMinimum() {
        // Eighteen minutes of running put the train at the second stop three minutes past its slot,
        // more than the stop has to give back.
        long stopTime = 30 * SECOND;
        List<Stop> line = List.of(
            new Stop(timeOfDay(8, 10), DAILY, stopTime, 4 * MINUTE),
            new Stop(timeOfDay(8, 25), DAILY, stopTime, 18 * MINUTE));
        List<Long> departures = run(line, at(0, 8, 8, 0), 1);
        assertEquals(at(0, 8, 10, 0), departures.get(0));
        // 08:10 + 18 min = 08:28, three minutes past the 08:25 slot.
        assertEquals(at(0, 8, 28, 30), departures.get(1));
    }

    @Test
    void aDailyRoundWaitsForTomorrowAfterTheLastStop() {
        List<Long> departures = run(dailyLine(), at(0, 8, 8, 0), 2);
        assertEquals(at(1, 8, 10, 0), departures.get(3));
        assertEquals(at(1, 8, 25, 0), departures.get(4));
        assertEquals(at(1, 8, 40, 0), departures.get(5));
    }

    @Test
    void aRoundOfSlotsRepeatingEveryQuarterHourKeepsToTheTimetableAndSkipsNothing() {
        // The same two-stop line, repeating every 15 minutes: the :10 and :13 slots. A train leaves
        // each stop on a slot, round after round, a quarter of an hour apart on the same stop.
        long stopTime = 20 * SECOND;
        List<Stop> line = List.of(
            new Stop(timeOfDay(8, 10), QUARTER_HOUR, stopTime, 2 * MINUTE),
            new Stop(timeOfDay(8, 13), QUARTER_HOUR, stopTime, 2 * MINUTE));
        List<Long> departures = run(line, at(0, 8, 8, 0), 4);
        assertEquals(List.of(
            at(0, 8, 10, 0), at(0, 8, 13, 0),
            at(0, 8, 25, 0), at(0, 8, 28, 0),
            at(0, 8, 40, 0), at(0, 8, 43, 0),
            at(0, 8, 55, 0), at(0, 8, 58, 0)), departures);
    }

    @Test
    void aTrainThatStartsLateOnARepeatingLineTakesTheNearestSlotAndThenChainsOffIt() {
        // The first stop's slots are :10, :25, :40 and :55, and the train turns up at 08:32. Without a
        // slot to chain off it takes the nearest, 08:25, and leaves at the minimum stop; the second
        // stop's slots are chained off 08:25, so it is late there too, and the next round catches up.
        long stopTime = 20 * SECOND;
        List<Stop> line = List.of(
            new Stop(timeOfDay(8, 10), QUARTER_HOUR, stopTime, 2 * MINUTE),
            new Stop(timeOfDay(8, 13), QUARTER_HOUR, stopTime, 2 * MINUTE));
        List<Long> departures = run(line, at(0, 8, 32, 0), 2);
        assertEquals(at(0, 8, 32, 20), departures.get(0));
        // Arrives 08:34:20; the slot chained off 08:25 is 08:28, six minutes past: late, so the minimum stop.
        assertEquals(at(0, 8, 34, 40), departures.get(1));
        // Back at the first stop at 08:36:40: chained off 08:28 the slot is 08:40, so it waits for it.
        assertEquals(at(0, 8, 40, 0), departures.get(2));
    }
}
