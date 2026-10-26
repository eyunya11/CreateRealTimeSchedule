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

    private Recovery() {}

    /**
     * Whether the set of {@code run} stands at a call to leave later than the threshold: the sets the
     * recovery looks at. None is, with the threshold at nothing.
     */
    public static boolean tooLate(DutyRun run, long threshold) {
        return threshold > 0 && run.phase() == DutyRun.Phase.STANDING && run.departure() - run.plannedDeparture(run.call()) > threshold;
    }

    /**
     * Whether the set of {@code run}, which has just arrived at the call it stands at, is to be held
     * there, and until which call; empty if it runs on.
     *
     * @param reach the set's track, and the trains around it
     */
    public static Optional<Hold> onArrival(Rules rules, DutyRun run, long now, Reach reach) {
        if (!tooLate(run, rules.threshold()))
            return Optional.empty();
        int call = run.call();
        long arrived = run.arrivals().get(call);
        if (arrived == DutyRun.NOT_YET || makesUp(rules, run.calls(), run.day(), call, run.departure(), reach))
            return Optional.empty();
        return hold(rules, run.duty(), run.calls(), run.day(), call, arrived, now, reach, reach, true);
    }

    /**
     * Whether duty {@code duty} of service {@code day}, which has had no free set by its time to leave,
     * can still start with a set that leaves the depot now: it would leave the first station after the
     * depot within the threshold, or make up enough on the way to be within it when it next leaves
     * there, or could be held there until a later call.
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
        if (departure - DutyPlan.at(day, calls.get(1).depart()) <= rules.threshold() || makesUp(rules, calls, day, 1, departure, times))
            return true;
        return hold(rules, duty, calls, day, 1, arrives, now, times, set, false).isPresent();
    }

    /** The later calls of the duty at the station of call {@code call} that the set can be held until, in order. */
    private static List<Integer> laterCalls(List<Call> calls, int call) {
        List<Integer> later = new ArrayList<>();
        for (int to = call + 2; to < calls.size() - 1; to++)
            if (calls.get(to).station().equals(calls.get(call).station()))
                later.add(to);
        return later;
    }

    /**
     * Whether a set that leaves call {@code call} at {@code departure} makes up enough on the way
     * ({@link Forecast}) to be within the threshold when it next leaves the station of that call; false
     * if the duty does not come back to it.
     */
    private static boolean makesUp(Rules rules, List<Call> calls, long day, int call, long departure, RunTimes times) {
        List<Integer> later = laterCalls(calls, call);
        if (later.isEmpty())
            return false;
        int first = later.getFirst();
        long leaves = Forecast.leaves(calls, day, call, departure, first, times, rules.defaultMinimumStop());
        return leaves - DutyPlan.at(day, calls.get(first).depart()) <= rules.threshold();
    }

    /**
     * The first later call at the station that the set of {@code duty}, there from {@code arrived}, can
     * be held until; empty if there is none. The call has a train that carries passengers after it: a
     * set held only to run deadheads back to the depot has cancelled all its duty is for.
     *
     * @param now     the clock now
     * @param reach   the set's track and the trains around it, to keep it out of their way; null not to
     *                look
     * @param findWay whether to look for a way on from where the set stands now ({@code reach} has to be
     *                there then)
     */
    private static Optional<Hold> hold(Rules rules, String duty, List<Call> calls, long day, int call, long arrived, long now, RunTimes times,
                                       @Nullable Reach reach, boolean findWay) {
        long threshold = rules.threshold();
        String station = calls.get(call).station();
        for (int to : laterCalls(calls, call)) {
            if (keeps(rules.timetable(), calls, call, to))
                break; // and so would every later call
            if (!servedFrom(rules.timetable(), calls, to))
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
            return Optional.of(new Hold(to, leaves, DutyPlan.cancelledTrains(calls, skipped), DutyPlan.partlyCancelledTrains(calls, skipped)));
        }
        return Optional.empty();
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
     * train's way: no train runs through meanwhile, and there are platforms for it and for the other
     * sets there meanwhile. A set that is there now counts the trains standing there now, and the sets
     * that come to stand there later by the timetable. One that is to come later counts the sets the
     * timetable has there while it is, those come in already as well: trains there now may be gone
     * by then.
     */
    private static boolean roomFor(Rules rules, String duty, long day, String station, long from, long to, long now, Reach reach) {
        if (StationUse.passedBy(rules.timetable(), rules.calls(), rules.axes(), duty, day, station, from, to))
            return false;
        int others = from <= now
            ? reach.standing(station) + StationUse.mostStanding(rules.calls(), duty, day, station, from, to, now, rules.lead())
            : StationUse.mostStanding(rules.calls(), duty, day, station, from, to, Long.MIN_VALUE, rules.lead());
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
