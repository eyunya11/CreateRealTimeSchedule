package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.ArrayList;
import java.util.List;

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
}
