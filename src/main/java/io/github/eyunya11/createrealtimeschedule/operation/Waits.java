package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import io.github.eyunya11.createrealtimeschedule.diagram.LineAxis;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Stop;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Train;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Wait;

/**
 * The trains the sets wait for ({@link Wait}), and whether a set may leave a call for them. It has no
 * Minecraft types in it so that it can be tested without a game.
 *
 * <p>A set that waits at a call for train X at station P waits until X, on the same service day, has
 * left P (a train that stops there) or run through it without stopping (one that does not): the last
 * time it does so before the set's own time to leave. Which way round a loop line X runs, and so where
 * it runs through P, is the track's, as the diagram draws it ({@link StationUse#runsThrough}). It does
 * not wait for a train that does not run that day: whose duty has no run going (not started, not run,
 * started before the timetable was loaded, ended or given up), or whose run does not make that call or
 * that way, held past it or at it until a later call (the train is cancelled). A train is seen to run
 * through P when its last carriage goes past a station named P ({@code PassWatch}); one that comes in at
 * the stop after the one after P without being seen so (its last carriage may still be short of P at
 * the first) has no station P on its way, and the wait ends then.
 */
public final class Waits {

    /**
     * Where a train waited for is to be: leaving call {@code call} of duty {@code duty} (a train that
     * stops at the station), or running through {@code station} on the way from that call (one that
     * does not).
     */
    public record Target(String train, String duty, int call, boolean through, String station) {}

    /** One wait of one call of a duty on one service day: the duty, the day, the call, and the wait's place among the call's. */
    public record Key(String duty, long day, int call, int index) {}

    /**
     * A wait that waits for nothing, so that the timetable's maker can be told: the train waited for is
     * in no duty, and so never runs, or by the way the track takes it, it neither leaves the station nor
     * runs through it before the waiting train leaves (on a loop line, it goes round the other way).
     *
     * @param train    the number of the train that waits
     * @param station  the station of the stop it waits at
     * @param waitFor  the wait, with its station filled in
     * @param inNoDuty whether the train waited for is in no duty
     */
    public record Unmet(String train, String station, Wait waitFor, boolean inNoDuty) {

        private static final String IN_NO_DUTY = "Train %1$s does not wait at %2$s for train %3$s, which is in no duty and never runs";
        private static final String NO_WAY = "Train %1$s does not wait at %2$s for train %3$s: the way the track takes that train,"
            + " it neither leaves %4$s nor runs through it before then";

        /**
         * What it is, in English, with %1$s for the waiting train, %2$s for its station, %3$s for the
         * train waited for and %4$s for the station it is waited for at: as the log has it, and as a
         * translation of the chat's falls back to.
         */
        public String pattern() {
            return inNoDuty ? IN_NO_DUTY : NO_WAY;
        }

        /** What {@link #pattern()} is filled in with. */
        public Object[] arguments() {
            return new Object[] {train, station, waitFor.train(), waitFor.at()};
        }

        /** What it is, in English. */
        public String text() {
            return String.format(pattern(), arguments());
        }
    }

    private static final Comparator<Unmet> UNMET_ORDER = Comparator.comparing(Unmet::train).thenComparing(Unmet::station)
        .thenComparing(unmet -> unmet.waitFor().train()).thenComparing(unmet -> unmet.waitFor().at());

    /** What a wait has come to. */
    public enum State {
        /** The train waited for has not left the station or run through it yet. */
        WAITING,
        /** It has, or it does not run: the set need not wait. */
        OVER,
        /** It came in at the stop after the next without being seen to run through the station: there is no such station on its way. */
        OVER_UNSEEN
    }

    /** Where a train's first stop is among the calls of the duty that runs it. */
    private record Start(String duty, int call) {}

    /** The target of each wait, by duty, call, and the wait's place among the call's; empty for one that waits for nothing ({@link #unmet}). */
    private final Map<String, Map<Integer, List<Optional<Target>>>> targets = new HashMap<>();
    private final Map<String, Map<Integer, Set<String>>> watched = new HashMap<>();
    private final List<Unmet> unmet = new ArrayList<>();

