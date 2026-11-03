package io.github.eyunya11.createrealtimeschedule.map;

import java.util.ArrayList;
import java.util.List;

/**
 * Where to put the things a map screen draws beside the track (the heads of the signals, the
 * speed-limit signs and the stations' names) so that they do not lie over one another or over what is
 * on the track. Each thing has places it may go, best first; it takes the first that is clear of what
 * is fixed and of what has been put before it. A thing with no clear place goes in its best place
 * anyway, or, if it may be left out (a name), is left out.
 */
public final class MarkLayout {

    private MarkLayout() {}

    /** A box on the screen. */
    public record Rect(double minU, double minV, double maxU, double maxV) {

        /** The box of {@code halfU} and {@code halfV} each side of ({@code u}, {@code v}). */
        public static Rect around(double u, double v, double halfU, double halfV) {
            return new Rect(u - halfU, v - halfV, u + halfU, v + halfV);
        }

        /** Whether the two boxes share more than an edge. */
        public boolean overlaps(Rect other) {
            return minU < other.maxU && other.minU < maxU && minV < other.maxV && other.minV < maxV;
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
        List<Rect> taken = new ArrayList<>(fixed);
        int[] chosen = new int[items.size()];
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            chosen[i] = item.droppable() ? -1 : 0;
            for (int k = 0; k < item.places().size(); k++)
                if (clear(item.places().get(k), taken)) {
                    chosen[i] = k;
                    break;
                }
            if (chosen[i] >= 0)
                taken.add(item.places().get(chosen[i]));
        }
        return chosen;
    }

    private static boolean clear(Rect rect, List<Rect> taken) {
        for (Rect other : taken)
            if (rect.overlaps(other))
                return false;
        return true;
    }
}
