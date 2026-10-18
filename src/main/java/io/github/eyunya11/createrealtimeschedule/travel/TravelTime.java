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
 * full one; curves and signs have not been timed on a real train. A smooth speed profile was tried
 * first and left out, because it is further from the train than this is (up to 0.55 s on 300
 * blocks) and does not make the arithmetic any cheaper.
 *
 * <p>Create's control law jitters: over 5 to 3,000 blocks at full throttle, runs a block apart differ
 * by up to 9 ticks (0.45 s), because the braking rule flips on and off from tick to tick near the
 * stop. The model has the same jitter, as a real train does.
 *
 * <p>Signals, other trains and a train that waits for chunks are not in it; they are the reasons a
 * real trip is longer than this.
 */
public final class TravelTime {

    /** Create aims this much past the stop so that the train's travelling point crosses it. */
    private static final double OVERSHOOT = 0.25;
    /** Within this many blocks of the stop, Create limits the speed in proportion to what is left. */
    private static final double APPROACH_BLOCKS = 10;
    /** The step below which Create considers the train to have arrived, in blocks. */
    private static final double ARRIVAL_SNAP = 1.0 / 32;
    /** Create's {@code Mth.equal}. */
    private static final double SPEED_EPSILON = 1.0e-5;
    /** A route that takes longer than a day of game time is taken to be one the train never finishes. */
    private static final int GIVE_UP_TICKS = 20 * 60 * 60 * 24;

    /**
     * The lowest cap, in blocks per second, that is taken as a speed and not as a stop. Below it a
     * trip of a few thousand blocks would take over a day, and the loop would run to the end of that
     * day before saying so. (A speed-limit sign of 1% is 0.28 blocks per second.)
     */
    public static final double MIN_CAP = 0.05;

    private TravelTime() {}

    /**
     * Runs Create's speed control for a trip of {@code distance} blocks that starts and ends at a
     * stop, and returns the number of ticks from the first tick in which the train moves to the one
     * in which it is stopped at the marker of the stop. The head is what is measured: Create looks at
     * the front of the train for the stop and for curves, so the length of the train does not matter.
     *
     * @param throttle the share of the top speed the train is allowed (1 for full; Create's throttle
     *                 instruction in a schedule sets it)
     * @param zones    where the head may not go faster than a cap, measured from the start of the trip
     * @return empty if a cap is below {@link #MIN_CAP} (a sign that stops the train), if the throttle
     *         or the distance is not a number in range, or if the train would never arrive (or not
     *         within a day)
     */
    public static OptionalInt ticks(TrainSpec spec, double throttle, double distance, List<SpeedZone> zones) {
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
        if (distance == 0)
            return OptionalInt.of(0);

        double topPerTick = spec.topSpeed() / 20;
        double acceleration = spec.acceleration() / 400;
        double cruise = topPerTick * throttle;

        double position = 0;
        double speed = 0;
        for (int ticks = 1; ticks <= GIVE_UP_TICKS; ticks++) {
            double remaining = distance + OVERSHOOT - position;
            double brakingDistance = speed * speed / (2 * acceleration);

            // Close enough to step onto the stop.
            if (remaining - Math.abs(speed) < ARRIVAL_SNAP)
                return OptionalInt.of(ticks);

            // The last ten blocks: no faster than the share of the top speed that is left to go.
            if (remaining < APPROACH_BLOCKS) {
                double maxApproachSpeed = topPerTick * (remaining / APPROACH_BLOCKS);
                if (speed > maxApproachSpeed) {
                    speed += (maxApproachSpeed - Math.abs(speed)) * 0.5;
                    // Create stops the train at the stop's marker, in the tick whose move reaches it.
                    if (position + speed >= distance)
                        return OptionalInt.of(ticks);
                    position += speed;
                    continue;
                }
            }

            double target = remaining > brakingDistance ? cruise : 0;

            // A cap under the head holds the speed to it; one ahead starts the braking early enough
            // to be down to it on arrival.
            double inside = Double.POSITIVE_INFINITY;
            for (SpeedZone zone : zones) {
                double cap = Math.min(zone.cap() / 20, cruise);
                if (zone.contains(position))
                    inside = Math.min(inside, cap);
                double ahead = zone.start() - position;
                if (ahead > 0 && cap < speed) {
                    double slowingDistance = (speed * speed - cap * cap) / (2 * acceleration);
                    if (ahead <= slowingDistance)
                        target = Math.min(target, cap);
                }
            }
            target = Math.min(target, inside);

            if (Math.abs(target - speed) >= SPEED_EPSILON) {
                if (speed < target)
                    speed = Math.min(speed + acceleration, target);
                else
                    speed = Math.max(speed - acceleration, target);
            }
            // The train is stopped at the marker the moment a move reaches it: it aims a quarter of a
            // block past, and arrives, still moving slowly, when it gets there.
            if (position + speed >= distance)
                return OptionalInt.of(ticks);
            position += speed;
        }
        return OptionalInt.empty();
    }

    /** {@link #ticks} in seconds. */
    public static OptionalDouble seconds(TrainSpec spec, double throttle, double distance, List<SpeedZone> zones) {
        OptionalInt ticks = ticks(spec, throttle, distance, zones);
        return ticks.isPresent() ? OptionalDouble.of(ticks.getAsInt() / 20.0) : OptionalDouble.empty();
    }
}
