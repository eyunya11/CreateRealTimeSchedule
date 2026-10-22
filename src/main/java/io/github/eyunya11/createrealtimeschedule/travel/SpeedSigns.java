package io.github.eyunya11.createrealtimeschedule.travel;

import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;

/**
 * The speed-limit signs of Create Train Next on a trip, and the limit the train is already under when
 * it starts: a sign's limit lasts until the next sign, past stations, so a trip can begin under the
 * limit of a sign that an earlier trip passed.
 *
 * <p>{@link TravelTime} treats them as Create Train Next's {@code TrainSpeedLimits} does. They hold
 * {@code Train.maxSpeed}, the top speed before the throttle is applied, and not the speed after it as
 * a curve does: at half throttle, a stretch under a 40 km/h sign is run at 20 km/h. The top speed is
 * also held to what the train can still slow from in time for each limit ahead, so that it is down to
 * the limit a block before the sign; this braking is reckoned at four fifths of the train's own, and
 * looked for only as far ahead as the train could need to brake from its top speed, plus 30 blocks.
 * A train standing still does not look ahead.
 *
 * <p>A sign at or behind the start ({@code position <= 0}) is behind the train: what it did is in
 * {@code limitAtStart}. Signs past the end of the trip may be given; the train brakes for those ahead
 * of it, as it would on the way to the stop.
 *
 * @param limitAtStart the limit in km/h the train is under when it starts, or empty for none
 * @param signs        the signs, in any order (they are kept sorted by position)
 */
public record SpeedSigns(OptionalInt limitAtStart, List<SpeedSign> signs) {

    /** No signs, and no limit to start with. */
    public static final SpeedSigns NONE = new SpeedSigns(OptionalInt.empty(), List.of());

    public SpeedSigns {
        if (limitAtStart.isPresent() && limitAtStart.getAsInt() < SpeedSign.MIN_KMH)
            throw new IllegalArgumentException("a limit is at least " + SpeedSign.MIN_KMH + " km/h: " + limitAtStart.getAsInt());
        signs = signs.stream().sorted(Comparator.comparingDouble(SpeedSign::position)).toList();
    }

    /** Signs on a trip that starts under no limit. */
    public static SpeedSigns of(List<SpeedSign> signs) {
        return new SpeedSigns(OptionalInt.empty(), signs);
    }

    /**
     * The limit the train is under once its front has passed {@code position}: that of the last sign
     * after the start and not after {@code position}, or the one it started under if it has passed
     * none. Empty for no limit, which a release sign also leaves.
     */
    public OptionalInt limitAfter(double position) {
        OptionalInt limit = limitAtStart;
        for (SpeedSign sign : signs) {
            if (sign.position() > position)
                break;
            if (sign.position() > 0)
                limit = sign.release() ? OptionalInt.empty() : OptionalInt.of(sign.kmh());
        }
        return limit;
    }
}
