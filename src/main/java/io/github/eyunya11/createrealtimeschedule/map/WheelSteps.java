package io.github.eyunya11.createrealtimeschedule.map;

/**
 * The wheel's notches counted into steps of a screen's scale ({@link MapProjection#STEPS}): a step for
 * every {@link #NOTCHES} notches turned one way. What is turned short of a step is kept for the next
 * turn the same way, so that a wheel turned a part of a notch at a time, as a touchpad turns it, still
 * steps; a turn the other way starts the count again, so that going back takes as many notches.
 */
public final class WheelSteps {

    /** How many notches of the wheel make a step: a third of a step a notch, as it was at first. */
    public static final int NOTCHES = 3;

    private double turned;

    /**
     * The whole steps made with {@code notches} more turned: away from the one turning the wheel
     * (positive, to zoom in) or towards them (negative, out).
     */
    public int turn(double notches) {
        if (notches == 0)
            return 0;
        if (turned != 0 && Math.signum(notches) != Math.signum(turned))
            turned = 0;
        turned += notches;
        int steps = (int) (turned / NOTCHES);
        turned -= steps * NOTCHES;
        return steps;
    }
}
