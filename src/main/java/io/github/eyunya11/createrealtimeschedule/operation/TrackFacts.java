package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Supplier;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.station.GlobalStation;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.eyunya11.createrealtimeschedule.ModSettings;
import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.diagram.LineAxis;
import io.github.eyunya11.createrealtimeschedule.railway.CreateNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTagData;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTags;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
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
     * Worked out the first time it is needed after the ways are, and kept with them: for a day of a
     * train a minute, it takes about a second. A timetable that holds too many trains up for too long to
     * be worked out ({@link Traffic#MAX_STEPS}) is taken to have the signals hold no train up, and the log
     * says so.
     */
    public static TimetableTraffic traffic(OperationData data, Timetable timetable) {
        workOut(data, timetable);
        TimetableTraffic known = data.traffic();
        if (known != null)
            return known;
        TrainSpec spec = null;
        for (Supplier<TrainSpec> each : List.<Supplier<TrainSpec>>of(TrainSpecs::unpowered, TrainSpecs::powered)) {
            try {
                spec = each.get();
                break;
            } catch (IllegalArgumentException e) {
                // no train of that kind moves
            }
        }
        TimetableTraffic traffic = TimetableTraffic.NONE;
        if (spec != null && data.blocks().count() > 0) {
            long started = System.nanoTime();
            traffic = TimetableTraffic.of(timetable, data.legs(), data.blocks(), spec,
                ModSettings.defaultMinimumStopSeconds() * (long) RealClock.TICKS_PER_SECOND, ModSettings.trainLengthBlocks());
            long ms = (System.nanoTime() - started) / 1_000_000;
            if (traffic.finished())
                LOGGER.info("Ran the trains of the timetable with the signals in {} ms", ms);
            else
                LOGGER.warn("The trains of the timetable hold one another up at the signals too much to work out in time ({} ms); "
                    + "the forecasts leave the signals out", ms);
        }
        data.traffic(traffic);
        return traffic;
    }

    /** {@code times}, with what the signals add to the ways of the trains of the timetable, running on time ({@link #traffic}). */
    public static RunTimes withSignals(OperationData data, Timetable timetable, RunTimes times) {
        return RunTimes.withSignals(times, traffic(data, timetable));
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
