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
 * of Create's and no duty are counted as free, from the moment they are first seen so.</li>
 * <li>A duty is given a set {@code duty_lead_seconds} before it first leaves: of the free sets at its
 * depot, the one that has waited longest. Of duties due together, the one that leaves first chooses
 * first. A duty that has had no set by the time it was to leave does not run that day.</li>
 * <li>Each running duty is taken one step on by {@link TrainDriver}: a set whose time has come is sent
 * to the next station, one that has gone from the platform is running, and one that has arrived is
 * told when to leave. A duty whose train is gone, derailed or given a schedule of Create's is given
 * up. A set back at the end of its duty is free. Either way, the duty's times are kept for the
 * diagram ({@link OperationData#keep}). This comes after the duties are given their sets, so
 * that one given its set at its very time is sent on the same tick.</li>
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
        startDuties(data, timetable, now, lead);
        stepRuns(data, timetable, now, defaultMinimumStop, threshold, lead);
    }

    private static void stepRuns(OperationData data, Timetable timetable, long now, long defaultMinimumStop, long threshold, long lead) {
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
            Outcome outcome = TrainDriver.step(run, train, now, defaultMinimumStop);
            if (threshold > 0 && phase == DutyRun.Phase.RUNNING && run.phase() == DutyRun.Phase.STANDING)
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
        if (run.departure() - run.plannedDeparture(run.call()) <= threshold)
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
     * Gives the duties that are due their sets. Until a lead before the next start of any duty, which
     * is worked out again only once that comes or the duties change, there is nothing to do.
     */
    private static void startDuties(OperationData data, Timetable timetable, long now, long lead) {
        if (now + lead < data.nextStart())
            return;
        Map<String, Duty> byId = new HashMap<>();
        List<Start> due = new ArrayList<>();
        for (Duty duty : timetable.duties()) {
            byId.put(duty.id(), duty);
            List<Call> calls = data.calls(duty.id());
            due.addAll(DutyCalendar.due(duty.id(), DutyPlan.firstDeparture(calls), data.lastDay(duty.id()), now, lead));
        }
        due.sort(Comparator.comparingLong(Start::at).thenComparing(Start::duty));
        boolean waiting = false;
        for (Start start : due) {
            Duty duty = byId.get(start.duty());
            if (start.missedBy(now)) {
                data.dealtWith(duty.id(), start.day());
                data.note(now, duty.id(), start.day(), "did not run: no free set at " + duty.depot() + " by its time");
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
                waiting = true; // a set may come free before the duty is to leave
                continue;
            }
            UUID id = chosen.get();
            data.runs().put(id, DutyRun.start(duty.id(), start.day(), data.calls(duty.id()), now));
            data.timesChanged();
            data.freeSince().remove(id);
            data.dealtWith(duty.id(), start.day());
            data.setDirty();
            LOGGER.info("Duty {} of service day {} is given {}, to leave {} at {}", duty.id(), start.day(),
                Create.RAILWAYS.trains.get(id).name.getString(), duty.depot(), RealClock.format(start.at()));
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
