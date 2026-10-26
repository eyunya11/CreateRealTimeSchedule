package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import org.jetbrains.annotations.Nullable;

import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.diagram.LineAxis;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Train;

/**
 * Getting a late set back on time, by cancelling trains. It has no Minecraft types in it so that it
 * can be tested without a game.
 *
 * <p>A set no later than the threshold runs on, and makes up its delay where the timetable gives it
 * more time than it needs: standing longer than its minimum stop, or taking longer than the track does
 * ({@link DutyRun}). A later set that arrives at a station its duty comes back to is held there until
 * the first later call there that it can leave within the threshold, and the trains in between do not
 * run. It is not held:
 * <ul>
 * <li>when it would make up enough on the way anyway to be within the threshold when it next leaves
 * the station ({@link Forecast});</li>
 * <li>past a train the timetable marks keep;</li>
 * <li>where it would be in another train's way: a train of the timetable runs through the station
 * meanwhile, or the station would have no platform to spare for the sets that are to stand there
 * meanwhile ({@link StationUse});</li>
 * <li>to a call that Create finds no way on from, in time, from where the set stands, as for a set that
 * would have to run backwards to leave from it and cannot.</li>
 * </ul>
 *
 * <p>A duty that has had no free set by its time to leave can start late, while a set that leaves its
 * depot now would be within the threshold at the first station after the depot, or would make up
 * enough on the way, or could be held there.
 */
public final class Recovery {

    /**
     * What the recovery goes by.
     *
     * @param timetable          the timetable
     * @param calls              the calls of each of its duties, by the duty's id
     * @param axes               the axis of each line, by its id, for which way round a loop line a train
     *                           runs; a loop line with none goes by the way that passes fewer stations
     * @param threshold          how late a set may be, in ticks, before trains are cancelled; 0 for never
     * @param defaultMinimumStop the minimum stop, in ticks, at a call the timetable gives none
     * @param lead               how long before a duty first leaves it is given its set, in ticks
     */
    public record Rules(Timetable timetable, Map<String, List<Call>> calls, Map<String, LineAxis> axes, long threshold,
                        long defaultMinimumStop, long lead) {}

    /**
     * A set held at a station until a later call of its duty there.
     *
     * @param to        the call it leaves from
     * @param departure when it leaves from it
     * @param cancelled the trains that do not run at all, in order
     * @param cutShort  the trains that run only part of their way, in order
     */
    public record Hold(int to, long departure, List<String> cancelled, List<String> cutShort) {}

    /** What becomes of a set late at a station. */
    enum Outcome {
        /** It is no later than the threshold. */
        WITHIN,
        /** It makes up enough on the way to be within the threshold when it next leaves the station. */
        MAKES_UP,
        /** It is held. */
        HELD,
        /** It runs on late: there is no call it can be held to. */
        LATE
    }

    private record Plan(Outcome outcome, @Nullable Hold hold) {}

    private Recovery() {}

    /**
     * Whether the set of {@code run}, which has just arrived at the call it stands at, is to be held
     * there, and until which call; empty if it runs on.
     *
     * @param reach the set's track, and the trains around it
     */
    public static Optional<Hold> onArrival(Rules rules, DutyRun run, long now, Reach reach) {
        if (rules.threshold() <= 0 || run.phase() != DutyRun.Phase.STANDING)
            return Optional.empty();
        int call = run.call();
        long arrived = run.arrivals().get(call);
        if (arrived == DutyRun.NOT_YET)
            return Optional.empty();
        return Optional.ofNullable(
            plan(rules, run.duty(), run.calls(), run.day(), call, arrived, run.departure(), now, reach, reach, true, false).hold());
    }

    /**
     * Whether duty {@code duty} of service {@code day}, which has had no free set by its time to leave,
     * can still start with a set that leaves the depot now: it would leave the first station after the
     * depot within the threshold, or make up enough on the way to be within it when it next leaves
     * there, or could be held there until a later call from which a train that carries passengers
     * still runs (one held to run only deadheads back to the depot is of no use).
     *
     * @param times how long the track takes
     * @param set   the track and trains around the set that would take the duty, to find whether it
     *              could be held without being in another train's way; null while there is no free
     *              set, to find whether waiting for one is still of any use
     */
    public static boolean canStartLate(Rules rules, String duty, List<Call> calls, long day, long now, RunTimes times, @Nullable Reach set) {
        if (rules.threshold() <= 0)
            return false;
        long arrives = now + Forecast.runTicks(calls, 0, times);
        if (calls.size() == 2)
            return arrives - DutyPlan.at(day, calls.get(1).arrive()) <= rules.threshold();
        long departure = Math.max(DutyPlan.at(day, calls.get(1).depart()), arrives + DutyPlan.minimumStop(calls.get(1), rules.defaultMinimumStop()));
        return plan(rules, duty, calls, day, 1, arrives, departure, arrives, times, set, false, true).outcome() != Outcome.LATE;
    }

