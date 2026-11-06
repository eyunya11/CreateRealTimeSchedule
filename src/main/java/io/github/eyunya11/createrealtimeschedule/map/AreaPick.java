package io.github.eyunya11.createrealtimeschedule.map;

/**
 * The part of the world a map screen is to show, as it is picked on a map: dragged, its middle snapped
 * to whole blocks, and zoomed with the wheel about a place on it, its scale snapped to
 * {@link MapProjection#STEPS}, a step for every {@link WheelSteps#NOTCHES} notches; or, held fine (with
 * Shift), neither snapped, the wheel zooming {@link MapProjection#WHEEL} a notch.
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

    /** A drag begins, from where the screen is shown. */
    public void press() {
        dragged = shown;
    }

    /** The screen moved {@code right} and {@code down} of its blocks by a drag: its middle on whole blocks, unless {@code fine}. */
    public void drag(double right, double down, boolean fine) {
        dragged = dragged.moved(right, down);
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
            shown = shown.zoomed(Math.pow(MapProjection.WHEEL, -notches), right, down);
            wheel = new WheelSteps();
        } else {
            int steps = wheel.turn(notches);
            if (steps == 0)
                return;
            double scale = shown.scale();
            for (int step = 0; step < Math.abs(steps); step++)
                scale = MapProjection.nextStep(scale, steps < 0);
            shown = shown.zoomedTo(scale, right, down).rounded();
        }
        dragged = shown;
    }
}
