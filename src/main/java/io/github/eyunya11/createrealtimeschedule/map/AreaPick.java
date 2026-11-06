package io.github.eyunya11.createrealtimeschedule.map;

/**
 * The part of the world a map screen is to show, as it is picked on a map: dragged, its middle snapped
 * to whole blocks, and zoomed with the wheel about a place on it, its scale snapped to
 * {@link MapProjection#STEPS}, a step for every {@link WheelSteps#NOTCHES} notches; or, held fine (with
 * Shift), neither snapped, the wheel zooming {@link MapProjection#WHEEL} a notch. Its middle is kept
 * to what the server takes ({@link ScreenSettings#MAX_CENTRE}), so that it is not dragged off the world.
 */
public final class AreaPick {

    private MapProjection shown;
    /**
     * Where the drag has taken the screen, its middle not snapped, so that a drag of less than a block at
     * a time still moves it: what is shown is this snapped.
     */
    private MapProjection dragged;
    private WheelSteps wheel = new WheelSteps();

    public AreaPick(MapProjection start) {
        shown = start;
        dragged = start;
    }

    /** What the screen would show now. */
    public MapProjection shown() {
        return shown;
    }

    /**
     * How many of the window's pixels a block of a screen {@code width} by {@code height} blocks takes on
     * the map, as many as fit the screen in {@code areaWidth} by {@code areaHeight} but no more: the screen
     * then takes a whole number of pixels each way, so that the frame drawn round it, on whole pixels, is
     * where what is drawn in it ends. A whole number of {@code 1 / gcd(width, height)} pixels does that,
     * so a square screen fills its room to the pixel.
     */
    public static double block(int areaWidth, int areaHeight, int width, int height) {
        int common = gcd(width, height);
        double fit = Math.min(areaWidth / (double) width, areaHeight / (double) height);
        long parts = Math.max(1, (long) Math.floor(fit * common + 1e-9));
        return parts / (double) common;
    }

    private static int gcd(int a, int b) {
        return b == 0 ? Math.max(1, a) : gcd(b, a % b);
    }

    /**
     * A drag begins, from where the screen is shown; the wheel counts its notches afresh, so that what
     * was turned short of a step before does not step the scale at the first notch after.
     */
    public void press() {
        dragged = shown;
        wheel = new WheelSteps();
    }

    /** The screen moved {@code right} and {@code down} of its blocks by a drag: its middle on whole blocks, unless {@code fine}. */
    public void drag(double right, double down, boolean fine) {
        dragged = within(dragged.moved(right, down));
        shown = fine ? dragged : dragged.rounded();
    }

    /**
     * The wheel turned {@code notches} notches away from the one turning it (in) or, below nought,
     * towards them (out), about the place {@code right} and {@code down} of the screen's blocks from its
     * middle: a step of the scale every {@link WheelSteps#NOTCHES} notches, its middle on whole blocks, or,
     * if {@code fine}, a little each notch. A drag going on goes on from what the wheel has zoomed to.
     */
    public void wheel(double notches, double right, double down, boolean fine) {
        if (fine) {
            shown = within(shown.zoomed(Math.pow(MapProjection.WHEEL, -notches), right, down));
            wheel = new WheelSteps();
        } else {
            int steps = wheel.turn(notches);
            if (steps == 0)
                return;
            double scale = shown.scale();
            for (int step = 0; step < Math.abs(steps); step++)
                scale = MapProjection.nextStep(scale, steps < 0);
            shown = within(shown.zoomedTo(scale, right, down).rounded());
        }
        dragged = shown;
    }

    /** {@code projection} with its middle kept to what the server takes. */
    private static MapProjection within(MapProjection projection) {
        double most = ScreenSettings.MAX_CENTRE;
        double x = Math.max(-most, Math.min(most, projection.centreX()));
        double z = Math.max(-most, Math.min(most, projection.centreZ()));
        return new MapProjection(x, z, projection.scale(), projection.width(), projection.height());
    }
}
