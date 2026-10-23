package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.eyunya11.createrealtimeschedule.clock.RealClock;

/**
 * Which duties are to start, and which set takes each, with no Minecraft types in it so that it can
 * be tested without a game.
 *
 * <p>The timetable is for one day, and runs every day. A duty of service day d starts at d's 00:00
 * plus its first departure, which may be on the next day by the clock when the timetable writes it
 * past 24:00. A duty is given a set a lead time before that, and once a day at most. A duty that has
 * not been given a set by the time it was to leave does not run that day.
 */
public final class DutyCalendar {

    /**
     * A duty's start on one service day.
     *
     * @param duty the duty's id
     * @param day  the service day
     * @param at   the clock value it first leaves at
     */
    public record Start(String duty, long day, long at) {
        /** Whether it is too late to give the duty a set: its time to leave has gone by. */
        public boolean missedBy(long now) {
            return now > at;
        }
    }

    private DutyCalendar() {}

    /** The clock value at which a duty that first leaves at {@code firstDeparture} seconds starts on service {@code day}. */
    public static long startOf(long day, int firstDeparture) {
        return day * RealClock.TICKS_PER_DAY + firstDeparture * (long) RealClock.TICKS_PER_SECOND;
    }

    /** The last service day on which such a duty started at or before {@code now}. */
    public static long lastDayStartedBy(long now, int firstDeparture) {
        return Math.floorDiv(now - firstDeparture * (long) RealClock.TICKS_PER_SECOND, RealClock.TICKS_PER_DAY);
    }

    /**
     * The starts of a duty that are due by {@code now}: those within {@code lead} ticks from now, and
     * those gone by, which are missed. Days up to {@code lastDay}, the last the duty was dealt with, are
     * not looked at again, and nor are days before the last start gone by: after a long stop of the
     * server, a duty is missed once, not once for each day.
     */
    public static List<Start> due(String duty, int firstDeparture, long lastDay, long now, long lead) {
        List<Start> due = new ArrayList<>();
        long day = Math.max(lastDay + 1, lastDayStartedBy(now, firstDeparture));
        for (; startOf(day, firstDeparture) <= now + lead; day++)
            due.add(new Start(duty, day, startOf(day, firstDeparture)));
        return due;
    }

    /**
     * The set that has waited longest, by when each became free; of two that became free at the same
     * time, the one with the lower id, so that the choice comes out the same every time.
     */
    public static Optional<UUID> longestWaiting(Map<UUID, Long> freeSince) {
        return freeSince.entrySet().stream()
            .min(Map.Entry.<UUID, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
            .map(Map.Entry::getKey);
    }
}