    /**
     * What becomes of the set of {@code duty} that arrived at {@code call} at {@code arrived} and is to
     * leave at {@code departure}.
     *
     * @param reach       the set's track and the trains around it, to keep it out of their way; null not
     *                    to look
     * @param findWay     whether to look for a way on from where the set stands now ({@code reach} has
     *                    to be there then)
     * @param needService whether a call it is held to has to have a train that carries passengers after it
     */
    private static Plan plan(Rules rules, String duty, List<Call> calls, long day, int call, long arrived, long departure, long now,
                             RunTimes times, @Nullable Reach reach, boolean findWay, boolean needService) {
        long threshold = rules.threshold();
        if (departure - DutyPlan.at(day, calls.get(call).depart()) <= threshold)
            return new Plan(Outcome.WITHIN, null);
        String station = calls.get(call).station();
        List<Integer> later = new ArrayList<>();
        for (int to = call + 2; to < calls.size() - 1; to++)
            if (calls.get(to).station().equals(station))
                later.add(to);
        if (later.isEmpty())
            return new Plan(Outcome.LATE, null);
        int first = later.getFirst();
        long running = Forecast.leaves(calls, day, call, departure, first, times, rules.defaultMinimumStop());
        if (running - DutyPlan.at(day, calls.get(first).depart()) <= threshold)
            return new Plan(Outcome.MAKES_UP, null);
        for (int to : later) {
            if (keeps(rules.timetable(), calls, call, to))
                break; // and so would every later call
            if (needService && !servedFrom(rules.timetable(), calls, to))
                break; // nor would any later call be
            long time = DutyPlan.at(day, calls.get(to).depart());
            long leaves = Math.max(Math.max(time, now), arrived + DutyPlan.minimumStop(calls.get(to), rules.defaultMinimumStop()));
            if (leaves - time > threshold)
                continue;
            if (reach != null && !roomFor(rules, duty, day, station, arrived, leaves, now, reach))
                continue;
            if (findWay && (reach == null || !wayOn(calls, to, reach, threshold)))
                continue;
            List<Integer> skipped = new ArrayList<>();
            for (int index = call + 1; index < to; index++)
                skipped.add(index);
            return new Plan(Outcome.HELD, new Hold(to, leaves, DutyPlan.cancelledTrains(calls, skipped), DutyPlan.partlyCancelledTrains(calls, skipped)));
        }
        return new Plan(Outcome.LATE, null);
    }

    /** Whether a train the timetable marks keep runs on one of the ways from call {@code from} to call {@code to}. */
    private static boolean keeps(Timetable timetable, List<Call> calls, int from, int to) {
        for (int way = from; way < to; way++)
            if (timetable.train(calls.get(way).leavingTrain()).map(Train::keep).orElse(false))
                return true;
        return false;
    }

    /** Whether a train that carries passengers runs on one of the ways from call {@code from} on. */
    private static boolean servedFrom(Timetable timetable, List<Call> calls, int from) {
        for (int way = from; way < calls.size() - 1; way++)
            if (timetable.train(calls.get(way).leavingTrain()).map(train -> !train.deadhead()).orElse(false))
                return true;
        return false;
    }

    /**
     * Whether the set can stand at {@code station} from {@code from} to {@code to} out of every other
     * train's way: no train runs through meanwhile, and there are platforms for it, the trains standing
     * there now, and the sets that come to stand there meanwhile.
     */
    private static boolean roomFor(Rules rules, String duty, long day, String station, long from, long to, long now, Reach reach) {
        if (StationUse.passedBy(rules.timetable(), rules.calls(), rules.axes(), duty, day, station, from, to))
            return false;
        int others = reach.standing(station) + StationUse.mostStanding(rules.calls(), duty, day, station, from, to, now, rules.lead());
        return others + 1 <= reach.platforms(station);
    }

    /** Whether the set can get from where it stands to the station after call {@code to} within the threshold of the timetable's time. */
    private static boolean wayOn(List<Call> calls, int to, Reach reach, long threshold) {
        OptionalLong ticks = reach.ticksTo(calls.get(to + 1).station());
        return ticks.isPresent() && ticks.getAsLong() <= Forecast.plannedRunTicks(calls, to) + threshold;
    }

    /**
     * What the set of {@code run} is noted down with when it is held as {@code hold} at the call it
     * stands at, before it is: how late it is there, and which trains do not run.
     */
    public static String note(DutyRun run, Hold hold) {
        Call at = run.calls().get(run.call());
        StringBuilder text = new StringBuilder("held at ").append(at.station()).append(", ")
            .append(duration(run.departure() - DutyPlan.at(run.day(), at.depart()))).append(" late: ");
        List<String> parts = new ArrayList<>();
        if (!hold.cancelled().isEmpty())
            parts.add((hold.cancelled().size() == 1 ? "train " : "trains ") + String.join(", ", hold.cancelled()) + " cancelled");
        if (!hold.cutShort().isEmpty())
            parts.add((hold.cutShort().size() == 1 ? "train " : "trains ") + String.join(", ", hold.cutShort()) + " cut short");
        text.append(String.join(", ", parts));
        return text.append("; it leaves at ").append(RealClock.format(hold.departure())).toString();
    }

    /** A length of time as {@code 25 min 20 s}, or {@code 40 s} under a minute. */
    static String duration(long ticks) {
        long seconds = Math.round(ticks / (double) RealClock.TICKS_PER_SECOND);
        return seconds < 60 ? seconds + " s" : seconds / 60 + " min " + seconds % 60 + " s";
    }
}
