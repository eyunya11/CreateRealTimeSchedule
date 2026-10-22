package io.github.eyunya11.createrealtimeschedule.travel;

/**
 * A speed-limit sign of Create Train Next on the route, facing the way the train goes (one facing the
 * other way does nothing to it, and is left out). The position is the distance of the sign from where
 * the trip starts, in blocks, as for a {@link SpeedZone}.
 *
 * <p>A sign holds the train to its limit from where the front passes it to where the front passes the
 * next sign. A release sign is the one that ends a limit. How {@link TravelTime} brakes for a sign is
 * in {@link SpeedSigns}.
 *
 * @param position where the sign is, from where the trip starts
 * @param kmh      the limit in km/h, as the sign shows it; 0 for a release sign
 * @param release  whether this is a release sign
 */
public record SpeedSign(double position, int kmh, boolean release) {

    /** The lowest limit a sign of Create Train Next can be set to, in km/h. */
    public static final int MIN_KMH = 1;

    public SpeedSign {
        if (!Double.isFinite(position))
            throw new IllegalArgumentException("a sign needs a place on the route: " + position);
        if (release ? kmh != 0 : kmh < MIN_KMH)
            throw new IllegalArgumentException(release ? "a release sign has no limit of its own: " + kmh
                : "a limit is at least " + MIN_KMH + " km/h: " + kmh);
    }

    /** A sign that holds trains to {@code kmh} from here. */
    public static SpeedSign limit(double position, int kmh) {
        return new SpeedSign(position, kmh, false);
    }

    /** A sign that ends the limit trains are under. */
    public static SpeedSign release(double position) {
        return new SpeedSign(position, 0, true);
    }
}
