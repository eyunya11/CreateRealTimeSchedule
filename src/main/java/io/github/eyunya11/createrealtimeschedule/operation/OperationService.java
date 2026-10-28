package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.station.GlobalStation;

import io.github.eyunya11.createrealtimeschedule.ModSettings;
import io.github.eyunya11.createrealtimeschedule.clock.ClockService;
import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.diagram.DutyTimes;
import io.github.eyunya11.createrealtimeschedule.operation.DutyCalendar.Start;
import io.github.eyunya11.createrealtimeschedule.operation.TrainDriver.Outcome;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Duty;
import net.minecraft.server.MinecraftServer;

/**
 * Runs the timetable loaded with {@code /rttimetable load}, once a tick, on the server.
 *
 * <ol>
 * <li>Once a second, the trains standing at a depot of the timetable with nowhere to go, no schedule
 * of Create's and no duty are counted as free, from the moment they are first seen so; and the track
 * is looked at, so that what it says of the stations is worked out again after it has changed
 * ({@link TrackFacts#watch}).</li>
 * <li>A duty is given a set {@code duty_lead_seconds} before it first leaves: of the free sets at its
 * depot, the one that has waited longest. Of duties due together, the one that leaves first chooses
 * first. A duty that has had no set by the time it was to leave is given one late, after the duties
 * on time, while that still gets it back on time ({@link Recovery#canStartLate}); once it does not,
 * the duty does not run that day.</li>
 * <li>Each running duty is taken one step on by {@link TrainDriver}: a set whose time has come is sent
 * to the next station, one that has gone from the platform is running, and one that has arrived is
 * told when to leave. A set that has arrived later than {@code recovery_threshold_seconds} may be held
 * at the station until a later call there, and the trains in between cancelled ({@link Recovery}). A
 * duty whose train is gone, derailed or given a schedule of Create's is given up. A set back at the
 * end of its duty is free. Either way, the duty's times are kept for the diagram
 * ({@link OperationData#keep}). This comes after the duties are given their sets, so that one given
 * its set at its very time is sent on the same tick.</li>
 * </ol>
 *
 * <p>Trains that do not stand at a depot, and trains with a schedule of Create's, are never touched,
 * so a timetable can share the track with trains that follow Create's schedules.
 */
