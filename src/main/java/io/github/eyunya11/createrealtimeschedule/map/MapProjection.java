package io.github.eyunya11.createrealtimeschedule.map;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

/**
 * What part of the world a map screen shows: a screen of {@code width} by {@code height} blocks, whose
 * middle is the world's ({@code centreX}, {@code centreZ}), where a block of the screen shows
 * {@code scale} blocks of the world each way. North is up and east to the right, as on the editor's
 * map, whichever way the screen faces.
 */
public record MapProjection(double centreX, double centreZ, double scale, int width, int height) {

    /** The least and the most blocks of the world a block of a screen may show. */
    public static final double MIN_SCALE = 0.25;
    public static final double MAX_SCALE = 4096;

    /**
     * The scales a screen snaps to as it is zoomed, from the least to the most: the powers of two from one
     * block up and one and a half of each (1, 1.5, 2, 3, 4, 6, 8, 12 and so on), and the quarters below
     * one, as the settings write a number to two places.
     */
    public static final List<Double> STEPS = steps();

    /**
     * How much a notch of the wheel zooms a screen's map where it is not kept to {@link #STEPS}: three
     * notches as much as one did at first (a quarter), as on the diagram and the editor's map.
     */
    public static final double WHEEL = Math.cbrt(1.25);

    private static List<Double> steps() {
        List<Double> steps = new ArrayList<>(List.of(MIN_SCALE, 0.5, 0.75));
        for (double power = 1; power <= MAX_SCALE; power *= 2) {
            steps.add(power);
            if (power * 1.5 < MAX_SCALE)
                steps.add(power * 1.5);
        }
        return List.copyOf(steps);
    }

    /** A part of the world, from above: west to east, and north to south. */
    public record Box(double minX, double minZ, double maxX, double maxZ) {
        public boolean contains(double x, double z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    /** The part of the world the screen's block at {@code column} from the left and {@code row} from the bottom shows. */
    public Box tile(int column, int row) {
        double left = centreX - width * scale / 2 + column * scale;
        double top = centreZ - height * scale / 2 + (height - 1 - row) * scale;
        return new Box(left, top, left + scale, top + scale);
    }

    /** The whole of what the screen shows. */
    public Box whole() {
        return new Box(centreX - width * scale / 2, centreZ - height * scale / 2, centreX + width * scale / 2, centreZ + height * scale / 2);
    }

    /** The screen moved {@code right} and {@code down} (south) of its blocks over the world. */
    public MapProjection moved(double right, double down) {
        return new MapProjection(centreX + right * scale, centreZ + down * scale, scale, width, height);
    }

    /**
     * The screen showing {@code factor} times as many blocks of the world each way, kept to what a screen
     * may show, with the place {@code right} and {@code down} of its blocks from its middle showing the
     * same part of the world as before.
     */
    public MapProjection zoomed(double factor, double right, double down) {
        return zoomedTo(scale * factor, right, down);
    }

    /** The screen at {@code to} blocks of the world to a block of it, kept to what a screen may show, about a place as {@link #zoomed}. */
    public MapProjection zoomedTo(double to, double right, double down) {
        double zoomed = clampScale(to);
        return new MapProjection(centreX + right * (scale - zoomed), centreZ + down * (scale - zoomed), zoomed, width, height);
    }

    /** The screen with its middle on the nearest whole blocks. */
    public MapProjection rounded() {
        return new MapProjection(Math.round(centreX), Math.round(centreZ), scale, width, height);
    }

    /**
     * The next of {@link #STEPS} past {@code scale}: the next more blocks of the world to a block of the
     * screen if {@code out}, the next fewer if not; the most or the least if there is none past it.
     */
    public static double nextStep(double scale, boolean out) {
        if (out) {
            for (double step : STEPS)
                if (step > scale * (1 + 1e-9))
                    return step;
            return MAX_SCALE;
        }
        for (int i = STEPS.size() - 1; i >= 0; i--)
            if (STEPS.get(i) < scale * (1 - 1e-9))
                return STEPS.get(i);
        return MIN_SCALE;
    }

    /** {@code scale} kept to what a screen may show. */
    public static double clampScale(double scale) {
        return Double.isFinite(scale) ? Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale)) : 1;
    }

    /**
     * The box round what a screen shows of the track of {@code dimension}: the tracks {@code shown}, by
     * their indexes on {@code map}, but for their stretches not shown ({@code stretches}, by the same
     * indexes; a track not there is shown all along). Null if it shows none of it.
     */
    public static @Nullable Box bounds(TrackMap map, int dimension, Set<Integer> shown, Map<Integer, List<TagView.Segment>> stretches) {
        double minX = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < map.tracks().size(); index++) {
            TrackMap.Track track = map.tracks().get(index);
            if (track.dimension() != dimension || !shown.contains(index))
                continue;
            List<TagView.Segment> segments = stretches.get(index);
            List<List<TrackMap.Spot>> parts = new ArrayList<>();
            if (segments == null)
                parts.add(track.points());
            else
                for (TagView.Segment segment : segments)
                    if (segment.shown())
                        parts.add(TagView.piece(track.points(), segment.from(), segment.to()));
            for (List<TrackMap.Spot> part : parts)
                for (TrackMap.Spot point : part) {
                    minX = Math.min(minX, point.x());
                    minZ = Math.min(minZ, point.z());
                    maxX = Math.max(maxX, point.x());
                    maxZ = Math.max(maxZ, point.z());
                }
        }
        return minX > maxX ? null : new Box(minX, minZ, maxX, maxZ);
    }

    /**
     * The middle and the scale that show the whole of {@code box} on a screen of this size, with a
     * twentieth of the screen to spare round it; the scale is rounded up to a whole number of blocks
     * where it is more than one, so that it reads well. Null for no box.
     *
     * @return {centreX, centreZ, scale}
     */
    public static double @Nullable [] fit(@Nullable Box box, int width, int height) {
        if (box == null)
            return null;
        double spanX = Math.max(box.maxX() - box.minX(), 1);
        double spanZ = Math.max(box.maxZ() - box.minZ(), 1);
        double scale = Math.max(spanX / (width * 0.9), spanZ / (height * 0.9));
        scale = clampScale(scale > 1 ? Math.ceil(scale) : scale);
        return new double[] {(box.minX() + box.maxX()) / 2, (box.minZ() + box.maxZ()) / 2, scale};
    }

    /**
     * The part of the segment from ({@code x1}, {@code z1}) to ({@code x2}, {@code z2}) inside
     * {@code box} (Liang and Barsky), or null if none of it is.
     *
     * @return {x1, z1, x2, z2}
     */
    public static double @Nullable [] clip(double x1, double z1, double x2, double z2, Box box) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double[] from = {0};
        double[] to = {1};
        if (!edge(-dx, x1 - box.minX(), from, to) || !edge(dx, box.maxX() - x1, from, to)
            || !edge(-dz, z1 - box.minZ(), from, to) || !edge(dz, box.maxZ() - z1, from, to))
            return null;
        return new double[] {x1 + from[0] * dx, z1 + from[0] * dz, x1 + to[0] * dx, z1 + to[0] * dz};
    }

    /** One edge of the box: narrows [from, to] to the part of the segment on the inside of it; false if none is. */
    private static boolean edge(double p, double q, double[] from, double[] to) {
        if (p == 0)
            return q >= 0;
        double r = q / p;
        if (p < 0) {
            if (r > to[0])
                return false;
            from[0] = Math.max(from[0], r);
        } else {
            if (r < from[0])
                return false;
            to[0] = Math.min(to[0], r);
        }
        return true;
    }
}
