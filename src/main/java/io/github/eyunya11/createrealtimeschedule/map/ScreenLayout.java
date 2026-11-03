package io.github.eyunya11.createrealtimeschedule.map;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * How map screen blocks side by side make screens. The blocks of a group, facing the same way in one
 * wall and touching, are cells of a grid: {@code u} to the right as one looks at them, {@code v} up.
 * A group that fills a rectangle of at most a given number of cells each way (the server's config,
 * {@link #DEFAULT_MOST} unless it says otherwise) is one screen across all of them; any other group is
 * a screen of one block for each of its blocks, so that a screen being built, or one with a block taken
 * out, still shows something.
 */
public final class ScreenLayout {

    /** The most blocks a screen has each way, until the config says otherwise. */
    public static final int DEFAULT_MOST = 32;
    /** The most the config allows: each block of a screen works out its part from all the track again when the map changes. */
    public static final int MOST_ALLOWED = 64;

    private ScreenLayout() {}

    /**
     * How many blocks of a group to look for, so that a wall of them does not keep the server looking:
     * one more than the largest screen, so that a group too large is never taken for a whole screen.
     */
    public static int cellsToFind(int most) {
        return most * most + 1;
    }

    /** A cell of the grid. */
    public record Cell(int u, int v) {}

    /**
     * Where a block is on its screen, and the screen's size, in blocks.
     *
     * @param column the block's column, from the left
     * @param row    the block's row, from the bottom
     */
    public record Place(int column, int row, int width, int height) {
        /** The screen of one block. */
        public static final Place ALONE = new Place(0, 0, 1, 1);
    }

    /** The place of each cell of a group, for screens of at most {@code most} blocks each way. */
    public static Map<Cell, Place> of(Set<Cell> group, int most) {
        Map<Cell, Place> places = new HashMap<>();
        if (group.isEmpty())
            return places;
        int minU = Integer.MAX_VALUE;
        int minV = Integer.MAX_VALUE;
        int maxU = Integer.MIN_VALUE;
        int maxV = Integer.MIN_VALUE;
        for (Cell cell : group) {
            minU = Math.min(minU, cell.u());
            minV = Math.min(minV, cell.v());
            maxU = Math.max(maxU, cell.u());
            maxV = Math.max(maxV, cell.v());
        }
        int width = maxU - minU + 1;
        int height = maxV - minV + 1;
        boolean rectangle = width <= most && height <= most && (long) width * height == group.size();
        for (Cell cell : group)
            places.put(cell, rectangle ? new Place(cell.u() - minU, cell.v() - minV, width, height) : Place.ALONE);
        return places;
    }
}
