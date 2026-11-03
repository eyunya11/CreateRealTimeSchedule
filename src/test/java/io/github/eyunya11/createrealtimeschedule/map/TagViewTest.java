package io.github.eyunya11.createrealtimeschedule.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.eyunya11.createrealtimeschedule.map.TagView.Segment;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Spot;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.TagPiece;

/**
 * The stretches of track a map shows for the tags turned off. The cases are shared with the editor's
 * tests ({@code map.test.js}), which hold {@code map.js} to the same results.
 */
class TagViewTest {

    static List<Path> cases() throws IOException, URISyntaxException {
        Path folder = Path.of(TagViewTest.class.getResource("tagviews").toURI());
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(file -> file.toString().endsWith(".json")).sorted().toList();
        }
    }

    @TestFactory
    Stream<DynamicTest> eachCaseShowsTheStretchesItSays() throws IOException, URISyntaxException {
        List<Path> cases = cases();
        assertFalse(cases.isEmpty(), "there are no cases");
        return cases.stream().map(file -> DynamicTest.dynamicTest(file.getFileName().toString(), () -> {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            List<String> keys = new ArrayList<>();
            List<List<TagPiece>> pieces = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("tracks")) {
                JsonObject track = element.getAsJsonObject();
                keys.add(track.get("key").getAsString());
                List<TagPiece> ofTrack = new ArrayList<>();
                for (JsonElement piece : track.getAsJsonArray("tags")) {
                    JsonObject one = piece.getAsJsonObject();
                    ofTrack.add(new TagPiece(one.get("from").getAsDouble(), one.get("to").getAsDouble(), strings(one.getAsJsonArray("tags"))));
                }
                pieces.add(ofTrack);
            }
            for (JsonElement element : root.getAsJsonArray("cases")) {
                JsonObject one = element.getAsJsonObject();
                Set<String> off = new HashSet<>(strings(one.getAsJsonArray("off")));
                List<List<Segment>> expected = new ArrayList<>();
                for (JsonElement track : one.getAsJsonArray("segments")) {
                    List<Segment> segments = new ArrayList<>();
                    for (JsonElement segment : track.getAsJsonArray()) {
                        JsonArray parts = segment.getAsJsonArray();
                        segments.add(new Segment(parts.get(0).getAsDouble(), parts.get(1).getAsDouble(), parts.get(2).getAsBoolean(),
                            parts.get(3).getAsBoolean(), strings(parts.get(4).getAsJsonArray())));
                    }
                    expected.add(segments);
                }
                assertEquals(expected, TagView.segments(keys, pieces, off), "the stretches shown with " + off + " off");
            }
        }));
    }

    private static List<String> strings(JsonArray array) {
        List<String> strings = new ArrayList<>();
        for (JsonElement element : array)
            strings.add(element.getAsString());
        return strings;
    }

    @Test
    void aPlaceIsAsFarAlongATrackAsTheNearestPointOnIt() {
        List<Spot> points = List.of(new Spot(0, 0), new Spot(30, 0), new Spot(30, 10));
        assertEquals(0.5, TagView.shareOf(points, new Spot(20, 3)), 1e-9);
        assertEquals(1, TagView.shareOf(points, new Spot(40, 20)), 1e-9);
        assertEquals(0, TagView.shareOf(List.of(new Spot(5, 5), new Spot(5, 5)), new Spot(1, 1)), 1e-9);
    }

    @Test
    void aPlaceIsShownWhereAStretchShownHasIt() {
        List<Segment> segments = List.of(new Segment(0, 0.5, true, false, List.of()), new Segment(0.5, 1, false, true, List.of()));
        assertFalse(TagView.shownAt(segments, 0.2));
        assertTrue(TagView.shownAt(segments, 0.5), "at the end of a stretch shown");
        assertTrue(TagView.shownAt(segments, 0.8));
    }

    @Test
    void aTrainIsShownIfATagItRunsOnIsOn() {
        assertTrue(TagView.trainShown(List.of("Local", ""), Set.of("Local")), "the stretch with no tag it runs on is on");
        assertFalse(TagView.trainShown(List.of("Local"), Set.of("Local")));
        assertTrue(TagView.trainShown(List.of(), Set.of("Local")), "a train on no tag is shown while no tag is on");
        assertFalse(TagView.trainShown(List.of(), Set.of(TagView.NO_TAG)));
    }

    @Test
    void aPieceOfATrackRunsFromWhereItStartsToWhereItEndsThroughThePointsBetween() {
        List<Spot> points = List.of(new Spot(0, 0), new Spot(10, 0), new Spot(10, 10));
        assertEquals(List.of(new Spot(5, 0), new Spot(10, 0), new Spot(10, 5)), TagView.piece(points, 0.25, 0.75));
        assertEquals(points, TagView.piece(points, 0, 1));
    }

    @Test
    void aTrackIsCutIntoDashesOfTheLengthGivenTheLastShorter() {
        List<Spot> points = List.of(new Spot(0, 0), new Spot(10, 0));
        assertEquals(List.of(List.of(new Spot(0, 0), new Spot(4, 0)), List.of(new Spot(4, 0), new Spot(8, 0)), List.of(new Spot(8, 0), new Spot(10, 0))),
            TagView.dashes(points, 4));
        assertEquals(List.of(points), TagView.dashes(points, 20), "a line shorter than a dash is one");
    }
}