public final class OperationService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How often free sets are looked for, in ticks. */
    private static final int FREE_CHECK_TICKS = 20;

    private OperationService() {}

    public static void tick(MinecraftServer server) {
        OperationData data = OperationData.get(server);
        Timetable timetable = data.timetable();
        if (timetable == null)
            return;
        long now = ClockService.now(server);
        if (server.getTickCount() % FREE_CHECK_TICKS == 0) {
            findFreeSets(data, timetable, now);
            data.forgetOld(now);
            TrackFacts.watch(data, timetable, server.getTickCount());
        }
        long lead = ModSettings.dutyLeadSeconds() * (long) RealClock.TICKS_PER_SECOND;
        long defaultMinimumStop = ModSettings.defaultMinimumStopSeconds() * (long) RealClock.TICKS_PER_SECOND;
        long threshold = ModSettings.recoveryThresholdSeconds() * (long) RealClock.TICKS_PER_SECOND;
        startDuties(data, timetable, now, lead, defaultMinimumStop, threshold, server.getTickCount() % FREE_CHECK_TICKS == 0);
        watchPasses(data, timetable, now);
        stepRuns(data, timetable, now, defaultMinimumStop, threshold, lead, ModSettings.maxWaitSeconds() * (long) RealClock.TICKS_PER_SECOND);
    }

    /**
     * Records the stations sets run through that a train waits for them to (see {@link Waits}). This
     * comes before the sets are stepped, so that a set waiting for one sees it go past on the same tick.
     */
    private static void watchPasses(OperationData data, Timetable timetable, long now) {
        if (!data.hasWaits())
            return; // nor is the track read for them
        Waits waits = data.waits(TrackFacts.axes(data, timetable));
        Set<UUID> watched = new HashSet<>();
        for (Map.Entry<UUID, DutyRun> entry : data.runs().entrySet()) {
            DutyRun run = entry.getValue();
            Train train = Create.RAILWAYS.trains.get(entry.getKey());
            if (train == null || run.finished())
                continue;
            // The way it is on, and the one before: the end of a long train can still be short of a
            // station on that one when its front has come in at the next stop.
            int way = run.phase() == DutyRun.Phase.RUNNING ? run.call() - 1 : run.call();
            Map<Integer, Set<String>> ways = new HashMap<>();
            for (int each : new int[] {way, way - 1})
                if (each >= 0 && !waits.watched(run.duty(), each).isEmpty())
                    ways.put(each, waits.watched(run.duty(), each));
            if (ways.isEmpty())
                continue;
            watched.add(entry.getKey());
            Set<String> stations = new HashSet<>();
            ways.values().forEach(stations::addAll);
            for (String station : PassWatch.passed(data.tails().get(entry.getKey()), train, stations))
                ways.forEach((each, names) -> {
                    if (names.contains(station) && !run.passedBy(each, station)) {
                        run.passed(each, station, now);
                        data.setDirty();
                    }
                });
            PassWatch.Tail tail = PassWatch.tail(train);
            if (tail != null)
                data.tails().put(entry.getKey(), tail);
        }
        data.tails().keySet().retainAll(watched);
    }

    /**
     * Whether the set of {@code run}, due to leave its call, is still to wait for a train there (see
     * {@link Waits}): one it waits for that has not left the station or run through it yet, unless
     * that is past the longest wait, or is forecast to be. A wait given up so is noted, once, and so is
     * one that ended without the train being seen to run through the station.
     *
     * @param maxWait the longest wait, in ticks, of a wait that does not say
     */
    private static boolean waitsForATrain(OperationData data, Timetable timetable, DutyRun run, long now, long defaultMinimumStop,
                                          long maxWait) {
        Call call = run.calls().get(run.call());
        if (call.waits().isEmpty())
            return false;
        Waits waits = data.waits(TrackFacts.axes(data, timetable));
        for (int index = 0; index < call.waits().size(); index++) {
            Timetable.Wait wait = call.waits().get(index);
            Waits.Key about = new Waits.Key(run.duty(), run.day(), run.call(), index);
            Waits.Target target = waits.target(run.duty(), run.call(), index).orElse(null);
            if (target == null)
                continue; // a train in no duty does not run
            Map.Entry<UUID, DutyRun> theirs = runOf(data, target.duty(), run.day());
            Waits.State state = Waits.state(target, theirs == null ? null : theirs.getValue());
            if (state == Waits.State.OVER_UNSEEN && data.firstUnseen(about))
                data.note(now, run.duty(), run.day(), "train " + wait.train() + " came in after " + wait.at()
                    + " without being seen to run through it: the track has no station " + wait.at() + " on its way");
            if (state != Waits.State.WAITING || data.waitGivenUp(about))
                continue;
            long longest = run.departure() + (wait.hasMaxWait() ? wait.maxWait() * (long) RealClock.TICKS_PER_SECOND : maxWait);
            long forecast = Waits.forecast(target, theirs.getValue(), now, timesOf(data, timetable, theirs.getKey()), defaultMinimumStop);
            if (now > longest || forecast > longest) {
                data.giveUpWait(about);
                data.note(now, run.duty(), run.day(), "did not wait " + (now > longest ? "longer " : "") + "for train " + wait.train()
                    + " to leave " + wait.at() + " or run through it: it was " + (now > longest ? "not there by " : "forecast to be there at ")
                    + RealClock.format(now > longest ? longest : forecast));
                continue;
            }
            return true;
        }
        return false;
    }

    /** The train the set of {@code run} waits for now, due to leave its call; null if it waits for none. */
    public static @Nullable Timetable.Wait waitingFor(OperationData data, DutyRun run, long now) {
        Timetable timetable = data.timetable();
        Call call = run.calls().get(run.call());
        if (timetable == null || !run.dueToLeave(now) || call.waits().isEmpty())
            return null;
        Waits waits = data.waits(TrackFacts.axes(data, timetable));
        for (int index = 0; index < call.waits().size(); index++) {
            Waits.Target target = waits.target(run.duty(), run.call(), index).orElse(null);
            if (target == null || data.waitGivenUp(new Waits.Key(run.duty(), run.day(), run.call(), index)))
                continue;
            Map.Entry<UUID, DutyRun> theirs = runOf(data, target.duty(), run.day());
            if (Waits.state(target, theirs == null ? null : theirs.getValue()) == Waits.State.WAITING)
                return call.waits().get(index);
        }
        return null;
    }

    /** The train that runs duty {@code duty} on service {@code day}, with its run; null if none does. */
    private static @Nullable Map.Entry<UUID, DutyRun> runOf(OperationData data, String duty, long day) {
        for (Map.Entry<UUID, DutyRun> entry : data.runs().entrySet())
            if (entry.getValue().duty().equals(duty) && entry.getValue().day() == day)
                return entry;
        return null;
    }

    /**
     * How long the track takes for the train {@code id}, at its own speed and throttle; the least any
     * train takes if it is gone, or Create's config leaves it no speed.
     */
    private static RunTimes timesOf(OperationData data, Timetable timetable, UUID id) {
        Train train = Create.RAILWAYS.trains.get(id);
        Optional<CreateReach> reach = train == null ? Optional.empty() : CreateReach.of(train, TrackFacts.legs(data, timetable));
        return reach.isPresent() ? reach.get() : TrackFacts.fastestTimes(TrackFacts.legs(data, timetable));
    }

    private static void stepRuns(OperationData data, Timetable timetable, long now, long defaultMinimumStop, long threshold, long lead,
                                 long maxWait) {
        Iterator<Map.Entry<UUID, DutyRun>> runs = data.runs().entrySet().iterator();
        while (runs.hasNext()) {
            Map.Entry<UUID, DutyRun> entry = runs.next();
            UUID id = entry.getKey();
            DutyRun run = entry.getValue();
            Train train = Create.RAILWAYS.trains.get(id);
            String letGo = TrainDriver.whyLetGo(train);
            if (letGo != null) {
                runs.remove();
                data.noWay().remove(id);
                data.note(now, run.duty(), run.day(), "given up: " + letGo);
                data.keep(run, train == null ? "" : train.name.getString(), DutyTimes.Status.GIVEN_UP, now);
                continue;
            }
            int call = run.call();
            DutyRun.Phase phase = run.phase();
            boolean waiting = run.dueToLeave(now) && waitsForATrain(data, timetable, run, now, defaultMinimumStop, maxWait);
            Outcome outcome = waiting ? Outcome.GOING : TrainDriver.step(run, train, now, defaultMinimumStop);
            if (phase == DutyRun.Phase.RUNNING && run.phase() == DutyRun.Phase.STANDING)
                recover(data, timetable, run, train, now, defaultMinimumStop, threshold, lead);
            if (run.call() != call || run.phase() != phase) {
                data.timesChanged();
                data.setDirty();
            }
            switch (outcome) {
                case GOING -> data.noWay().remove(id);
                case NO_WAY -> {
                    Integer logged = data.noWay().put(id, run.call());
                    if (logged == null || logged != run.call())
                        LOGGER.warn("Duty {} of service day {}: {} finds no way to {}; it is tried again every tick",
                            run.duty(), run.day(), train.name.getString(), run.nextStation());
                }
                case FINISHED -> {
                    runs.remove();
                    data.noWay().remove(id);
                    data.freeSince().put(id, now);
                    data.keep(run, train.name.getString(), DutyTimes.Status.FINISHED, now);
                    LOGGER.info("Duty {} of service day {} is done; {} is free at {}", run.duty(), run.day(), train.name.getString(),
                        run.calls().getLast().station());
                }
            }
        }
    }

    /**
     * Holds a set that has just come in later than the threshold at a station its duty comes back to,
     * until a later call there, if it can be ({@link Recovery}); the trains in between are cancelled.
     */
    private static void recover(OperationData data, Timetable timetable, DutyRun run, Train train, long now, long defaultMinimumStop,
                                long threshold, long lead) {
        if (!Recovery.tooLate(run, threshold))
            return; // as most sets are: the track need not be looked at
        Optional<CreateReach> reach = CreateReach.of(train, TrackFacts.legs(data, timetable));
        if (reach.isEmpty())
            return;
        Recovery.Rules rules = new Recovery.Rules(timetable, data.allCalls(), TrackFacts.axes(data, timetable), threshold, defaultMinimumStop,
            lead);
        Recovery.onArrival(rules, run, now, reach.get()).ifPresent(hold -> {
            data.note(now, run.duty(), run.day(), Recovery.note(run, hold));
            run.hold(hold.to(), hold.departure());
        });
    }

    /** Whether the train can be given a duty from {@code depot}: it stands there, with no duty, no schedule of Create's and nowhere to go. */
    private static boolean freeAt(OperationData data, Train train, String depot) {
        return !data.runs().containsKey(train.id) && TrainDriver.whyLetGo(train) == null && TrainDriver.standsAt(train, depot);
    }

    private static void findFreeSets(OperationData data, Timetable timetable, long now) {
        Set<String> depots = new HashSet<>();
        for (Duty duty : timetable.duties())
            depots.add(duty.depot());
        Set<UUID> free = new HashSet<>();
        for (Train train : Create.RAILWAYS.trains.values()) {
            GlobalStation at = train.getCurrentStation();
            if (at != null && depots.contains(at.name) && freeAt(data, train, at.name))
                free.add(train.id);
        }
        boolean changed = data.freeSince().keySet().retainAll(free);
        for (UUID id : free)
            changed |= data.freeSince().putIfAbsent(id, now) == null;
        if (changed)
            data.setDirty();
    }

    /**
     * Gives the duties that are due their sets: those on time first, by when they leave, then those
     * whose time to leave has gone by (else these, due first, would take the sets first). A late duty
     * is given a set while that still gets it back on time, and does not run that day once it does
     * not. That is looked at only on the ticks {@code lateChecks} says, once a second, as it reads the
     * track and the trains. Until a lead before the next start of any duty, which is worked out again
     * only once that comes or the duties change, there is nothing to do.
     */
    private static void startDuties(OperationData data, Timetable timetable, long now, long lead, long defaultMinimumStop, long threshold,
                                    boolean lateChecks) {
        if (now + lead < data.nextStart())
            return;
        Map<String, Duty> byId = new HashMap<>();
        List<Start> due = new ArrayList<>();
        for (Duty duty : timetable.duties()) {
            byId.put(duty.id(), duty);
            List<Call> calls = data.calls(duty.id());
            due.addAll(DutyCalendar.due(duty.id(), DutyPlan.firstDeparture(calls), data.lastDay(duty.id()), now, lead));
        }
        due.sort(Comparator.comparing((Start start) -> start.missedBy(now)).thenComparingLong(Start::at).thenComparing(Start::duty));
        boolean waiting = false;
        Recovery.Rules rules = null; // worked out for the first late duty, as it reads the track
        RunTimes fastest = RunTimes.NONE; // the least any set takes: whether one that comes free can still start the duty
        for (Start start : due) {
            Duty duty = byId.get(start.duty());
            List<Call> calls = data.calls(duty.id());
            boolean late = start.missedBy(now);
            if (late && threshold > 0 && !lateChecks) {
                waiting = true;
                continue;
            }
            if (late && threshold > 0 && rules == null) {
                rules = new Recovery.Rules(timetable, data.allCalls(), TrackFacts.axes(data, timetable), threshold, defaultMinimumStop, lead);
                fastest = TrackFacts.fastestTimes(TrackFacts.legs(data, timetable));
            }
            if (late && (rules == null || !DutyCalendar.mayStartLate(start.day(), DutyPlan.firstDeparture(calls), now, lead)
                || !Recovery.canStartLate(rules, duty.id(), calls, start.day(), now, fastest, null))) {
                data.dealtWith(duty.id(), start.day());
                // One that was to start before the timetable was loaded is no duty that did not run.
                if (start.at() >= data.loadedAt(duty.id()))
                    data.note(now, duty.id(), start.day(), "did not run: no free set at " + duty.depot() + " by its time"
                        + (threshold > 0 ? ", nor in time to start late" : ""));
                continue;
            }
            Map<UUID, Long> candidates = new HashMap<>();
            data.freeSince().forEach((id, since) -> {
                Train train = Create.RAILWAYS.trains.get(id);
                if (train != null && freeAt(data, train, duty.depot()))
                    candidates.put(id, since);
            });
            Optional<UUID> chosen = DutyCalendar.longestWaiting(candidates);
            if (chosen.isEmpty()) {
                waiting = true; // a set may come free before the duty is to leave, or while it can still start late
                continue;
            }
            UUID id = chosen.get();
            Train set = Create.RAILWAYS.trains.get(id);
            if (late) {
                Optional<CreateReach> reach = CreateReach.of(set, TrackFacts.legs(data, timetable));
                if (reach.isEmpty() || !Recovery.canStartLate(rules, duty.id(), calls, start.day(), now, reach.get(), reach.get())) {
                    waiting = true; // where this set would have to be held it would be in the way; perhaps later
                    continue;
                }
            }
            data.runs().put(id, DutyRun.start(duty.id(), start.day(), calls, now));
            data.timesChanged();
            data.freeSince().remove(id);
            data.dealtWith(duty.id(), start.day());
            data.setDirty();
            if (late)
                data.note(now, duty.id(), start.day(), "started late: " + (start.at() >= data.loadedAt(duty.id())
                    ? "no free set at " + duty.depot() + " by " + RealClock.format(start.at())
                    : "the timetable was loaded after " + RealClock.format(start.at())));
            LOGGER.info("Duty {} of service day {} is given {}, to leave {} at {}", duty.id(), start.day(), set.name.getString(),
                duty.depot(), RealClock.format(start.at()));
        }
        // A duty still waiting for a set is looked at again on the next tick; otherwise not until the next start.
        long next = Long.MAX_VALUE;
        if (waiting)
            next = Long.MIN_VALUE;
        else
            for (Duty duty : timetable.duties())
                next = Math.min(next, DutyCalendar.nextStart(DutyPlan.firstDeparture(data.calls(duty.id())), data.lastDay(duty.id()), now));
        data.nextStart(next);
    }
}
