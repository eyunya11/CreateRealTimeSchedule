package io.github.eyunya11.createrealtimeschedule.travel;

import java.util.List;

/**
 * A train on a trip under Create's speed control, a tick at a time: what {@link TravelTime} runs to the
 * end of a trip with nothing in the way, and what a simulation of many trains runs alongside the others,
 * stopping it short of a signal at red as Create does.
 *
 * <p>Each {@link #step} does what Create's {@code Navigation.tick} and the move of {@code Train.tick} do
 * in one tick, for a train whose front is to stop at a point: the stop marker at the end of the trip, or
 * a signal it waits at. The front is what is measured, from where the trip starts, in blocks.
 *
 * <p>Create Train Next holds a train to the limit of the last speed-limit sign it passed and to that of
 * the last light signal it passed, and to what it can still slow from in time for the limits ahead. The
 * signs are the trip's, and this keeps track of them; the signals ahead and the limit of the last one
 * passed come from whoever runs the signals ({@link #signalLimit}).
 */
public final class Drive {

    /** Create aims this much past the stop so that the train's travelling point crosses it. */
    static final double OVERSHOOT = 0.25;
    /** Within this many blocks of the stop, Create limits the speed in proportion to what is left. */
    private static final double APPROACH_BLOCKS = 10;
    /** The step below which Create considers the train to have arrived, in blocks. */
    private static final double ARRIVAL_SNAP = 1.0 / 32;
    /** Create's {@code Mth.equal}. */
    private static final double SPEED_EPSILON = 1.0e-5;

    /** Create Train Next takes a block a second to be 3.6 km/h, so a block a tick is 72 km/h. */
    public static final double KMH_PER_BLOCK_PER_TICK = 3.6 * 20;
    /** The share of the train's braking that Create Train Next counts on to be down to a limit in time. */
    private static final double SIGN_BRAKING_SHARE = 0.8;
    /** How far past its braking distance Create Train Next looks for signs and signals, in blocks. */
    private static final double SIGN_REACH_MARGIN = 30;
    /** How far before a sign or signal Create Train Next has the train down to its limit, in blocks. */
    private static final double SIGN_ARRIVE_EARLY = 1;

    /** What a step came to. */
    public enum Step {
        /** The train moved, or stood still on its way. */
        MOVED,
        /** Its front got to the point it was to stop at, and it stands there. */
        REACHED
    }

    /**
     * The signals ahead of the train, as Create Train Next scouts for them: each with what its lamps say
     * now. Positions are from where the trip starts, in order.
     */
    public interface SignalsAhead {

        /** None. */
        SignalsAhead NONE = new SignalsAhead() {
            @Override
            public int count() {
                return 0;
            }

            @Override
            public double position(int i) {
                throw new IndexOutOfBoundsException(i);
            }

            @Override
            public double kmh(int i) {
                throw new IndexOutOfBoundsException(i);
            }
        };

        int count();

        double position(int i);

        /**
         * The speed in km/h the signal will hold the train to; infinity where it lifts the limit of the
         * signal before (a green one, or one of Create's own); NaN where it says nothing the train can go
         * by, as one at stop does, since it is Create that stops the train there.
         */
        double kmh(int i);
    }

    private final double throttle;
    private final List<SpeedZone> zones;
    private final List<SpeedSign> signs;
    private final double topPerTick;
    private final double acceleration;
    private final double braking;
    private final double reach;

    private double position;
    private double speed;
    /** The first sign the front has not passed yet. */
    private int nextSign;
    /** The limit of the last sign passed, in blocks a tick, or infinity for none. */
    private double signLimit;
    /** The limit of the last signal passed, in blocks a tick, or infinity for none. */
    private double signalLimit = Double.POSITIVE_INFINITY;

    /**
     * A train standing at the start of a trip, under the limit of the signs in {@code signs} it passed
     * before it.
     *
     * @param zones where the head may not go faster than a cap, from the start of the trip
     */
    public Drive(TrainSpec spec, double throttle, List<SpeedZone> zones, SpeedSigns signs) {
        this.throttle = throttle;
        this.zones = List.copyOf(zones);
        this.signs = signs.signs();
        this.topPerTick = spec.topSpeed() / 20;
        this.acceleration = spec.acceleration() / 400;
        this.braking = SIGN_BRAKING_SHARE * acceleration;
        this.reach = topPerTick * topPerTick / (2 * braking) + SIGN_REACH_MARGIN;
        this.signLimit = signs.limitAtStart().isPresent() ? signs.limitAtStart().getAsInt() / KMH_PER_BLOCK_PER_TICK
            : Double.POSITIVE_INFINITY;
        passSigns(0);
    }

    /** Where the front is, from the start of the trip. */
    public double position() {
        return position;
    }

