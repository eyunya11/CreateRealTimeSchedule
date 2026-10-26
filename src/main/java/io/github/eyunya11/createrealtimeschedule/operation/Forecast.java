package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.List;
import java.util.OptionalLong;

import io.github.eyunya11.createrealtimeschedule.clock.RealClock;

/**
 * When a set is to leave the calls ahead of it if nothing holds it up: it runs from each call to the
 * next in the least time the track takes, or in the timetable's time where that is not known, and
 * stands at each for its minimum stop at least, and until the timetable's time. That is how much of
 * its delay a late set makes up on the way. Signals and other trains are not in it, so it hopes for
 * the best; the operation works it out again each time a set arrives.
 *
 * <p>It has no Minecraft types in it so that it can be tested without a game.
 */
public final class Forecast {

    private Forecast() {}

    /**
     * The clock value at which a set that leaves call {@code from} of {@code calls} at
     * {@code departure}, on service {@code day}, is to leave the later call {@code to}, which has a
     * departure.
     *
     * @param defaultMinimumStop the minimum stop, in ticks, at a call the timetable gives none
     */
    public static long leaves(List<Call> calls, long day, int from, long departure, int to, RunTimes times, long defaultMinimumStop) {
        long leaves = departure;
        for (int call = from + 1; call <= to; call++) {
            long arrives = leaves + runTicks(calls, call - 1, times);
            leaves = Math.max(DutyPlan.at(day, calls.get(call).depart()), arrives + DutyPlan.minimumStop(calls.get(call), defaultMinimumStop));
        }
        return leaves;
    }

    /** The ticks a set takes from call {@code from} to the next: the track's least, or else the timetable's. */
    public static long runTicks(List<Call> calls, int from, RunTimes times) {
        Call leaving = calls.get(from);
        Call coming = calls.get(from + 1);
        OptionalLong track = times.ticks(leaving.station(), coming.station());
        if (track.isPresent())
            return track.getAsLong();
        return plannedRunTicks(calls, from);
    }

    /** The ticks the timetable gives a set from call {@code from} to the next. */
    public static long plannedRunTicks(List<Call> calls, int from) {
        return Math.max(0, calls.get(from + 1).comesIn() - calls.get(from).depart()) * (long) RealClock.TICKS_PER_SECOND;
    }
}
