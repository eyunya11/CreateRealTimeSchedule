package io.github.eyunya11.createrealtimeschedule.railway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTags;

/**
 * The map of a network's track, and which track of it each edge of the network is: both ways of a
 * stretch of track are two edges of the network and one track of the map.
 *
 * @param map          the map
 * @param trackOfEdge  for each edge of the network, by its index, the index of its track in the map, or
 *                     -1 if the map does not have it
 * @param backwardEdge for each edge of the network, by its index, whether it runs from the last point of
 *                     its track to the first
 */
public record NetworkMap(TrackMap map, List<Integer> trackOfEdge, List<Boolean> backwardEdge) {

    private static final double EPSILON = 1e-9;

    public NetworkMap {
        trackOfEdge = List.copyOf(trackOfEdge);
        backwardEdge = List.copyOf(backwardEdge);
    }

    /** A map whose edges all run the way of their tracks' points. */
    public NetworkMap(TrackMap map, List<Integer> trackOfEdge) {
        this(map, trackOfEdge, trackOfEdge.stream().map(track -> false).toList());
    }

    /**
     * The map, for a network whose edges are {@code edges} by their indexes, of which {@code trackOf}
     * gives the track; an edge it does not give is not on the map.
     *
     * @param backward the edges that run from the last point of their track to the first
     */
    public static <E> NetworkMap of(TrackMap map, List<E> edges, Map<E, Integer> trackOf, Set<E> backward) {
        List<Integer> trackOfEdge = new ArrayList<>();
        List<Boolean> backwardEdge = new ArrayList<>();
        for (E edge : edges) {
            trackOfEdge.add(trackOf.getOrDefault(edge, -1));
            backwardEdge.add(backward.contains(edge));
        }
        return new NetworkMap(map, trackOfEdge, backwardEdge);
    }

    /** The tracks a leg runs along, in order, each once for each time the leg comes onto it. */
    public List<Integer> tracks(Leg leg) {
        List<Integer> tracks = new ArrayList<>();
        for (int edge : leg.edges()) {
            int track = edge >= 0 && edge < trackOfEdge.size() ? trackOfEdge.get(edge) : -1;
            if (track >= 0 && (tracks.isEmpty() || tracks.getLast() != track))
                tracks.add(track);
        }
        return tracks;
    }

    /**
     * The stretches of each track of the map that the track tags of {@code network} are on, by the
     * track's index: the sections of both ways of it ({@link TagSections}) together, cut where the tags
     * change, with the names of the tags in {@code tags}, in its order. A tag the list does not have is
     * left out.
     */
    public List<List<TrackMap.TagPiece>> tagPieces(RailNetwork network, TrackTags tags) {
        TagSections sections = network.tagSections();
        List<List<double[]>> cuts = new ArrayList<>();
        List<List<Shared>> shares = new ArrayList<>();
        for (int i = 0; i < map.tracks().size(); i++) {
            cuts.add(new ArrayList<>());
            shares.add(new ArrayList<>());
        }
        for (int edge = 0; edge < trackOfEdge.size() && edge < network.edges().size(); edge++) {
            int track = trackOfEdge.get(edge);
            double length = network.edges().get(edge).length();
            if (track < 0 || track >= map.tracks().size() || !(length > 0))
                continue;
            for (TagSections.Span span : sections.spans(edge)) {
                double from = clamp(span.from() / length);
                double to = clamp(span.to() / length);
                if (backwardEdge.get(edge)) {
                    double start = clamp(1 - to);
                    to = clamp(1 - from);
                    from = start;
                }
                if (to - from > EPSILON)
                    shares.get(track).add(new Shared(from, to, span.tags()));
            }
        }
        List<List<TrackMap.TagPiece>> pieces = new ArrayList<>();
        for (List<Shared> onTrack : shares)
            pieces.add(pieces(onTrack, tags));
        return pieces;
    }

    /** A stretch of a track, as a share of its length, and the ids of its tags. */
    private record Shared(double from, double to, List<Integer> tags) {}

    /** {@code shared} cut where the tags on the track change, the stretches with the same tags side by side as one. */
    private static List<TrackMap.TagPiece> pieces(List<Shared> shared, TrackTags names) {
        TreeSet<Double> cuts = new TreeSet<>();
        for (Shared each : shared) {
            cuts.add(each.from());
            cuts.add(each.to());
        }
        List<TrackMap.TagPiece> pieces = new ArrayList<>();
        Double from = null;
        for (double to : cuts) {
            if (from != null && to - from > EPSILON) {
                double middle = (from + to) / 2;
                List<String> tags = new ArrayList<>();
                for (Map.Entry<Integer, String> tag : names.names().entrySet())
                    if (shared.stream().anyMatch(each -> each.from() <= middle && middle < each.to() && each.tags().contains(tag.getKey())))
                        tags.add(tag.getValue());
                if (!tags.isEmpty()) {
                    TrackMap.TagPiece last = pieces.isEmpty() ? null : pieces.getLast();
                    if (last != null && last.tags().equals(tags) && Math.abs(last.to() - from) <= EPSILON)
                        pieces.set(pieces.size() - 1, new TrackMap.TagPiece(last.from(), to, tags));
                    else
                        pieces.add(new TrackMap.TagPiece(from, to, tags));
                }
            }
            from = to;
        }
        return pieces;
    }

    /** {@code share} between 0 and 1, to a millionth, so that the same place reached either way is one cut. */
    private static double clamp(double share) {
        return Math.round(Math.max(0, Math.min(1, share)) * 1e6) / 1e6;
    }
}