    /** How far the train moves in a tick, in blocks. */
    public double speed() {
        return speed;
    }

    /** What Create's {@code Navigation} takes to be how far the train needs to stop: its speed squared over twice its braking. */
    public double brakingDistance() {
        return speed * speed / (2 * acceleration);
    }

    /** The limit in km/h of the last signal passed, infinity for none. */
    public double signalLimit() {
        return signalLimit * KMH_PER_BLOCK_PER_TICK;
    }

    /** The front has passed a signal that holds the train to {@code kmh}, or infinity to lift the limit. */
    public void signalLimit(double kmh) {
        signalLimit = kmh / KMH_PER_BLOCK_PER_TICK;
    }

    /**
     * One tick toward stopping the front at {@code stopAt}: Create's control law, as {@link TravelTime}
     * has it. Where the move would take the front to the stop or past it, it stops there.
     *
     * @param stopAt where the front is to stop, from the start of the trip; not behind it
     * @param ahead  the signals ahead, for Create Train Next's slowing in time for them
     */
    public Step step(double stopAt, SignalsAhead ahead) {
        double remaining = stopAt + OVERSHOOT - position;
        double brakingDistance = brakingDistance();

        // Close enough to step onto the stop.
        if (remaining - Math.abs(speed) < ARRIVAL_SNAP)
            return reach(stopAt);

        // What Train.maxSpeed answers: the top speed, held by Create Train Next's signs and signals.
        double top = cap(ahead);

        // The last ten blocks: no faster than the share of the top speed that is left to go.
        if (remaining < APPROACH_BLOCKS) {
            double maxApproachSpeed = top * (remaining / APPROACH_BLOCKS);
            if (speed > maxApproachSpeed) {
                speed += (maxApproachSpeed - Math.abs(speed)) * 0.5;
                return move(stopAt);
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
            double before = zone.start() - position;
            if (before > 0 && cap < speed) {
                double slowingDistance = (speed * speed - cap * cap) / (2 * acceleration);
                if (before <= slowingDistance)
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
        return move(stopAt);
    }

    /**
     * The train is stopped at the point the moment a move reaches it: it aims a quarter of a block past,
     * and gets there still moving slowly.
     */
    private Step move(double stopAt) {
        if (position + speed >= stopAt)
            return reach(stopAt);
        position += speed;
        passSigns(position);
        return Step.MOVED;
    }

    private Step reach(double stopAt) {
        position = Math.max(position, stopAt);
        speed = 0;
        passSigns(position);
        return Step.REACHED;
    }

    /**
     * {@code TrainSpeedLimits.cap}: the top speed held to the limits the train is under and, while it
     * moves, to the fastest it may go to slow down to each limit ahead by a block before its sign or
     * signal. A sign ahead replaces the sign limit, a signal the signal limit, and the lower of the two is
     * what the train has to be down to there.
     */
    private double cap(SignalsAhead ahead) {
        double capped = Math.min(topPerTick, Math.min(signLimit, signalLimit));
        if (speed == 0)
            return capped;
        double sign = signLimit;
        double signal = signalLimit;
        int s = nextSign;
        int g = 0;
        int lights = ahead.count();
        while (true) {
            double signAt = s < signs.size() ? signs.get(s).position() : Double.POSITIVE_INFINITY;
            double signalAt = g < lights ? ahead.position(g) : Double.POSITIVE_INFINITY;
            double at = Math.min(signAt, signalAt);
            double distance = at - position;
            if (distance > reach)
                break;
            if (signAt <= signalAt) {
                SpeedSign each = signs.get(s++);
                sign = each.release() ? Double.POSITIVE_INFINITY : each.kmh() / KMH_PER_BLOCK_PER_TICK;
            } else {
                double kmh = ahead.kmh(g++);
                // A signal at stop says nothing to go by; Create stops the train there.
                if (Double.isNaN(kmh))
                    continue;
                signal = kmh / KMH_PER_BLOCK_PER_TICK;
            }
            double lowest = Math.min(sign, signal);
            if (Double.isInfinite(lowest))
                continue;
            double room = Math.max(0, distance - SIGN_ARRIVE_EARLY);
            capped = Math.min(capped, Math.sqrt(lowest * lowest + 2 * braking * room));
        }
        return capped;
    }

    /** The front has moved to {@code to}: the signs it passed on the way set the limit, the last one last. */
    private void passSigns(double to) {
        while (nextSign < signs.size() && signs.get(nextSign).position() <= to) {
            SpeedSign sign = signs.get(nextSign++);
            if (sign.position() > 0)
                signLimit = sign.release() ? Double.POSITIVE_INFINITY : sign.kmh() / KMH_PER_BLOCK_PER_TICK;
        }
    }
}
