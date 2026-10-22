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
 * full one; curves have not been timed on a real train. A smooth speed profile was tried
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
     * The lowest cap of a {@link SpeedZone}, in blocks per second, that is taken as a speed and not as
     * a stop. Below it a trip of a few thousand blocks would take over a day, and the loop would run to
     * the end of that day before saying so. Create Train Next's signs are not zones and are not held to
     * this: the lowest, {@link SpeedSign#MIN_KMH} km/h, is 0.28 blocks per second.
     */
    public static final double MIN_CAP = 0.05;

    /** Create Train Next takes a block a second to be 3.6 km/h, so a block a tick is 72 km/h. */
    private static final double KMH_PER_BLOCK_PER_TICK = 3.6 * 20;
    /** The share of the train's braking that Create Train Next counts on to be down to a limit in time. */
    private static final double SIGN_BRAKING_SHARE = 0.8;
    /** How far past its braking distance Create Train Next looks for signs, in blocks. */
    private static final double SIGN_REACH_MARGIN = 30;
    /** How far before a sign Create Train Next has the train down to its limit, in blocks. */
    private static final double SIGN_ARRIVE_EARLY = 1;

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

        double topPerTick = spec.topSpeed() / 20;
        double acceleration = spec.acceleration() / 400;
        SignLimits limits = new SignLimits(signs, topPerTick, acceleration);

        double position = 0;
        double speed = 0;
        for (int ticks = 1; ticks <= GIVE_UP_TICKS; ticks++) {
            double remaining = distance + OVERSHOOT - position;
            double brakingDistance = speed * speed / (2 * acceleration);

            // Close enough to step onto the stop.
            if (remaining - Math.abs(speed) < ARRIVAL_SNAP)
                return OptionalInt.of(ticks);

            // What Train.maxSpeed answers: the top speed, held by Create Train Next's signs.
            double top = limits.cap(topPerTick, position, speed);

            // The last ten blocks: no faster than the share of the top speed that is left to go.
            if (remaining < APPROACH_BLOCKS) {
                double maxApproachSpeed = top * (remaining / APPROACH_BLOCKS);
                if (speed > maxApproachSpeed) {
                    speed += (maxApproachSpeed - Math.abs(speed)) * 0.5;
                    // Create stops the train at the stop's marker, in the tick whose move reaches it.
                    if (position + speed >= distance)
                        return OptionalInt.of(ticks);
                    position += speed;
                    limits.pass(position);
                    continue;
                }
            }

            double cruise = top * throttle;
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
            limits.pass(position);
        }
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
        return limit.isPresent() ? blocks / (limit.getAsInt() / KMH_PER_BLOCK_PER_TICK) : 0;
    }

    /**
     * The limits of Create Train Next's signs as the trip goes on, kept as its {@code TrainSpeedLimits}
     * keeps them for a train: the limit of the last sign the front passed, and the signs still ahead.
     * Speeds are in blocks a tick.
     */
    private static final class SignLimits {
        private final List<SpeedSign> signs;
        private final double braking;
        private final double reach;
        /** The first sign the front has not passed yet. */
        private int next;
        /** The limit of the last sign passed, or infinity for none. */
        private double limit;

        SignLimits(SpeedSigns signs, double topPerTick, double acceleration) {
            this.signs = signs.signs();
            this.braking = SIGN_BRAKING_SHARE * acceleration;
            this.reach = topPerTick * topPerTick / (2 * braking) + SIGN_REACH_MARGIN;
            this.limit = signs.limitAtStart().isPresent() ? signs.limitAtStart().getAsInt() / KMH_PER_BLOCK_PER_TICK
                : Double.POSITIVE_INFINITY;
            pass(0);
        }

        /**
         * {@code TrainSpeedLimits.cap}: the top speed held to the limit the train is under and, while it
         * moves, to the fastest it may go to slow down to each limit ahead by a block before its sign.
         */
        double cap(double topPerTick, double position, double speed) {
            double capped = Math.min(topPerTick, limit);
            if (speed == 0)
                return capped;
            for (int i = next; i < signs.size(); i++) {
                SpeedSign sign = signs.get(i);
                double ahead = sign.position() - position;
                if (ahead > reach)
                    break;
                // A release sign lifts the limit: there is nothing to slow down for.
                if (sign.release())
                    continue;
                double kmh = sign.kmh() / KMH_PER_BLOCK_PER_TICK;
                double room = Math.max(0, ahead - SIGN_ARRIVE_EARLY);
                capped = Math.min(capped, Math.sqrt(kmh * kmh + 2 * braking * room));
            }
            return capped;
        }

        /** The front has moved to {@code position}: the signs it passed on the way set the limit, the last one last. */
        void pass(double position) {
            while (next < signs.size() && signs.get(next).position() <= position) {
                SpeedSign sign = signs.get(next++);
                if (sign.position() > 0)
                    limit = sign.release() ? Double.POSITIVE_INFINITY : sign.kmh() / KMH_PER_BLOCK_PER_TICK;
            }
        }
    }
}
