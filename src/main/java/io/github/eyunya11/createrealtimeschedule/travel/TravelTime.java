package io.github.eyunya11.createrealtimeschedule.travel;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * How long a train takes to run from a stop to the next, worked out from Create's speed control and
 * nothing else, so that it can be tested without a game.
 *
 * <p>It does what Create's {@code Navigation.tick} does, one tick at a time, for a train that starts
 * and ends at a stop with nothing in the way. That costs a loop of a few thousand steps for a run of
 * five thousand blocks, which is cheap enough to do for every pair of stations of a timetable. It
 * agrees with real trains to the tick on the five straight runs that the game tests
 * ({@code TravelTimeRunTests}) time, from 20 to 300 blocks and from a tenth of the throttle to a
 * full one. Over a curve it is within a second of a real train, not to the tick
 * ({@code CurveRunTests}): Create keeps the wheels of a bogey a fixed straight distance apart on a
 * curve, which nudges the front wheel off the distance the train counts by a fraction of a block,
 * and that is left out. A smooth speed profile was tried
 * first and left out, because it is further from the train than this is (up to 0.55 s on 300
 * blocks) and does not make the arithmetic any cheaper.
 *
 * <p>Create's control law jitters: over 5 to 3,000 blocks at full throttle, runs a block apart differ
 * by up to 9 ticks (0.45 s), because the braking rule flips on and off from tick to tick near the
 * stop. The model has the same jitter, as a real train does.
 *
 * <p>The speed-limit signs of Create Train Next are in it as that mod enforces them (see
 * {@link SpeedSigns}). It agrees to the tick with trains that run under them on four straight runs of
 * 300 blocks ({@code SpeedSignRunTests}, which run only with a jar of Create Train Next at hand).
 *
 * <p>Signals, other trains and a train that waits for chunks are not in it; they are the reasons a
 * real trip is longer than this. The control law itself is {@link Drive}'s, which a simulation of the
 * trains of a timetable also runs, a tick at a time and with the signals.
 */
public final class TravelTime {

    /** A route that takes longer than a day of game time is taken to be one the train never finishes. */
    private static final int GIVE_UP_TICKS = 20 * 60 * 60 * 24;

    /**
     * The lowest cap of a {@link SpeedZone}, in blocks per second, that is taken as a speed and not as
     * a stop. Below it a trip of a few thousand blocks would take over a day, and the loop would run to
     * the end of that day before saying so. Create Train Next's signs are not zones and are not held to
     * this: the lowest, {@link SpeedSign#MIN_KMH} km/h, is 0.28 blocks per second.
     */
    public static final double MIN_CAP = 0.05;

    private TravelTime() {}

    /** {@link #ticks(TrainSpec, double, double, List, SpeedSigns)} on a route without speed-limit signs. */
    public static OptionalInt ticks(TrainSpec spec, double throttle, double distance, List<SpeedZone> zones) {
        return ticks(spec, throttle, distance, zones, SpeedSigns.NONE);
    }

    /**
     * Runs Create's speed control for a trip of {@code distance} blocks that starts and ends at a
     * stop, and returns the number of ticks from the first tick in which the train moves to the one
     * in which it is stopped at the marker of the stop. The head is what is measured: Create looks at
     * the front of the train for the stop and for curves, so the length of the train does not matter.
     *
     * @param throttle the share of the top speed the train is allowed (1 for full; Create's throttle
     *                 instruction in a schedule sets it)
     * @param zones    where the head may not go faster than a cap, measured from the start of the trip
     * @param signs    the speed-limit signs of Create Train Next on the way
     * @return empty if a zone's cap is below {@link #MIN_CAP}, if the throttle or the distance is not a
     *         number in range, or if the train would never arrive (or not within a day)
     */
    public static OptionalInt ticks(TrainSpec spec, double throttle, double distance, List<SpeedZone> zones,
        SpeedSigns signs) {
        if (!(throttle > 0) || throttle > 1 || !(distance >= 0))
            return OptionalInt.empty();
        for (SpeedZone zone : zones) {
            if (!(zone.cap() >= MIN_CAP))
                return OptionalInt.empty();
            // Answered without running the loop: the stretch alone is longer than a day at that cap.
            double inside = Math.min(zone.end(), distance) - Math.max(zone.start(), 0);
            if (inside / (zone.cap() / 20) > GIVE_UP_TICKS)
                return OptionalInt.empty();
        }
        // The same for the signs: a slow one ahead of a long way would also run the loop for a day.
        if (ticksUnderSigns(signs, distance) > GIVE_UP_TICKS)
            return OptionalInt.empty();
        if (distance == 0)
            return OptionalInt.of(0);

        Drive drive = new Drive(spec, throttle, zones, signs);
        for (int ticks = 1; ticks <= GIVE_UP_TICKS; ticks++)
            if (drive.step(distance, Drive.SignalsAhead.NONE) == Drive.Step.REACHED)
                return OptionalInt.of(ticks);
        return OptionalInt.empty();
    }

    /** {@link #ticks(TrainSpec, double, double, List)} in seconds. */
    public static OptionalDouble seconds(TrainSpec spec, double throttle, double distance, List<SpeedZone> zones) {
        return seconds(spec, throttle, distance, zones, SpeedSigns.NONE);
    }

    /** {@link #ticks(TrainSpec, double, double, List, SpeedSigns)} in seconds. */
    public static OptionalDouble seconds(TrainSpec spec, double throttle, double distance, List<SpeedZone> zones,
        SpeedSigns signs) {
        OptionalInt ticks = ticks(spec, throttle, distance, zones, signs);
        return ticks.isPresent() ? OptionalDouble.of(ticks.getAsInt() / 20.0) : OptionalDouble.empty();
    }

    /**
     * The fewest ticks the trip can take for its signs alone: each stretch of it under a limit, run at
     * that limit. The train is never faster than the limit it is under.
     */
    private static double ticksUnderSigns(SpeedSigns signs, double distance) {
        double ticks = 0;
        double from = 0;
        OptionalInt limit = signs.limitAtStart();
        for (SpeedSign sign : signs.signs()) {
            if (sign.position() <= 0)
                continue;
            if (sign.position() >= distance)
                break;
            ticks += ticksAt(limit, sign.position() - from);
            from = sign.position();
            limit = sign.release() ? OptionalInt.empty() : OptionalInt.of(sign.kmh());
        }
        return ticks + ticksAt(limit, distance - from);
    }

    private static double ticksAt(OptionalInt limit, double blocks) {
        return limit.isPresent() ? blocks / (limit.getAsInt() / Drive.KMH_PER_BLOCK_PER_TICK) : 0;
    }
}
