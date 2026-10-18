package io.github.eyunya11.createrealtimeschedule.travel;

/**
 * A stretch of the route in which the head of the train may not go faster than {@code cap}, as
 * Create does on a curve. Positions are the distance of the head from where the trip starts, in
 * blocks; the cap is in blocks per second.
 *
 * <p>Head-based on purpose: Create decides about a curve by where the front of the train is, not the
 * rear, so the zone is measured for the front as well.
 */
public record SpeedZone(double start, double end, double cap) {

    public SpeedZone {
        if (!(end >= start))
            throw new IllegalArgumentException("a zone cannot end before it starts: " + start + " to " + end);
    }

    /**
     * A curve. Create holds a train to its turn speed on one, so the cap is the spec's; a curve that
     * is built with {@code new SpeedZone(..., someNumber)} instead goes at whatever that number is.
     * (At a throttle low enough that the train is slower than the turn speed anyway, a curve changes
     * nothing, as in the game.)
     */
    public static SpeedZone curve(double start, double end, TrainSpec spec) {
        return new SpeedZone(start, end, spec.turnSpeed());
    }

    public boolean contains(double position) {
        return position >= start && position <= end;
    }
}
