package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Supplier;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.station.GlobalStation;

import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.diagram.LineAxis;
import io.github.eyunya11.createrealtimeschedule.railway.CreateNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Line;
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

    /** How long the track has to stay as it is after it has changed before the facts are worked out again, in ticks. */
    public static final int SETTLE_TICKS = 5 * RealClock.TICKS_PER_SECOND;

    private TrackFacts() {}

    /** The axis of each line of the timetable, by its id. */
    public static Map<String, LineAxis> axes(OperationData data, Timetable timetable) {
        workOut(data, timetable);
        return data.axes();
    }

    /** The ways between the timetable's stations, by their two ends, from and to. */
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
        List<Leg> legs = CreateNetwork.read(graphs).legs(stationsOf(timetable));
        Map<String, LineAxis> axes = new LinkedHashMap<>();
        for (Line line : timetable.lines())
            axes.put(line.id(), LineAxis.of(line, legs));
        Map<List<String>, Leg> byEnds = new HashMap<>();
        for (Leg leg : legs)
            byEnds.putIfAbsent(List.of(leg.from(), leg.to()), leg);
        data.track(axes, byEnds);
        data.trackWatch().workedOut(print);
        // The end of a train copied on an edge that is gone is not run on from; it is taken again from where the train is.
        data.tails().entrySet().removeIf(entry -> {
            Train train = Create.RAILWAYS.trains.get(entry.getKey());
            return train == null || !PassWatch.stillOn(entry.getValue(), train.graph);
        });
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

    /** How long the track takes between two stations of {@code legs}, for a train of {@code spec} at {@code throttle}. */
    public static RunTimes runTimes(Map<List<String>, Leg> legs, TrainSpec spec, double throttle) {
        return (from, to) -> {
            Leg leg = legs.get(List.of(from, to));
            if (leg == null)
                return OptionalLong.empty();
            OptionalInt ticks = leg.ticks(spec, throttle);
            return ticks.isPresent() ? OptionalLong.of(ticks.getAsInt()) : OptionalLong.empty();
        };
    }
}
