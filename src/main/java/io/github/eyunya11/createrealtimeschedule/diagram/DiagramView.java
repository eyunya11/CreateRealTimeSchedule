package io.github.eyunya11.createrealtimeschedule.diagram;

import org.jetbrains.annotations.Nullable;

import io.github.eyunya11.createrealtimeschedule.diagram.DiagramModel.Segment;

/**
 * Which part of a diagram is on the screen, and where on the screen a point of it goes: the time
 * shown runs from left to right across the plot, and the line's axis from its top to its bottom. It
 * has no Minecraft types in it so that it can be tested without a game.
 */
public final class DiagramView {

    /** The narrowest span of time that can be shown, in seconds. */
    public static final double MIN_SPAN = 5 * 60;
    /** The widest, in seconds: two days, as far as a timetable's times go. */
    public static final double MAX_SPAN = 48 * 3600;
    /** The steps the time scale can be marked in, in seconds. */
    private static final int[] STEPS = {60, 5 * 60, 10 * 60, 30 * 60, 3600, 2 * 3600, 6 * 3600};

    private double start;
    private double span;
    private double left;
    private double top;
    private double width = 1;
    private double height = 1;

    /** A view of {@code span} seconds from {@code start}. */
    public DiagramView(double start, double span) {
        this.span = clampSpan(span);
        this.start = start;
    }

    /** Puts the plot at {@code left}, {@code top}, {@code width} wide and {@code height} high, in screen units. */
    public void place(double left, double top, double width, double height) {
        this.left = left;
        this.top = top;
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
    }

    public double start() {
        return start;
    }

    public double end() {
        return start + span;
    }

    public double span() {
        return span;
    }

    /** Where on the screen the time {@code second} is across. */
    public double x(double second) {
        return left + (second - start) / span * width;
    }

    /** Where on the screen {@code position} on an axis {@code length} blocks long is down. */
    public double y(double position, double length) {
        return length <= 0 ? top : top + position / length * height;
    }

    /** The time at {@code x} across the screen. */
    public double second(double x) {
        return start + (x - left) / width * span;
    }

    /** Shows {@code factor} times as much time, keeping the time at {@code x} where it is. */
    public void zoom(double factor, double x) {
        double fixed = second(x);
        double share = (fixed - start) / span;
        span = clampSpan(span * factor);
        start = fixed - share * span;
    }

    /** Moves the view {@code dx} screen units to the right, so that earlier times come in from the left. */
    public void pan(double dx) {
        start -= dx / width * span;
    }

    /** Puts {@code second} in the middle. */
    public void centreOn(double second) {
        start = second - span / 2;
    }

    private static double clampSpan(double span) {
        return Math.max(MIN_SPAN, Math.min(MAX_SPAN, span));
    }

    /** The step to mark the time scale in, in seconds: the finest whose marks are {@code minGap} screen units apart at the least. */
    public int step(double minGap) {
        for (int step : STEPS)
            if (step / span * width >= minGap)
                return step;
        return STEPS[STEPS.length - 1];
    }

    /**
     * The part of {@code segment} that is in the time shown, on an axis {@code length} blocks long, as
     * {@code {x1, y1, x2, y2}} on the screen, from its {@code from} end to its {@code to} end; null if
     * none of it is. A segment may go back in time, as a wait's arrow can.
     */
    public double @Nullable [] clip(Segment segment, double length) {
        double from = segment.fromSecond();
        double to = segment.toSecond();
        if (Math.max(from, to) < start || Math.min(from, to) > end())
            return null;
        double fromPosition = segment.fromPosition();
        double toPosition = segment.toPosition();
        if (to != from) {
            double a = Math.clamp(from, start, end());
            double b = Math.clamp(to, start, end());
            double slope = (toPosition - fromPosition) / (to - from);
            double aPosition = fromPosition + (a - from) * slope;
            double bPosition = fromPosition + (b - from) * slope;
            from = a;
            to = b;
            fromPosition = aPosition;
            toPosition = bPosition;
        }
        return new double[] {x(from), y(fromPosition, length), x(to), y(toPosition, length)};
    }
}
