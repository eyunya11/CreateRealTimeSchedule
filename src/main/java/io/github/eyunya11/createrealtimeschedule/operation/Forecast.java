package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.List;
import java.util.OptionalInt;
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
        // The limit is carried on from call to call here, not worked out again from the train's first call for each.
        OptionalInt limit = from < to ? limitAt(calls, from, times) : OptionalInt.empty();
        for (int call = from + 1; call <= to; call++) {
            long arrives = leaves + runTicks(calls, call - 1, times, limit);
            leaves = Math.max(DutyPlan.at(day, calls.get(call).depart()), arrives + DutyPlan.minimumStop(calls.get(call), defaultMinimumStop));
            if (call < to)
                limit = limitAfter(calls, call - 1, limit, times);
        }
        return leaves;
    }

    /**
     * The ticks a set takes from call {@code from} to the next, on the track tag it leaves on: the
     * track's least, or else the timetable's.
     */
    public static long runTicks(List<Call> calls, int from, RunTimes times) {
        return runTicks(calls, from, times, limitAt(calls, from, times));
    }

    /** {@link #runTicks(List, int, RunTimes)} for a set that leaves call {@code from} under {@code limit} ({@link #limitAt}). */
    private static long runTicks(List<Call> calls, int from, RunTimes times, OptionalInt limit) {
        Call leaving = calls.get(from);
        Call coming = calls.get(from + 1);
        OptionalLong track = times.ticks(leaving.station(), coming.station(), leaving.tag(), limit);
        if (track.isPresent())
            return track.getAsLong();
        return plannedRunTicks(calls, from);
    }

    /**
     * The limit of a speed-limit sign of Create Train Next the set is under as it leaves call
     * {@code call}, in km/h (empty for none). A limit lasts until the train passes the next sign, stops
     * and all, so it is carried from call to call; each train of the duty is taken to start under none,
     * since where trains turn back the signs are put up facing both ways.
     */
    static OptionalInt limitAt(List<Call> calls, int call, RunTimes times) {
        String train = calls.get(call).leavingTrain();
        int first = call;
        while (first > 0 && calls.get(first - 1).leavingTrain().equals(train))
            first--;
        OptionalInt limit = OptionalInt.empty();
        for (int way = first; way < call; way++)
            limit = times.limitAtEnd(calls.get(way).station(), calls.get(way + 1).station(), calls.get(way).tag(), limit);
        return limit;
    }

    /**
     * The limit the set is under as it leaves call {@code way + 1}, having left call {@code way} under
     * {@code limit}: carried on if it goes on as the same train, else none ({@link #limitAt}).
     */
    static OptionalInt limitAfter(List<Call> calls, int way, OptionalInt limit, RunTimes times) {
        Call leaving = calls.get(way);
        Call coming = calls.get(way + 1);
        if (!coming.leavingTrain().equals(leaving.leavingTrain()))
            return OptionalInt.empty();
        return times.limitAtEnd(leaving.station(), coming.station(), leaving.tag(), limit);
    }

    /** The ticks the timetable gives a set from call {@code from} to the next. */
    public static long plannedRunTicks(List<Call> calls, int from) {
        return Math.max(0, calls.get(from + 1).comesIn() - calls.get(from).depart()) * (long) RealClock.TICKS_PER_SECOND;
    }
}
