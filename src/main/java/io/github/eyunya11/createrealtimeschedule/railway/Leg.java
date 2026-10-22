package io.github.eyunya11.createrealtimeschedule.railway;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import io.github.eyunya11.createrealtimeschedule.travel.SpeedZone;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpec;
import io.github.eyunya11.createrealtimeschedule.travel.TravelTime;

/**
 * The way from one station to another along the shortest route a train can take, with nothing
 * from the game in it.
 *
 * @param from     the name of the station the train starts at, standing at its stop marker
 * @param to       the name of the station it stops at
 * @param distance the length of the route in blocks, from one marker to the other
 * @param curves   the stretches of the route that are curves, as distances from the start
 * @param via      the names of the other stations the train passes on the way, in order, each once
 */
public record Leg(String from, String to, double distance, List<Curve> curves, List<String> via) {

    /** A curve from {@code start} to {@code end}, in blocks from the start of the route. */
    public record Curve(double start, double end) {}

    public Leg {
        curves = List.copyOf(curves);
        via = List.copyOf(via);
    }

    /**
     * How many ticks a train of this spec takes for the leg at this throttle, running without a stop
     * in between; empty if it cannot get there (see {@link TravelTime#ticks}). Signals and other
     * trains are not in it.
     */
    public OptionalInt ticks(TrainSpec spec, double throttle) {
        List<SpeedZone> zones = new ArrayList<>();
        for (Curve curve : curves)
            zones.add(SpeedZone.curve(curve.start(), curve.end(), spec));
        return TravelTime.ticks(spec, throttle, distance, zones);
    }
}
