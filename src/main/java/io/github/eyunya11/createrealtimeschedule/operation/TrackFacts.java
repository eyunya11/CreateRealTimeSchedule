package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.station.GlobalStation;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.eyunya11.createrealtimeschedule.ModSettings;
import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.diagram.LineAxis;
import io.github.eyunya11.createrealtimeschedule.railway.CreateNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.SignalBlocks;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTagData;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTags;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Duty;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Line;
import io.github.eyunya11.createrealtimeschedule.traffic.Traffic;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpec;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpecs;

/**
 * What the track says of the timetable's stations: the ways between them ({@link Leg}), and the axis
 * of each line that the diagram draws from them. Both are worked out from Create's track the first
 * time either is needed, searching from the lines' stations only and not from every station of the
 * network, and {@link OperationData} keeps them until the timetable changes
 * ({@link OperationData#forgetAxes}), or the track does ({@link #watch}).
 */
public final class TrackFacts {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * The thread the trains of the timetable are run with the signals on, one run at a time, so that the
     * server's ticks do not wait for it ({@link #traffic}).
     */
    private static final ExecutorService TRAFFIC = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "CreateRealTimeSchedule traffic");
        thread.setDaemon(true);
        return thread;
    });

    /** How long the track has to stay as it is after it has changed before the facts are worked out again, in ticks. */
    public static final int SETTLE_TICKS = 5 * RealClock.TICKS_PER_SECOND;

    private TrackFacts() {}

    /** The axis of each line of the timetable, by its id. */
    public static Map<String, LineAxis> axes(OperationData data, Timetable timetable) {
        workOut(data, timetable);
        return data.axes();
    }

    /**
     * The ways between the timetable's stations, by their two ends, from and to, and the track tag a
     * train is held to on them ({@code ""} for none): for each tag the timetable holds a train to.
     */
    public static Map<List<String>, Leg> legs(OperationData data, Timetable timetable) {
        workOut(data, timetable);
        return data.legs();
    }

    /**
     * Works the facts out again if the track has changed since they were, once it has stayed as it is
     * for {@link #SETTLE_TICKS} ({@link TrackWatch}): not while a player is still laying it, as each time
     * reads the network. Called once a second, at server tick {@code tick}. Facts that have not been
     * worked out yet are left until they are first needed, when they are read from the track as it is.
     */
    public static void watch(OperationData data, Timetable timetable, int tick) {
        if (data.axes() == null || data.legs() == null)
            return;
        if (data.trackWatch().settledOnAChange(CreateNetwork.fingerprint(graphsOf(timetable)), tick))
            read(data, timetable);
    }

    /**
     * The track networks that have a station of the timetable's lines: the facts come from these alone,
     * so that track laid on a network of none of them is not read, nor watched.
     */
    private static List<TrackGraph> graphsOf(Timetable timetable) {
        Set<String> stations = stationsOf(timetable);
        List<TrackGraph> graphs = new ArrayList<>();
        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values())
            for (GlobalStation station : graph.getPoints(EdgePointType.STATION))
                if (stations.contains(station.name)) {
                    graphs.add(graph);
                    break;
                }
        return graphs;
    }

    private static Set<String> stationsOf(Timetable timetable) {
        Set<String> stations = new HashSet<>();
        for (Line line : timetable.lines())
            stations.addAll(line.stations());
        return stations;
    }

    private static void workOut(OperationData data, Timetable timetable) {
        if (data.axes() == null || data.legs() == null)
            read(data, timetable);
    }

    private static void read(OperationData data, Timetable timetable) {
        List<TrackGraph> graphs = graphsOf(timetable);
        long print = CreateNetwork.fingerprint(graphs);
        RailNetwork network = CreateNetwork.read(graphs);
        Set<String> stations = stationsOf(timetable);
        // The axes are drawn from the ways of no tag.
        Map<String, RailNetwork.Routes> routes = new LinkedHashMap<>();
        routes.put("", network.routes(stations, OptionalInt.empty()));
        TrackTags tags = TrackTagData.current();
        for (String tag : TagWays.tags(timetable))
            routes.put(tag, network.routes(stations, tags.held(tag)));
        Map<String, LineAxis> axes = new LinkedHashMap<>();
        for (Line line : timetable.lines())
            axes.put(line.id(), LineAxis.of(line, routes.get("").legs()));
        Map<List<String>, Leg> byEnds = new HashMap<>();
        for (Map.Entry<String, RailNetwork.Routes> tag : routes.entrySet())
            for (Leg leg : tag.getValue().legs())
                byEnds.putIfAbsent(List.of(leg.from(), leg.to(), tag.getKey()), leg);
        data.track(axes, byEnds, network.blocks(), TagWays.of(timetable, track(network, routes)), TagWays.unknown(timetable, tags.names().values()));
        data.trackWatch().workedOut(print);
        // The end of a train copied on an edge that is gone is not run on from; it is taken again from where the train is.
        data.tails().entrySet().removeIf(entry -> {
            Train train = Create.RAILWAYS.trains.get(entry.getKey());
            return train == null || !PassWatch.stillOn(entry.getValue(), train.graph);
        });
    }

    /** The platforms of {@code network} and the ways from each, on each tag of {@code routes}. */
    private static TagWays.Track track(RailNetwork network, Map<String, RailNetwork.Routes> routes) {
        Map<String, List<Integer>> platforms = new HashMap<>();
        for (int i = 0; i < network.stations().size(); i++)
            platforms.computeIfAbsent(network.stations().get(i).name(), name -> new ArrayList<>()).add(i);
        Map<String, Map<Integer, Map<String, Integer>>> byTag = new HashMap<>();
        for (Map.Entry<String, RailNetwork.Routes> tag : routes.entrySet()) {
            Map<Integer, Map<String, Integer>> from = new HashMap<>();
            for (RailNetwork.Way way : tag.getValue().ways())
                from.computeIfAbsent(way.from(), f -> new HashMap<>()).put(way.to(), way.station());
            byTag.put(tag.getKey(), from);
        }
        return new TagWays.Track() {
            @Override
            public List<Integer> platforms(String station) {
                return platforms.getOrDefault(station, List.of());
            }

            @Override
            public int way(int platform, String station, String tag) {
                return byTag.getOrDefault(tag, Map.of()).getOrDefault(platform, Map.of()).getOrDefault(station, -1);
            }
        };
    }

    /**
     * The trains of the timetable run on time together with the signals on the ways between its
     * stations ({@link TimetableTraffic}), for a train without fuel at full throttle, as long as the
     * server's config makes one, and of the length the config gives where the timetable gives none.
     * Worked out the first time it is needed after the ways are, on a thread of its own, as for a day of
     * a train a minute it takes about a second: until it is done, the signals hold no train up
     * ({@link TimetableTraffic#NONE}). Then it is kept with the ways, while the config's minimum stop and
     * train length stay as they are; a run for ways or a timetable that has changed since it started is
     * let go. A timetable that holds too many trains up for too long to be worked out
     * ({@link Traffic#MAX_STEPS}) is taken to have the signals hold no train up, and the log says so.
     */
    public static TimetableTraffic traffic(OperationData data, Timetable timetable) {
        workOut(data, timetable);
        List<Object> key = trafficKey();
        long minimumStop = (long) key.get(0);
        int length = (int) key.get(1);
        TimetableTraffic known = data.traffic(key);
        if (known != null)
            return known;
        CompletableFuture<TimetableTraffic> pending = data.pendingTraffic(key);
        if (pending != null) {
            if (!pending.isDone())
                return TimetableTraffic.NONE;
            TimetableTraffic done = pending.join();
            data.traffic(done, key);
            return done;
        }
        TrainSpec spec = onTimeSpec();
        if (spec == null || data.blocks().count() == 0) {
            data.traffic(TimetableTraffic.NONE, key);
            return TimetableTraffic.NONE;
        }
        // All that the run reads is taken here, on the server's thread: the timetable, ways and blocks are not changed after.
        Map<List<String>, Leg> legs = data.legs();
        SignalBlocks blocks = data.blocks();
        TrainSpec runSpec = spec;
        data.pendingTraffic(CompletableFuture.supplyAsync(() -> run(timetable, legs, blocks, runSpec, minimumStop, length), TRAFFIC), key);
        return TimetableTraffic.NONE;
    }

    /** How fast the trains on time are run with the signals: a train without fuel, or with if none without moves; null if none moves. */
    private static @Nullable TrainSpec onTimeSpec() {
        for (Supplier<TrainSpec> each : List.<Supplier<TrainSpec>>of(TrainSpecs::unpowered, TrainSpecs::powered)) {
            try {
                return each.get();
            } catch (IllegalArgumentException e) {
                // no train of that kind moves
            }
        }
        return null;
    }

    /**
     * The sets of the duties as last run on from where they were with the signals ({@link #runLive}): a
     * run done since is taken, and the next started if a set has come in while it was going.
     * {@link LiveTraffic#NONE} until one is done, or once the ways between stations, the timetable or the
     * server's config has changed since.
     */
    public static LiveTraffic live(OperationData data, Timetable timetable, long now) {
        List<Object> key = trafficKey();
        CompletableFuture<LiveTraffic> pending = data.pendingLive(key);
        if (pending != null && pending.isDone()) {
            data.live(pending.join(), key);
            if (data.liveWanted())
                runLive(data, timetable, now);
        }
        return data.live(key);
    }

    /**
     * Starts running the sets of the duties on from where they are at {@code now} with the signals
     * ({@link LiveTraffic}), with the duties that are to start on time within the hour, on the thread the
     * trains of the timetable are run on: unless such a run is going, when the next is started once it is
     * done ({@link #live}). A set at its own speed and throttle; one that cannot move is left out, as are
     * the trains of no duty. Nothing is run before the ways between stations are worked out, nor on a
     * network without signals.
     */
    public static void runLive(OperationData data, Timetable timetable, long now) {
        List<Object> key = trafficKey();
        CompletableFuture<LiveTraffic> pending = data.pendingLive(key);
        if (pending != null && !pending.isDone()) {
            data.liveWanted(true);
            return;
        }
        TrainSpec spec = onTimeSpec();
        if (data.legs() == null || data.blocks().count() == 0 || spec == null)
            return;
        // All that the run reads is taken here, on the server's thread.
        List<LiveTraffic.Running> running = new ArrayList<>();
        for (Map.Entry<UUID, DutyRun> entry : data.runs().entrySet()) {
            DutyRun run = entry.getValue();
            Train train = Create.RAILWAYS.trains.get(entry.getKey());
            if (train == null || run.finished())
                continue;
            try {
                running.add(LiveTraffic.Running.of(run, now, TrainSpecs.of(train), train.throttle));
            } catch (IllegalArgumentException e) {
                // Create's config leaves it no speed
            }
        }
        List<LiveTraffic.Coming> coming = new ArrayList<>();
        for (Duty duty : timetable.duties()) {
            List<Call> calls = data.calls(duty.id());
            if (calls == null)
                continue;
            for (DutyCalendar.Start start : DutyCalendar.due(duty.id(), DutyPlan.firstDeparture(calls), data.lastDay(duty.id()), now,
                LiveTraffic.HORIZON_TICKS))
                if (!start.missedBy(now))
                    coming.add(new LiveTraffic.Coming(duty.id(), start.day(), calls));
        }
        Map<List<String>, Leg> legs = data.legs();
        SignalBlocks blocks = data.blocks();
        long minimumStop = (long) key.get(0);
        int length = (int) key.get(1);
        // Cancelled when it is let go (OperationData#pendingLive), the run gives up.
        CompletableFuture<LiveTraffic> job = new CompletableFuture<>();
        TRAFFIC.execute(() -> {
            try {
                LiveTraffic live = LiveTraffic.of(timetable, running, coming, legs, blocks, spec, minimumStop, length, now, job::isCancelled);
                if (!live.finished() && !job.isCancelled())
                    LOGGER.warn("The sets of the duties hold one another up at the signals too much to work out in time; "
                        + "the forecasts take the signals as for the trains on time");
                job.complete(live);
            } catch (RuntimeException e) {
                LOGGER.error("Could not run the sets of the duties on with the signals; the forecasts take the signals as for the trains on time", e);
            } finally {
                job.complete(LiveTraffic.unknown(now));
            }
        });
        data.pendingLive(job, key);
    }

    /** {@link TimetableTraffic#of}, timed for the log; {@link TimetableTraffic#NONE} if it fails. */
    private static TimetableTraffic run(Timetable timetable, Map<List<String>, Leg> legs, SignalBlocks blocks, TrainSpec spec,
        long minimumStop, int length) {
        try {
            long started = System.nanoTime();
            TimetableTraffic traffic = TimetableTraffic.of(timetable, legs, blocks, spec, minimumStop, length);
            long ms = (System.nanoTime() - started) / 1_000_000;
            if (traffic.finished())
                LOGGER.info("Ran the trains of the timetable with the signals in {} ms", ms);
            else
                LOGGER.warn("The trains of the timetable hold one another up at the signals too much to work out in time ({} ms); "
                    + "the forecasts leave the signals out", ms);
            return traffic;
        } catch (RuntimeException e) {
            LOGGER.error("Could not run the trains of the timetable with the signals; the forecasts leave the signals out", e);
            return TimetableTraffic.NONE;
        }
    }

    /**
     * What of the server's config {@link #traffic} is worked out with: the minimum stop in ticks where the
     * timetable gives none, and the length of a train where it gives none.
     */
    public static List<Object> trafficKey() {
        return List.of(ModSettings.defaultMinimumStopSeconds() * (long) RealClock.TICKS_PER_SECOND, ModSettings.trainLengthBlocks());
    }

    /**
     * {@code times}, with what the signals add to the ways of the trains of the timetable, running on time
     * ({@link #traffic}): looked up each time, so that times kept for a train take the run once it is done.
     */
    public static RunTimes withSignals(OperationData data, Timetable timetable, RunTimes times) {
        return RunTimes.withSignals(times, () -> traffic(data, timetable));
    }

    /**
     * The least time the track takes between two stations of {@code legs} for any train: with fuel or
     * without, at full throttle, whichever is the faster. A kind of train that Create's config leaves no
     * speed is left out; with neither, nothing is known of the track and the timetable's times are taken.
     */
    public static RunTimes fastestTimes(Map<List<String>, Leg> legs) {
        List<RunTimes> times = new ArrayList<>();
        for (Supplier<TrainSpec> spec : List.<Supplier<TrainSpec>>of(TrainSpecs::powered, TrainSpecs::unpowered)) {
            try {
                times.add(runTimes(legs, spec.get(), 1));
            } catch (IllegalArgumentException e) {
                // no train of that kind moves
            }
        }
        return RunTimes.fastest(times);
    }

    /**
     * How long the track takes between two stations of {@code legs} for {@code train}, at its own speed
     * (with fuel or without, as it is now) and throttle; the least any train takes if Create's config
     * leaves it no speed.
     */
    public static RunTimes timesOf(Train train, Map<List<String>, Leg> legs) {
        try {
            return runTimes(legs, TrainSpecs.of(train), train.throttle);
        } catch (IllegalArgumentException e) {
            return fastestTimes(legs);
        }
    }

    /**
     * How long the track takes between two stations of {@code legs} (by their two ends and the track
     * tag, as {@link #legs} has them), for a train of {@code spec} at {@code throttle}.
     */
    public static RunTimes runTimes(Map<List<String>, Leg> legs, TrainSpec spec, double throttle) {
        return RunTimes.ofLegs(legs, spec, throttle);
    }
}
