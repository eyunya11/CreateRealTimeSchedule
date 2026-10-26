package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Duty;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Stop;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Train;

/**
 * The calls a duty makes, in order, from the depot and back: what the operation follows. It has no
 * Minecraft types in it so that it can be tested without a game.
 */
public final class DutyPlan {

    private DutyPlan() {}

    /**
     * The calls of {@code duty}, which has to be one that {@link Timetable#problems()} finds nothing
     * wrong with: the trains are there, and each starts where the one before ended.
     *
     * @throws IllegalArgumentException if a train of the duty is not in the timetable
     */
    public static List<Call> calls(Timetable timetable, Duty duty) {
        List<Call> calls = new ArrayList<>();
        Train previous = null;
        for (String number : duty.trains()) {
            Train train = timetable.train(number)
                .orElseThrow(() -> new IllegalArgumentException("duty " + duty.id() + " has no train " + number));
            List<Stop> stops = train.stops();
            Stop first = stops.getFirst();
            if (previous == null) {
                calls.add(new Call(first.station(), Stop.NONE, first.depart(), "", number, timetable.minimumStop(first)));
            } else {
                // The train before has come in here; the set leaves as this one, so this one's stop says how long it stands at the least.
                Call end = calls.removeLast();
                calls.add(new Call(end.station(), end.arrive(), first.depart(), previous.number(), number, timetable.minimumStop(first)));
            }
            for (int k = 1; k < stops.size(); k++) {
                Stop stop = stops.get(k);
                boolean last = k == stops.size() - 1;
                calls.add(new Call(stop.station(), stop.arrive(), last ? Stop.NONE : stop.depart(), number, last ? "" : number,
                    last ? Stop.NONE : timetable.minimumStop(stop)));
            }
            previous = train;
        }
        return calls;
    }

    /** When the duty first leaves, as {@code ServiceTime} seconds. */
    public static int firstDeparture(List<Call> calls) {
        return calls.getFirst().depart();
    }

    /**
     * The clock value of {@code seconds} of the timetable on service {@code day}. The timetable's times
     * count from 00:00 of the service day, and run on past midnight.
     */
    public static long at(long day, int seconds) {
        return day * RealClock.TICKS_PER_DAY + seconds * (long) RealClock.TICKS_PER_SECOND;
    }

    /** The minimum stop at {@code call}, in ticks: the timetable's, or else {@code defaultMinimumStop}. */
    public static long minimumStop(Call call, long defaultMinimumStop) {
        int seconds = call.minimumStop();
        return seconds < 0 ? defaultMinimumStop : seconds * (long) RealClock.TICKS_PER_SECOND;
    }

    /**
     * Which of the ways from one call to the next are not run, by the call each leaves from: those from
     * or to a call the set does not make, having been held past it ({@link DutyRun#hold}).
     *
     * @param skipped the calls not made
     */
    public static boolean[] cancelledWays(List<Call> calls, Collection<Integer> skipped) {
        boolean[] cancelled = new boolean[calls.size() - 1];
        for (int index : skipped) {
            cancelled[index - 1] = true;
            cancelled[index] = true;
        }
        return cancelled;
    }

    /** The trains of the duty none of whose way is run, in order, as {@link #cancelledWays} has it. */
    public static List<String> cancelledTrains(List<Call> calls, Collection<Integer> skipped) {
        return trains(calls, skipped, true);
    }

    /** The trains of the duty some, but not all, of whose way is not run, in order. */
    public static List<String> partlyCancelledTrains(List<Call> calls, Collection<Integer> skipped) {
        return trains(calls, skipped, false);
    }

    private static List<String> trains(List<Call> calls, Collection<Integer> skipped, boolean whole) {
        boolean[] cancelled = cancelledWays(calls, skipped);
        Map<String, int[]> counts = new LinkedHashMap<>(); // a train's ways, and how many of them are not run
        for (int way = 0; way < cancelled.length; way++) {
            int[] count = counts.computeIfAbsent(calls.get(way).leavingTrain(), number -> new int[2]);
            count[0]++;
            if (cancelled[way])
                count[1]++;
        }
        List<String> trains = new ArrayList<>();
        counts.forEach((number, count) -> {
            if (count[1] > 0 && (count[1] == count[0]) == whole)
                trains.add(number);
        });
        return trains;
    }
}
