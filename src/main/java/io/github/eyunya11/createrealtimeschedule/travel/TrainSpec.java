package io.github.eyunya11.createrealtimeschedule.travel;

/**
 * What decides how fast a train goes, in the units of Create's config: blocks, seconds, and blocks
 * per second squared. Create divides the speeds by 20 and the acceleration by 400 to get what it
 * uses each tick; {@link TravelTime} does the same.
 *
 * @param topSpeed     the top speed on straight track (config {@code trainTopSpeed}, 28 by default)
 * @param turnSpeed    the top speed on a curve ({@code trainTurningTopSpeed}, 14)
 * @param acceleration the acceleration, which is also the braking ({@code trainAcceleration}, 3)
 */
public record TrainSpec(double topSpeed, double turnSpeed, double acceleration) {

    /** A train that is not powered, with Create's default config. */
    public static final TrainSpec STANDARD = new TrainSpec(28, 14, 3);

    /** A powered train (one with fuel), with Create's default config. */
    public static final TrainSpec POWERED = new TrainSpec(40, 20, 3);

    public TrainSpec {
        if (!(topSpeed > 0) || !(turnSpeed > 0) || !(acceleration > 0))
            throw new IllegalArgumentException("a train needs a positive top speed, turn speed and acceleration: "
                + topSpeed + ", " + turnSpeed + ", " + acceleration);
    }
}
