package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Line;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Stop;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Train;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Wait;

/**
 * The trains the sets wait for ({@link Wait}), and whether a set may leave a call for them. It has no
 * Minecraft types in it so that it can be tested without a game.
 *
 * <p>A set that waits at a call for train X at station P waits until X, on the same service day, has
 * left P (a train that stops there) or run through it without stopping (one that does not): the last
 * time it does so before the set's own time to leave, as the timetable is checked for. It does not wait
 * for a train that does not run that day: whose duty has no run going (not started, not run, started
 * before the timetable was loaded, ended or given up), or whose run does not make that call or that
 * way, held past it. A train is seen to run through P when its last carriage goes past a station named
 * P ({@code PassWatch}); one that comes in at the stop after P without being seen so has no station P
 * on its way, and the wait ends then.
 */
public final class Waits {

    /**
     * Where a train waited for is to be: leaving call {@code call} of duty {@code duty} (a train that
     * stops at the station), or running through {@code station} on the way from that call (one that
     * does not).
     */
    public record Target(String train, String duty, int call, boolean through, String station) {}

    /** What a wait has come to. */
    public enum State {
        /** The train waited for has not left the station or run through it yet. */
        WAITING,
        /** It has, or it does not run: the set need not wait. */
        OVER,
        /** It came in at the stop after the station without being seen to run through it: there is no such station on its way. */
        OVER_UNSEEN
    }

    /** Where a train's first stop is among the calls of the duty that runs it. */
    private record Start(String duty, int call) {}

    private final Timetable timetable;
    private final Map<String, Start> starts;
    private final Map<String, Map<Integer, Set<String>>> watched = new HashMap<>();

    private Waits(Timetable timetable, Map<String, Start> starts) {
        this.timetable = timetable;
        this.starts = starts;
    }

    /** The waits of {@code timetable}, whose duties make {@code calls}, by the duty's id. */
    public static Waits of(Timetable timetable, Map<String, List<Call>> calls) {
        Map<String, Start> starts = new HashMap<>();
        calls.forEach((duty, list) -> {
            for (int index = 0; index < list.size(); index++) {
                String train = list.get(index).leavingTrain();
                if (!train.isEmpty())
                    starts.putIfAbsent(train, new Start(duty, index));
            }
        });
        Waits waits = new Waits(timetable, starts);
        for (List<Call> list : calls.values())
            for (Call call : list)
                for (Wait wait : call.waits())
                    waits.target(call, wait).filter(Target::through).ifPresent(target -> waits.watched
                        .computeIfAbsent(target.duty(), duty -> new HashMap<>())
                        .computeIfAbsent(target.call(), way -> new HashSet<>())
                        .add(target.station()));
        return waits;
    }

    /**
     * Where the train {@code wait} is for is to leave its station or run through it, the last time
     * before {@code call}, which waits for it, is to leave; empty if it is in no duty, and so never
     * runs.
     */
    public Optional<Target> target(Call call, Wait wait) {
        Start start = starts.get(wait.train());
        Train train = timetable.train(wait.train()).orElse(null);
        Line line = train == null ? null : timetable.line(train.line()).orElse(null);
        if (start == null || line == null)
            return Optional.empty();
        List<String> names = train.stops().stream().map(Stop::station).toList();
        Target found = null;
        for (int s = 0; s + 1 < names.size(); s++) {
            Stop stop = train.stops().get(s);
            if (!stop.hasDepart() || stop.depart() >= call.depart())
                continue;
            if (stop.station().equals(wait.at()))
                found = new Target(wait.train(), start.duty(), start.call() + s, false, wait.at());
            else if (line.runsThrough(names, s, wait.at()))
                found = new Target(wait.train(), start.duty(), start.call() + s, true, wait.at());
        }
        return Optional.ofNullable(found);
    }

    /** The stations a train waits for the set of {@code duty} to run through on the way from call {@code way}; none if no train does. */
    public Set<String> watched(String duty, int way) {
        return watched.getOrDefault(duty, Map.of()).getOrDefault(way, Set.of());
    }

    /** What the wait for {@code target} has come to, with {@code run} the run of its duty that is going on the day, or null if none is. */
    public static State state(Target target, @Nullable DutyRun run) {
        if (run == null)
            return State.OVER;
        int call = target.call();
        if (!target.through())
            return run.departures().get(call) != DutyRun.NOT_YET || run.skipped(call) ? State.OVER : State.WAITING;
        if (run.passedBy(call, target.station()) || run.skipped(call) || run.skipped(call + 1))
            return State.OVER;
        return run.arrivals().get(call + 1) != DutyRun.NOT_YET ? State.OVER_UNSEEN : State.WAITING;
    }

    /**
     * When the train of {@code run} is to leave the call of {@code target}, if nothing holds it up
     * ({@link Forecast}): for a train that runs through the station after it, the least it can be when
     * it does.
     *
     * @param defaultMinimumStop the minimum stop, in ticks, at a call the timetable gives none
     */
    public static long forecast(Target target, DutyRun run, long now, RunTimes times, long defaultMinimumStop) {
        int call = target.call();
        if (run.departures().get(call) != DutyRun.NOT_YET)
            return run.departures().get(call);
        List<Call> calls = run.calls();
        int at = run.call();
        long leaves;
        if (run.phase() == DutyRun.Phase.RUNNING) {
            long arrives = Math.max(now, run.departures().get(at - 1) + Forecast.runTicks(calls, at - 1, times));
            leaves = Math.max(DutyPlan.at(run.day(), calls.get(at).depart()), arrives + DutyPlan.minimumStop(calls.get(at), defaultMinimumStop));
        } else {
            leaves = Math.max(run.departure(), now);
        }
        return call <= at ? leaves : Forecast.leaves(calls, run.day(), at, leaves, call, times, defaultMinimumStop);
    }
}
