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
 * network, and {@link OperationData} keeps them until the timetable or the track changes
 * ({@link OperationData#forgetAxes}).
 */
public final class TrackFacts {

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

    private static void workOut(OperationData data, Timetable timetable) {
        if (data.axes() != null && data.legs() != null)
            return;
        Set<String> stations = new HashSet<>();
        for (Line line : timetable.lines())
            stations.addAll(line.stations());
        List<Leg> legs = CreateNetwork.read(Create.RAILWAYS.trackNetworks.values()).legs(stations);
        Map<String, LineAxis> axes = new LinkedHashMap<>();
        for (Line line : timetable.lines())
            axes.put(line.id(), LineAxis.of(line, legs));
        Map<List<String>, Leg> byEnds = new HashMap<>();
        for (Leg leg : legs)
            byEnds.putIfAbsent(List.of(leg.from(), leg.to()), leg);
        data.track(axes, byEnds);
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
