package io.github.eyunya11.createrealtimeschedule.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.TimetableJson;

/**
 * The cases in {@code cases/}, which the editor's tests read too (src/test/js/map.test.js): the tracks
 * of a map, the legs between the stations with the tracks they run along, a timetable, and for each set
 * of lines shown the tracks the map shows.
 */
class MapSelectionTest {

    static List<Path> cases() throws IOException, URISyntaxException {
        Path folder = Path.of(MapSelectionTest.class.getResource("cases").toURI());
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(file -> file.toString().endsWith(".json")).sorted().toList();
        }
    }

    @TestFactory
    Stream<DynamicTest> eachCaseShowsTheTracksItSays() throws IOException, URISyntaxException {
        List<Path> cases = cases();
        assertFalse(cases.isEmpty(), "there are no cases");
        return cases.stream().map(file -> DynamicTest.dynamicTest(file.getFileName().toString(), () -> {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            List<String> keys = strings(root.getAsJsonArray("tracks"));
            List<MapSelection.LegTracks> legs = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("legs")) {
                JsonObject leg = element.getAsJsonObject();
                legs.add(new MapSelection.LegTracks(leg.get("from").getAsString(), leg.get("to").getAsString(),
                    leg.has("tag") ? leg.get("tag").getAsString() : "", leg.has("otherTag") && leg.get("otherTag").getAsBoolean(),
                    integers(leg.getAsJsonArray("tracks"))));
            }
            TimetableJson.Result read = TimetableJson.parse(root.get("timetable").toString());
            assertEquals(List.of(), read.problems(), "the case's timetable");
            Timetable timetable = read.timetable();
            assertNotNull(timetable);
            for (JsonElement element : root.getAsJsonArray("cases")) {
                JsonObject one = element.getAsJsonObject();
                List<String> lines = strings(one.getAsJsonArray("lines"));
                assertEquals(integers(one.getAsJsonArray("visible")), MapSelection.visible(keys, legs, timetable, lines),
                    "the tracks shown for the lines " + lines);
            }
            assertEquals(strings(root.getAsJsonArray("missing")), MapSelection.missing(keys, timetable), "the keys not on the map");
        }));
    }

    @Test
    void withNoTimetableEveryTrackIsShown() {
        assertEquals(List.of(0, 1), MapSelection.visible(List.of("a", "b"), List.of(), null, List.of("l")));
    }

    private static List<String> strings(JsonArray array) {
        List<String> out = new ArrayList<>();
        array.forEach(element -> out.add(element.getAsString()));
        return out;
    }

    private static List<Integer> integers(JsonArray array) {
        List<Integer> out = new ArrayList<>();
        array.forEach(element -> out.add(element.getAsInt()));
        return out;
    }
}