    private Waits() {}

    /**
     * The waits of {@code timetable}, whose duties make {@code calls}, by the duty's id, worked out once.
     *
     * @param axes the axis of each line, by its id, for which way round a loop line a train runs
     */
    public static Waits of(Timetable timetable, Map<String, List<Call>> calls, Map<String, LineAxis> axes) {
        Map<String, Start> starts = new HashMap<>();
        calls.forEach((duty, list) -> {
            for (int index = 0; index < list.size(); index++) {
                String train = list.get(index).leavingTrain();
                if (!train.isEmpty())
                    starts.putIfAbsent(train, new Start(duty, index));
            }
        });
        Waits waits = new Waits();
        calls.forEach((duty, list) -> {
            for (int index = 0; index < list.size(); index++) {
                Call call = list.get(index);
                if (call.waits().isEmpty())
                    continue;
                List<Optional<Target>> found = new ArrayList<>();
                for (Wait wait : call.waits()) {
                    Optional<Target> target = target(timetable, axes, starts, call, wait);
                    found.add(target);
                    if (target.isEmpty())
                        waits.unmet.add(new Unmet(call.leavingTrain(), call.station(), wait, !starts.containsKey(wait.train())));
                }
                waits.targets.computeIfAbsent(duty, any -> new HashMap<>()).put(index, found);
                for (Optional<Target> target : found)
                    target.filter(Target::through).ifPresent(through -> waits.watched
                        .computeIfAbsent(through.duty(), any -> new HashMap<>())
                        .computeIfAbsent(through.call(), any -> new HashSet<>())
                        .add(through.station()));
            }
        });
        waits.unmet.sort(UNMET_ORDER);
        return waits;
    }

    /** The waits that wait for nothing, by the waiting train's number and its station. */
    public List<Unmet> unmet() {
        return List.copyOf(unmet);
    }

    /**
     * Where the train {@code wait} is for is to leave its station or run through it, the last time
     * before {@code call}, which waits for it, is to leave; empty if it is in no duty, and so never
     * runs, or never does either before then.
     */
    private static Optional<Target> target(Timetable timetable, Map<String, LineAxis> axes, Map<String, Start> starts, Call call, Wait wait) {
        Start start = starts.get(wait.train());
        Train train = timetable.train(wait.train()).orElse(null);
        if (start == null || train == null)
            return Optional.empty();
        Target found = null;
        for (int s = 0; s + 1 < train.stops().size(); s++) {
            Stop stop = train.stops().get(s);
            if (!stop.hasDepart() || stop.depart() >= call.depart())
                continue;
            if (stop.station().equals(wait.at()))
                found = new Target(wait.train(), start.duty(), start.call() + s, false, wait.at());
            else if (StationUse.runsThrough(timetable, axes, train, stop.station(), train.stops().get(s + 1).station(), wait.at()))
                found = new Target(wait.train(), start.duty(), start.call() + s, true, wait.at());
        }
        return Optional.ofNullable(found);
    }

    /** Where the train of wait {@code wait} of call {@code call} of {@code duty} is waited for; empty if it does not run. */
    public Optional<Target> target(String duty, int call, int wait) {
        List<Optional<Target>> found = targets.getOrDefault(duty, Map.of()).get(call);
        return found == null || wait >= found.size() ? Optional.empty() : found.get(wait);
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
        // Held past the call, or at it until a later one, the set does not run the way from it: the train is cancelled.
        if (run.skipped(call) || run.skipped(call + 1))
            return State.OVER;
        if (!target.through())
            return run.departures().get(call) != DutyRun.NOT_YET ? State.OVER : State.WAITING;
        if (run.passedBy(call, target.station()))
            return State.OVER;
        List<Long> arrivals = run.arrivals();
        for (int later = call + 2; later < arrivals.size(); later++)
            if (arrivals.get(later) != DutyRun.NOT_YET)
                return State.OVER_UNSEEN;
        return State.WAITING;
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
