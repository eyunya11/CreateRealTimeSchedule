package io.github.eyunya11.createrealtimeschedule.map;

import java.util.ArrayList;
import java.util.List;

import io.github.eyunya11.createrealtimeschedule.map.MapProjection.Box;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Spot;

/**
 * Lines of some width through points, as a map screen draws track: each bend is mitred, the outer
 * edges carried on till they meet, so that the pieces of a line neither overlap nor leave a notch at
 * the bend; the ends are cut square at the points and not carried past them, so that two lines that
 * meet end to end, as the dashes of a stretch with more than one tag do, only touch. A track meets the
 * next at a node along the same line (Create lays the track through a node one way), so square ends
 * join the tracks as well.
 */
public final class Polyline {

    /** How far a mitred corner may reach from its point, in half widths, before it is cut back to that. */
    static final double MITRE_LIMIT = 4;

    private Polyline() {}

    /**
     * The corners of a line through {@code points}, {@code half} each side of it: for each point, the
     * corner on its left and the one on its right, as {@code [leftX, leftZ, rightX, rightZ]}, left as one
     * goes along x to the right and z up. The line between two points is the quad of their corners;
     * points on top of the one before are left out.
     */
    public static List<double[]> outline(List<Spot> points, double half) {
        List<Spot> line = new ArrayList<>();
        for (Spot point : points)
            if (line.isEmpty() || Math.hypot(point.x() - line.getLast().x(), point.z() - line.getLast().z()) > 1e-9)
                line.add(point);
        List<double[]> corners = new ArrayList<>();
        if (line.size() < 2)
            return corners;
        for (int i = 0; i < line.size(); i++) {
            double[] before = i > 0 ? normal(line.get(i - 1), line.get(i)) : null;
            double[] after = i < line.size() - 1 ? normal(line.get(i), line.get(i + 1)) : null;
            double nx;
            double nz;
            if (before == null || after == null) {
                double[] only = before == null ? after : before;
                nx = only[0] * half;
                nz = only[1] * half;
            } else {
                // The mitre: halfway between the two normals, as long as reaches both edges.
                double mx = before[0] + after[0];
                double mz = before[1] + after[1];
                double length = Math.hypot(mx, mz);
                if (length < 1e-9) {
                    nx = after[0] * half;
                    nz = after[1] * half;
                } else {
                    mx /= length;
                    mz /= length;
                    double reach = half / Math.max(mx * after[0] + mz * after[1], 1 / MITRE_LIMIT);
                    nx = mx * reach;
                    nz = mz * reach;
                }
            }
            Spot at = line.get(i);
            corners.add(new double[] {at.x() + nx, at.z() + nz, at.x() - nx, at.z() - nz});
        }
        return corners;
    }

    /** The unit normal to the left of the way from {@code from} to {@code to}. */
    private static double[] normal(Spot from, Spot to) {
        double dx = to.x() - from.x();
        double dz = to.z() - from.z();
        double length = Math.hypot(dx, dz);
        return new double[] {-dz / length, dx / length};
    }

    /**
     * The runs of the line through {@code points} that are in {@code box}: a run goes on from one
     * piece to the next while neither is cut there, so that its bends are mitred as one line.
     */
    public static List<List<Spot>> runs(List<Spot> points, Box box) {
        List<List<Spot>> runs = new ArrayList<>();
        List<Spot> run = null;
        for (int i = 1; i < points.size(); i++) {
            Spot from = points.get(i - 1);
            Spot to = points.get(i);
            double[] cut = MapProjection.clip(from.x(), from.z(), to.x(), to.z(), box);
            if (cut == null) {
                run = null;
                continue;
            }
            boolean fromKept = same(cut[0], cut[1], from);
            boolean toKept = same(cut[2], cut[3], to);
            if (run == null || !fromKept) {
                run = new ArrayList<>();
                run.add(fromKept ? from : new Spot(cut[0], cut[1]));
                runs.add(run);
            }
            run.add(toKept ? to : new Spot(cut[2], cut[3]));
            if (!toKept)
                run = null;
        }
        return runs;
    }

    /** Whether a point the clip gave back is {@code point}, but for rounding. */
    private static boolean same(double x, double z, Spot point) {
        return Math.abs(x - point.x()) < 1e-9 && Math.abs(z - point.z()) < 1e-9;
    }
}
