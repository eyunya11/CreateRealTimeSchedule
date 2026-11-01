package io.github.eyunya11.createrealtimeschedule.map;

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

    /** {@code scale} kept to what a screen may show. */
    public static double clampScale(double scale) {
        return Double.isFinite(scale) ? Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale)) : 1;
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
