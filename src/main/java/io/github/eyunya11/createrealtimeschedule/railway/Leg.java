package io.github.eyunya11.createrealtimeschedule.railway;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import io.github.eyunya11.createrealtimeschedule.travel.SpeedSign;
import io.github.eyunya11.createrealtimeschedule.travel.SpeedSigns;
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
 * @param signs    the speed-limit signs of Create Train Next that the train meets, as distances from
 *                 the start; those past {@code distance} are beyond the stop, where the track goes
 *                 on without a choice of way, and the train brakes for them on the way in
 */
public record Leg(String from, String to, double distance, List<Curve> curves, List<String> via, List<SpeedSign> signs) {

    /** A curve from {@code start} to {@code end}, in blocks from the start of the route. */
    public record Curve(double start, double end) {}

    public Leg {
        curves = List.copyOf(curves);
        via = List.copyOf(via);
        signs = List.copyOf(signs);
    }

    /** A leg with no signs on it. */
    public Leg(String from, String to, double distance, List<Curve> curves, List<String> via) {
        this(from, to, distance, curves, via, List.of());
    }

    /**
     * How many ticks a train of this spec takes for the leg at this throttle, running without a stop
     * in between and starting under no speed limit; empty if it cannot get there (see
     * {@link TravelTime#ticks}). Signals and other trains are not in it.
     */
    public OptionalInt ticks(TrainSpec spec, double throttle) {
        return ticks(spec, throttle, OptionalInt.empty());
    }

    /**
     * {@link #ticks(TrainSpec, double)} for a train that starts under the limit of a sign it passed
     * before, in km/h; its limit lasts until the first sign of the leg.
     */
    public OptionalInt ticks(TrainSpec spec, double throttle, OptionalInt limitAtStart) {
        List<SpeedZone> zones = new ArrayList<>();
        for (Curve curve : curves)
            zones.add(SpeedZone.curve(curve.start(), curve.end(), spec));
        return TravelTime.ticks(spec, throttle, distance, zones, new SpeedSigns(limitAtStart, signs));
    }

    /**
     * The limit a train is under when it stops at the end, in km/h: that of the last sign up to the
     * stop, or the one it started under if the leg has none. Empty for no limit.
     */
    public OptionalInt limitAtEnd(OptionalInt limitAtStart) {
        return new SpeedSigns(limitAtStart, signs).limitAfter(distance);
    }
}
