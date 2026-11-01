package io.github.eyunya11.createrealtimeschedule.railway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.eyunya11.createrealtimeschedule.map.TrackMap;

/**
 * The map of a network's track, and which track of it each edge of the network is: both ways of a
 * stretch of track are two edges of the network and one track of the map.
 *
 * @param map         the map
 * @param trackOfEdge for each edge of the network, by its index, the index of its track in the map, or
 *                    -1 if the map does not have it
 */
public record NetworkMap(TrackMap map, List<Integer> trackOfEdge) {

    public NetworkMap {
        trackOfEdge = List.copyOf(trackOfEdge);
    }

    /**
     * The map, for a network whose edges are {@code edges} by their indexes, of which {@code trackOf}
     * gives the track; an edge it does not give is not on the map.
     */
    public static <E> NetworkMap of(TrackMap map, List<E> edges, Map<E, Integer> trackOf) {
        List<Integer> trackOfEdge = new ArrayList<>();
        for (E edge : edges)
            trackOfEdge.add(trackOf.getOrDefault(edge, -1));
        return new NetworkMap(map, trackOfEdge);
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
}
