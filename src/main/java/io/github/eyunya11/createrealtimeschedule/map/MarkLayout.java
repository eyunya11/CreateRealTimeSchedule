package io.github.eyunya11.createrealtimeschedule.map;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where to put the things a map screen draws beside the track (the heads of the signals, the
 * speed-limit signs and the stations' names) so that they do not lie over the track, over what is on
 * it, or over one another. Each thing has places it may go, best first; it takes the first that is
 * clear of what is fixed (the track, and the marks on it) and of what has been put before it. A thing
 * with no clear place goes in its best place anyway, or, if it may be left out (a name), is left out.
 * Things are boxes that may lie at a slant, as a signal's head along slanting track does, and a piece
 * of track is a long thin box.
 */
public final class MarkLayout {

    /** The side of the squares the boxes are sorted into, so that a box is tested only against those near it. */
    private static final double CELL = 0.25;

    private MarkLayout() {}

    /**
     * A box on the screen: its middle, the way of its length (a unit vector), and how far it reaches
     * each side of its middle along its length and across it.
     */
    public record Rect(double u, double v, double du, double dv, double halfAlong, double halfAcross) {

        /** The upright box of {@code halfU} and {@code halfV} each side of ({@code u}, {@code v}). */
        public static Rect around(double u, double v, double halfU, double halfV) {
            return new Rect(u, v, 1, 0, halfU, halfV);
        }

        /** The box of {@code halfAlong} and {@code halfAcross} each side of ({@code u}, {@code v}), lengthways along ({@code du}, {@code dv}). */
        public static Rect along(double u, double v, double du, double dv, double halfAlong, double halfAcross) {
            double length = Math.hypot(du, dv);
            return length < 1e-12 ? around(u, v, halfAlong, halfAcross) : new Rect(u, v, du / length, dv / length, halfAlong, halfAcross);
        }

        /** A line from one point to another, {@code half} each side of it. */
        public static Rect line(double u1, double v1, double u2, double v2, double half) {
            return along((u1 + u2) / 2, (v1 + v2) / 2, u2 - u1, v2 - v1, Math.hypot(u2 - u1, v2 - v1) / 2, half);
        }

        /** How far it reaches each way along u and v. */
        double reachU() {
            return Math.abs(du) * halfAlong + Math.abs(dv) * halfAcross;
        }

        double reachV() {
            return Math.abs(dv) * halfAlong + Math.abs(du) * halfAcross;
        }

        /** Whether the two boxes share more than an edge: no line along a side of either parts them. */
        public boolean overlaps(Rect other) {
            return !parted(du, dv, halfAlong, other) && !parted(-dv, du, halfAcross, other)
                && !other.parted(other.du, other.dv, other.halfAlong, this) && !other.parted(-other.dv, other.du, other.halfAcross, this);
        }

        /** Whether the two boxes lie apart along the way (au, av), one of this box's own, which it reaches {@code mine} along. */
        private boolean parted(double au, double av, double mine, Rect other) {
            double gap = Math.abs((other.u - u) * au + (other.v - v) * av);
            double theirs = Math.abs(other.du * au + other.dv * av) * other.halfAlong + Math.abs(-other.dv * au + other.du * av) * other.halfAcross;
            return gap >= mine + theirs - 1e-12;
        }
    }

    /**
     * A thing to put.
     *
     * @param places    where it may go, best first
     * @param droppable whether it is left out if no place is clear
     */
    public record Item(List<Rect> places, boolean droppable) {
        public Item {
            places = List.copyOf(places);
            if (places.isEmpty())
                throw new IllegalArgumentException("a thing with nowhere to go");
        }
    }

    /** The place each item takes, by its index in its places, or -1 for one left out. */
    public static int[] place(List<Rect> fixed, List<Item> items) {
        Grid taken = new Grid();
        fixed.forEach(taken::add);
        int[] chosen = new int[items.size()];
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            chosen[i] = item.droppable() ? -1 : 0;
            for (int k = 0; k < item.places().size(); k++)
                if (taken.clear(item.places().get(k))) {
                    chosen[i] = k;
                    break;
                }
            if (chosen[i] >= 0)
                taken.add(item.places().get(chosen[i]));
        }
        return chosen;
    }

    /** The boxes taken, by the squares they reach into. */
    private static final class Grid {
        private final Map<Long, List<Rect>> cells = new HashMap<>();

        void add(Rect rect) {
            forCells(rect, key -> cells.computeIfAbsent(key, unused -> new ArrayList<>()).add(rect));
        }

        boolean clear(Rect rect) {
            boolean[] clear = {true};
            forCells(rect, key -> {
                if (clear[0])
                    for (Rect other : cells.getOrDefault(key, List.of()))
                        if (rect.overlaps(other)) {
                            clear[0] = false;
                            return;
                        }
            });
            return clear[0];
        }

        private static void forCells(Rect rect, java.util.function.LongConsumer each) {
            long fromU = (long) Math.floor((rect.u() - rect.reachU()) / CELL);
            long toU = (long) Math.floor((rect.u() + rect.reachU()) / CELL);
            long fromV = (long) Math.floor((rect.v() - rect.reachV()) / CELL);
            long toV = (long) Math.floor((rect.v() + rect.reachV()) / CELL);
            for (long cu = fromU; cu <= toU; cu++)
                for (long cv = fromV; cv <= toV; cv++)
                    each.accept(cu * 0x1_0000_0000L + (cv & 0xFFFF_FFFFL));
        }
    }
}
