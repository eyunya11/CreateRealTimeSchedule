package io.github.eyunya11.createrealtimeschedule.map;

import java.util.Arrays;

/**
 * The key of a track: what a timetable names a track of the map by, to show it or hide it. Create
 * gives its edges no name that lasts, so a track is named by where its two ends are, which stays the
 * same as long as no one lays track at them. A track laid on from one of its ends gets another key,
 * and a timetable that named the old one names a track that is not there any more.
 *
 * <p>The ends are Create's node locations, which are on a grid of half blocks: Create keeps them as
 * whole numbers twice the block's. The key is the dimension and the two ends, the lower first, in
 * blocks: {@code minecraft:overworld 3.5,2,3.5 46.5,2,3.5}.
 */
public final class TrackKeys {

    private TrackKeys() {}

    /**
     * @param dimension the dimension, such as {@code minecraft:overworld}
     * @param one       one end, as Create's x, y and z of the node: twice the block's
     * @param other     the other end, the same way
     */
    public static String of(String dimension, int[] one, int[] other) {
        int[] low = Arrays.compare(one, other) <= 0 ? one : other;
        int[] high = low == one ? other : one;
        return dimension + " " + place(low) + " " + place(high);
    }

    private static String place(int[] doubled) {
        return half(doubled[0]) + "," + half(doubled[1]) + "," + half(doubled[2]);
    }

    /** A number of half blocks, in blocks: a whole number, or one and a half. */
    private static String half(int doubled) {
        if (doubled % 2 == 0)
            return Integer.toString(doubled / 2);
        // Integer division rounds towards zero, which for -3 gives -1: "-1.5" is wanted.
        return (doubled < 0 ? "-" : "") + Math.abs(doubled) / 2 + ".5";
    }
}
