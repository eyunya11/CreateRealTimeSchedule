package io.github.eyunya11.createrealtimeschedule.railway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.map.TrackMap;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Spot;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.TagPiece;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Track;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Edge;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Marker;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Place;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTags;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpec;

/** The stretches of the map's tracks that track tags are on. */
class NetworkMapTest {

    private static final Place BLOCK = new Place(0, 0, 0);
    private static final TrackTags TAGS = new TrackTags(Map.of(1, "Local", 2, "Rapid"), 3);

    private static Edge edge(double length, int start, int end, Integer... next) {
        return new Edge(length, false, List.of(next)).withNodes(start, end);
    }

    /**
     * A line of three nodes 0, 1, 2, 50 blocks apart: edges 0 (0 to 1) and 2 (1 to 2) east, 3 (2 to 1)
     * and 1 (1 to 0) west. The map has track 0 from node 0 to node 1 and track 1 from node 1 to node 2,
     * so the edges west run backward along them.
     */
    private static List<Edge> line() {
        return List.of(edge(50, 0, 1, 2), edge(50, 1, 0), edge(50, 1, 2), edge(50, 2, 1, 1));
    }

    private static NetworkMap map() {
        TrackMap map = new TrackMap(List.of("minecraft:overworld"),
            List.of(new Track("a", 0, List.of(new Spot(0, 0), new Spot(50, 0))), new Track("b", 0, List.of(new Spot(50, 0), new Spot(100, 0)))),
            List.of(), List.of(), List.of(), List.of());
        return new NetworkMap(map, List.of(0, 0, 1, 1), List.of(false, true, false, true));
    }

    private static List<List<TagPiece>> pieces(Marker... markers) {
        return map().tagPieces(new RailNetwork(line(), List.of(), List.of(), List.of(markers)), TAGS);
    }

    @Test
    void aSectionIsOnTheTracksItRunsAlongAsAShareOfTheirLength() {
        List<List<TagPiece>> pieces = pieces(new Marker(0, 20, List.of(1), false, BLOCK), new Marker(2, 30, List.of(2), false, BLOCK));
        assertEquals(List.of(new TagPiece(0.4, 1, List.of("Local"))), pieces.get(0));
        assertEquals(List.of(new TagPiece(0, 0.6, List.of("Local")), new TagPiece(0.6, 1, List.of("Rapid"))), pieces.get(1));
    }

    /** A one-way block facing west is on the edges that run backward along the tracks, and is turned round. */
    @Test
    void aSectionOfAnEdgeRunningBackwardIsTurnedRound() {
        List<List<TagPiece>> pieces = pieces(new Marker(3, 10, List.of(2), true, BLOCK));
        assertEquals(List.of(new TagPiece(0, 1, List.of("Rapid"))), pieces.get(0));
        assertEquals(List.of(new TagPiece(0, 0.8, List.of("Rapid"))), pieces.get(1));
    }

    /** Where sections overlap, the track has the tags of them all, in the list's order; a tag the list does not have is left out. */
    @Test
    void overlappingSectionsHaveTheTagsOfThemAll() {
        List<List<TagPiece>> pieces = pieces(new Marker(0, 10, List.of(2, 7), false, BLOCK), new Marker(1, 10, List.of(1), true, BLOCK));
        // The one-way block faces west 10 in along edge 1: from 40 back to 0 on track 0.
        assertEquals(List.of(new TagPiece(0, 0.2, List.of("Local")), new TagPiece(0.2, 0.8, List.of("Local", "Rapid")),
            new TagPiece(0.8, 1, List.of("Rapid"))), pieces.get(0));
        assertEquals(List.of(new TagPiece(0, 1, List.of("Rapid"))), pieces.get(1));
    }

    /** The network's export has the stretches on each track of the map, and the colours of the tags. */
    @Test
    void theExportHasTheStretchesOfTheTagsAndTheirColours() {
        TrackTags tags = new TrackTags(Map.of(1, "Local", 2, "Rapid"), 3, Map.of(2, "#123456"));
        RailNetwork network = new RailNetwork(line(), List.of(), List.of(), List.of(new Marker(0, 20, List.of(1), false, BLOCK)));
        String json = NetworkJson.write(network, tags, TrainSpec.STANDARD, 1.0, map());
        assertTrue(json.contains("\"tagColours\": {\"Local\": \"" + tags.colour(1) + "\", \"Rapid\": \"#123456\"}"), json);
        assertTrue(json.contains("\"points\": [0, 0, 50, 0], \"tags\": [{\"from\": 0.4, \"to\": 1, \"tags\": [\"Local\"]}]}"), json);
        assertTrue(json.contains("\"points\": [50, 0, 100, 0], \"tags\": [{\"from\": 0, \"to\": 1, \"tags\": [\"Local\"]}]}"), json);
    }
}
