package io.github.eyunya11.createrealtimeschedule.map;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Spot;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.TagPiece;

/**
 * Which stretches of the map's tracks are shown for the track tags turned off, and in which tags'
 * colours. The editor's {@code map.js} works it out the same way.
 *
 * <p>A stretch with tags is shown if one of its tags is on, in the colours of the tags on. A stretch
 * with no tag is shown if "no tag" ({@link #NO_TAG}) is on. If it is off, track with no tag is shown
 * only where it joins stretches shown: the track with no tag is taken a run at a time, a run being the
 * track with no tag that hangs together between stretches with tags, and a run is shown if it meets
 * stretches shown in two places or more, such as the junctions between two stretches of a tag. A run
 * that meets them in one place only is not, and nor is a stretch that leads nowhere else, such as a
 * siding off a run that is shown, or a way to a tag turned off. Two tracks meet where their ends are
 * at the same place, as their keys ({@link TrackKeys}) say.
 *
 * <p>A train is shown if a tag it runs on is on, "no tag" for a train that runs on none or that no
 * timetable runs; and then only where it is on a stretch shown.
 */
public final class TagView {

    /** What "no tag" is named among the tags turned off. */
    public static final String NO_TAG = "";

    private static final double EPSILON = 1e-9;

    private TagView() {}

    /**
     * A stretch of a track, as a share of its length from its first point.
     *
     * @param tagged whether tags are on it
     * @param shown  whether it is shown
     * @param tags   the tags on it that are on, whose colours it is drawn in; none for a stretch with no
     *               tag, or one not shown
     */
    public record Segment(double from, double to, boolean tagged, boolean shown, List<String> tags) {
        public Segment {
            tags = List.copyOf(tags);
        }
    }

    /**
     * The stretches of each track, by the track's index, from its first point to its last.
     *
     * @param keys   the key of each track
     * @param pieces the stretches of each track that tags are on, as the network has them; a track past
     *               the end has none
     * @param off    the tags turned off, {@link #NO_TAG} among them for "no tag"
     */
    public static List<List<Segment>> segments(List<String> keys, List<List<TagPiece>> pieces, Set<String> off) {
        boolean noTagOn = !off.contains(NO_TAG);
        List<List<Segment>> all = new ArrayList<>();
        for (int track = 0; track < keys.size(); track++) {
            List<Segment> segments = new ArrayList<>();
            double at = 0;
            for (TagPiece piece : track < pieces.size() ? pieces.get(track) : List.<TagPiece>of()) {
                if (piece.from() - at > EPSILON)
                    segments.add(new Segment(at, piece.from(), false, noTagOn, List.of()));
                List<String> on = piece.tags().stream().filter(tag -> !off.contains(tag)).toList();
                segments.add(new Segment(piece.from(), piece.to(), true, !on.isEmpty(), on));
                at = piece.to();
            }
            if (1 - at > EPSILON || segments.isEmpty())
                segments.add(new Segment(at, 1, false, noTagOn, List.of()));
            all.add(segments);
        }
        if (noTagOn)
            return all;

        // The track with no tag, as stretches between places: the ends of tracks, by the places their keys
        // give, and the places along a track where a stretch with tags starts or ends.
        Set<String> meeting = new HashSet<>();
        List<int[]> bare = new ArrayList<>();
        List<String[]> joins = new ArrayList<>();
        Map<String, List<Integer>> bareAt = new HashMap<>();
        for (int track = 0; track < all.size(); track++) {
            String[] ends = ends(keys.get(track), track);
            List<Segment> segments = all.get(track);
            for (int k = 0; k < segments.size(); k++) {
                Segment segment = segments.get(k);
                String first = segment.from() <= EPSILON ? "end " + ends[0] : "track " + track + " " + segment.from();
                String last = segment.to() >= 1 - EPSILON ? "end " + ends[1] : "track " + track + " " + segment.to();
                if (segment.tagged()) {
                    if (segment.shown()) {
                        meeting.add(first);
                        meeting.add(last);
                    }
                    continue;
                }
                int number = bare.size();
                bare.add(new int[] {track, k});
                joins.add(new String[] {first, last});
                bareAt.computeIfAbsent(first, place -> new ArrayList<>()).add(number);
                bareAt.computeIfAbsent(last, place -> new ArrayList<>()).add(number);
            }
        }
        // Take away the stretches that lead nowhere, one after another from the end, so that a siding goes
        // whole: a stretch with a place that no other stretch with no tag reaches and no stretch shown meets.
        Map<String, Integer> reaching = new HashMap<>();
        for (String[] join : joins)
            for (String place : join)
                reaching.merge(place, 1, Integer::sum);
        boolean[] gone = new boolean[bare.size()];
        ArrayDeque<String> leaves = new ArrayDeque<>();
        for (Map.Entry<String, Integer> place : reaching.entrySet())
            if (place.getValue() == 1 && !meeting.contains(place.getKey()))
                leaves.add(place.getKey());
        while (!leaves.isEmpty()) {
            String place = leaves.poll();
            for (int i : bareAt.get(place)) {
                if (gone[i])
                    continue;
                gone[i] = true;
                for (String other : joins.get(i))
                    if (reaching.merge(other, -1, Integer::sum) == 1 && !meeting.contains(other))
                        leaves.add(other);
            }
        }
        // Of what is left, a run that meets stretches shown in two places or more is shown. Runs are not
        // joined where they meet a stretch shown: a siding from there that leads nowhere else stays a run
        // of its own, and meets them in one place only.
        int[] parent = new int[bare.size()];
        for (int i = 0; i < parent.length; i++)
            parent[i] = i;
        for (Map.Entry<String, List<Integer>> place : bareAt.entrySet()) {
            if (meeting.contains(place.getKey()))
                continue;
            List<Integer> together = place.getValue();
            int first = -1;
            for (int i : together)
                if (!gone[i]) {
                    if (first < 0)
                        first = i;
                    else
                        union(parent, first, i);
                }
        }
        Map<Integer, Set<String>> meets = new HashMap<>();
        for (int i = 0; i < bare.size(); i++)
            if (!gone[i])
                for (String place : joins.get(i))
                    if (meeting.contains(place))
                        meets.computeIfAbsent(find(parent, i), run -> new HashSet<>()).add(place);
        for (int i = 0; i < bare.size(); i++) {
            if (gone[i] || meets.getOrDefault(find(parent, i), Set.of()).size() < 2)
                continue;
            List<Segment> segments = all.get(bare.get(i)[0]);
            Segment segment = segments.get(bare.get(i)[1]);
            segments.set(bare.get(i)[1], new Segment(segment.from(), segment.to(), false, true, List.of()));
        }
        return all;
    }

