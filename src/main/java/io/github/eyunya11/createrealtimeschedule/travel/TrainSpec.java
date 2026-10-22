package io.github.eyunya11.createrealtimeschedule.travel;

/**
 * What decides how fast a train goes, in the units of Create's config: blocks, seconds, and blocks
 * per second squared. Create divides the speeds by 20 and the acceleration by 400 to get what it
 * uses each tick; {@link TravelTime} does the same.
 *
 * <p>For a train in the game, take the spec from the server's config with {@link TrainSpecs#of}: a
 * server can change every one of these numbers, and a train with fuel has others.
 *
 * @param topSpeed     the top speed on straight track (config {@code trainTopSpeed}, 28 by default)
 * @param turnSpeed    the top speed on a curve ({@code trainTurningTopSpeed}, 14); {@link SpeedZone#curve}
 *                     is how a curve gets it
 * @param acceleration the acceleration, which is also the braking ({@code trainAcceleration}, 3)
 */
public record TrainSpec(double topSpeed, double turnSpeed, double acceleration) {

    /** Create's default config for a train that is not powered. For tests and examples, not for a real train. */
    public static final TrainSpec STANDARD = new TrainSpec(28, 14, 3);

    /** Create's default config for a powered train (one with fuel). For tests and examples, not for a real train. */
    public static final TrainSpec POWERED = new TrainSpec(40, 20, 3);

    public TrainSpec {
        if (!(topSpeed > 0) || !(turnSpeed > 0) || !(acceleration > 0))
            throw new IllegalArgumentException("a train needs a positive top speed, turn speed and acceleration: "
                + topSpeed + ", " + turnSpeed + ", " + acceleration);
    }
}
