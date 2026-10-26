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
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpecs;
import net.minecraft.server.MinecraftServer;

/**
 * Runs the timetable loaded with {@code /rttimetable load}, once a tick, on the server.
 *
 * <ol>
 * <li>Once a second, the trains standing at a depot of the timetable with nowhere to go, no schedule
 * of Create's and no duty are counted as free, from the moment they are first seen so.</li>
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
        }
        long lead = ModSettings.dutyLeadSeconds() * (long) RealClock.TICKS_PER_SECOND;
        long defaultMinimumStop = ModSettings.defaultMinimumStopSeconds() * (long) RealClock.TICKS_PER_SECOND;
        long threshold = ModSettings.recoveryThresholdSeconds() * (long) RealClock.TICKS_PER_SECOND;
        startDuties(data, timetable, now, lead, defaultMinimumStop, threshold, server.getTickCount() % FREE_CHECK_TICKS == 0);
        watchPasses(data, now);
        stepRuns(data, timetable, now, defaultMinimumStop, threshold, lead, ModSettings.maxWaitSeconds() * (long) RealClock.TICKS_PER_SECOND);
    }

    /**
     * Records the stations sets run through that a train waits for them to (see {@link Waits}). This
     * comes before the sets are stepped, so that a set waiting for one sees it go past on the same tick.
     */
    private static void watchPasses(OperationData data, long now) {
        Waits waits = data.waits();
        if (waits == null)
            return;
        Set<UUID> watched = new HashSet<>();
        for (Map.Entry<UUID, DutyRun> entry : data.runs().entrySet()) {
            DutyRun run = entry.getValue();
            Train train = Create.RAILWAYS.trains.get(entry.getKey());
            if (train == null || (run.phase() != DutyRun.Phase.RUNNING && run.phase() != DutyRun.Phase.DEPARTING))
                continue;
            int way = run.phase() == DutyRun.Phase.RUNNING ? run.call() - 1 : run.call();
            Set<String> stations = waits.watched(run.duty(), way);
            if (stations.isEmpty())
                continue;
            watched.add(entry.getKey());
            for (String station : PassWatch.passed(data.tails().get(entry.getKey()), train, stations))
                if (!run.passedBy(way, station)) {
                    run.passed(way, station, now);
                    data.setDirty();
                }
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
        Waits waits = data.waits();
        Call call = run.calls().get(run.call());
        if (waits == null || call.waits().isEmpty())
            return false;
        RunTimes times = null;
        for (int index = 0; index < call.waits().size(); index++) {
            Timetable.Wait wait = call.waits().get(index);
            String about = run.duty() + "/" + run.day() + "/" + run.call() + "/" + index;
            Waits.Target target = waits.target(call, wait).orElse(null);
            if (target == null)
                continue; // a train in no duty does not run
            DutyRun theirs = runOf(data, target.duty(), run.day());
            Waits.State state = Waits.state(target, theirs);
            if (state == Waits.State.OVER_UNSEEN && data.firstUnseen(about))
                data.note(now, run.duty(), run.day(), "train " + wait.train() + " came in after " + wait.at()
                    + " without being seen to run through it: the track has no station " + wait.at() + " on its way");
            if (state != Waits.State.WAITING || data.waitGivenUp(about))
                continue;
            long longest = run.departure() + (wait.hasMaxWait() ? wait.maxWait() * (long) RealClock.TICKS_PER_SECOND : maxWait);
            if (times == null)
                times = unpoweredTimes(data, timetable);
            long forecast = Waits.forecast(target, theirs, now, times, defaultMinimumStop);
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
        Waits waits = data.waits();
        if (waits == null || !run.dueToLeave(now))
            return null;
        Call call = run.calls().get(run.call());
        for (int index = 0; index < call.waits().size(); index++) {
            Timetable.Wait wait = call.waits().get(index);
            Waits.Target target = waits.target(call, wait).orElse(null);
            if (target != null && !data.waitGivenUp(run.duty() + "/" + run.day() + "/" + run.call() + "/" + index)
                && Waits.state(target, runOf(data, target.duty(), run.day())) == Waits.State.WAITING)
                return wait;
        }
        return null;
    }

    /** The run of {@code duty} on service {@code day} that is going on; null if none is. */
    private static @Nullable DutyRun runOf(OperationData data, String duty, long day) {
        for (DutyRun run : data.runs().values())
            if (run.duty().equals(duty) && run.day() == day)
                return run;
        return null;
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

    /** How long the track takes for a train without fuel, as the network's export has it; the timetable's times if Create's config leaves it no speed. */
    private static RunTimes unpoweredTimes(OperationData data, Timetable timetable) {
        try {
            return TrackFacts.runTimes(TrackFacts.legs(data, timetable), TrainSpecs.unpowered(), 1);
        } catch (IllegalArgumentException e) {
            return RunTimes.NONE;
        }
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
        RunTimes unpowered = RunTimes.NONE;
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
                unpowered = unpoweredTimes(data, timetable);
            }
            if (late && (rules == null || !DutyCalendar.mayStartLate(start.day(), DutyPlan.firstDeparture(calls), now, lead)
                || !Recovery.canStartLate(rules, duty.id(), calls, start.day(), now, unpowered, null))) {
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
