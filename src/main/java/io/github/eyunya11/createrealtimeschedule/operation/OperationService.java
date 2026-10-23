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
import io.github.eyunya11.createrealtimeschedule.operation.DutyCalendar.Start;
import io.github.eyunya11.createrealtimeschedule.operation.TrainDriver.Outcome;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Duty;
import net.minecraft.server.MinecraftServer;

/**
 * Runs the timetable loaded with {@code /rttimetable load}, once a tick, on the server.
 *
 * <ol>
 * <li>Each running duty is taken one step on by {@link TrainDriver}: a set whose time has come is sent
 * to the next station, and one that has arrived is told when to leave. A duty whose train is gone,
 * derailed or given a schedule of Create's is given up. A set back at the end of its duty is free.</li>
 * <li>Once a second, the trains standing at a depot of the timetable with nowhere to go, no schedule
 * of Create's and no duty are counted as free, from the moment they are first seen so.</li>
 * <li>A duty is given a set {@code duty_lead_seconds} before it first leaves: of the free sets at its
 * depot, the one that has waited longest. Of duties due together, the one that leaves first chooses
 * first. A duty that has had no set by the time it was to leave does not run that day.</li>
 * </ol>
 *
 * <p>Trains that do not stand at a depot, and trains with a schedule of Create's, are never touched,
 * so a timetable can share the track with trains that follow Create's schedules.
 */
public final class OperationService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How often free sets are looked for, in ticks. */
    private static final int FREE_CHECK_TICKS = 20;

    /** The call at which each train was last found to have no way on, so that it is logged once a call. */
    private static final Map<UUID, Integer> NO_WAY = new HashMap<>();

    private OperationService() {}

    public static void tick(MinecraftServer server) {
        OperationData data = OperationData.get(server);
        Timetable timetable = data.timetable();
        if (timetable == null)
            return;
        long now = ClockService.now(server);
        stepRuns(data, now, ModSettings.defaultMinimumStopSeconds() * (long) RealClock.TICKS_PER_SECOND);
        if (server.getTickCount() % FREE_CHECK_TICKS == 0)
            findFreeSets(data, timetable, now);
        startDuties(data, timetable, now, ModSettings.dutyLeadSeconds() * (long) RealClock.TICKS_PER_SECOND);
    }

    private static void stepRuns(OperationData data, long now, long minimumStop) {
        Iterator<Map.Entry<UUID, DutyRun>> runs = data.runs().entrySet().iterator();
        while (runs.hasNext()) {
            Map.Entry<UUID, DutyRun> entry = runs.next();
            UUID id = entry.getKey();
            DutyRun run = entry.getValue();
            Train train = Create.RAILWAYS.trains.get(id);
            String letGo = TrainDriver.whyLetGo(train);
            if (letGo != null) {
                runs.remove();
                NO_WAY.remove(id);
                data.note(now, run.duty(), run.day(), "given up: " + letGo);
                continue;
            }
            int call = run.call();
            DutyRun.Phase phase = run.phase();
            Outcome outcome = TrainDriver.step(run, train, now, minimumStop);
            if (run.call() != call || run.phase() != phase)
                data.setDirty();
            switch (outcome) {
                case GOING -> NO_WAY.remove(id);
                case NO_WAY -> {
                    Integer logged = NO_WAY.put(id, run.call());
                    if (logged == null || logged != run.call())
                        LOGGER.warn("Duty {} of service day {}: {} finds no way to {}; it is tried again every tick",
                            run.duty(), run.day(), train.name.getString(), run.nextStation());
                }
                case FINISHED -> {
                    runs.remove();
                    NO_WAY.remove(id);
                    data.freeSince().put(id, now);
                    data.setDirty();
                    LOGGER.info("Duty {} of service day {} is done; {} is free at {}", run.duty(), run.day(), train.name.getString(),
                        run.calls().getLast().station());
                }
            }
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

    private static void startDuties(OperationData data, Timetable timetable, long now, long lead) {
        Map<String, Duty> byId = new HashMap<>();
        List<Start> due = new ArrayList<>();
        for (Duty duty : timetable.duties()) {
            byId.put(duty.id(), duty);
            List<Call> calls = data.calls(duty.id());
            due.addAll(DutyCalendar.due(duty.id(), DutyPlan.firstDeparture(calls), data.lastDay(duty.id()), now, lead));
        }
        if (due.isEmpty())
            return;
        due.sort(Comparator.comparingLong(Start::at).thenComparing(Start::duty));
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
            if (chosen.isEmpty())
                continue; // a set may come free before the duty is to leave
            UUID id = chosen.get();
            data.runs().put(id, DutyRun.start(duty.id(), start.day(), data.calls(duty.id()), now));
            data.freeSince().remove(id);
            data.dealtWith(duty.id(), start.day());
            data.setDirty();
            LOGGER.info("Duty {} of service day {} is given {}, to leave {} at {}", duty.id(), start.day(),
                Create.RAILWAYS.trains.get(id).name.getString(), duty.depot(), RealClock.format(start.at()));
        }
    }
}