    /** The places of the two ends of a track by its key: its dimension and each end; a key that is not one gives places of its own. */
    private static String[] ends(String key, int track) {
        String[] parts = key.split(" ");
        if (parts.length != 3)
            return new String[] {"track " + track + " start", "track " + track + " end"};
        return new String[] {parts[0] + " " + parts[1], parts[0] + " " + parts[2]};
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i)
            i = parent[i] = parent[parent[i]];
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }

    /** Whether the place {@code share} of the way along a track is on a stretch shown. */
    public static boolean shownAt(List<Segment> segments, double share) {
        for (Segment segment : segments)
            if (segment.shown() && segment.from() - EPSILON <= share && share <= segment.to() + EPSILON)
                return true;
        return false;
    }

    /** How far along a line through {@code points} the place nearest {@code at} is, as a share of its length. */
    public static double shareOf(List<Spot> points, Spot at) {
        double total = 0;
        double best = Double.POSITIVE_INFINITY;
        double bestAlong = 0;
        for (int i = 0; i + 1 < points.size(); i++) {
            Spot a = points.get(i);
            Spot b = points.get(i + 1);
            double dx = b.x() - a.x();
            double dz = b.z() - a.z();
            double length = Math.hypot(dx, dz);
            double t = length == 0 ? 0 : Math.max(0, Math.min(1, ((at.x() - a.x()) * dx + (at.z() - a.z()) * dz) / (length * length)));
            double distance = Math.hypot(a.x() + dx * t - at.x(), a.z() + dz * t - at.z());
            if (distance < best) {
                best = distance;
                bestAlong = total + t * length;
            }
            total += length;
        }
        return total == 0 ? 0 : bestAlong / total;
    }

    /** Whether a train that runs on {@code tags} is shown: one of them on, {@link #NO_TAG} for none. */
    public static boolean trainShown(List<String> tags, Set<String> off) {
        if (tags.isEmpty())
            return !off.contains(NO_TAG);
        return tags.stream().anyMatch(tag -> !off.contains(tag));
    }
}
