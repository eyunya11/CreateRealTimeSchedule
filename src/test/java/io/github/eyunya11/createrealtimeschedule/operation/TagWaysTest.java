package io.github.eyunya11.createrealtimeschedule.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.TimetableJson;

/**
 * The ways between stops a train cannot run on its track tag. The cases in {@code ways/}, which the
 * editor's tests read too (src/test/js), give the platforms and the ways from each as the network the
 * mod writes has them, a timetable, and the ways it has none for: the two have to find the same.
 */
class TagWaysTest {

    /** The platforms and ways of a case's network, as {@code network.js} reads them from the mod's network. */
    private static TagWays.Track track(JsonObject network) {
        List<String> tags = new ArrayList<>();
        for (JsonElement tag : network.getAsJsonArray("tags"))
            tags.add(tag.getAsString());
        List<String> platforms = new ArrayList<>();
        for (JsonElement platform : network.getAsJsonArray("platforms"))
            platforms.add(platform.getAsJsonObject().get("station").getAsString());
        return new TagWays.Track() {
            @Override
            public List<Integer> platforms(String station) {
                List<Integer> found = new ArrayList<>();
                for (int i = 0; i < platforms.size(); i++)
                    if (platforms.get(i).equals(station))
                        found.add(i);
                return found;
            }

            @Override
            public int way(int platform, String station, String tag) {
                for (JsonElement element : network.getAsJsonArray("ways")) {
                    JsonObject way = element.getAsJsonObject();
                    boolean other = way.has("otherTag") && way.get("otherTag").getAsBoolean();
                    String of = way.has("tag") ? way.get("tag").getAsString() : "";
                    boolean forTag = tag.isEmpty() || tags.contains(tag) ? !other && of.equals(tag) : other;
                    if (forTag && way.get("from").getAsInt() == platform && way.getAsJsonObject("to").has(station))
                        return way.getAsJsonObject("to").get(station).getAsInt();
                }
                return -1;
            }
        };
    }

    static List<Path> cases() throws IOException, URISyntaxException {
        Path folder = Path.of(TagWaysTest.class.getResource("ways").toURI());
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    @TestFactory
    Stream<DynamicTest> eachCaseHasTheWaysWithNoneItSays() throws IOException, URISyntaxException {
        List<Path> cases = cases();
        assertFalse(cases.isEmpty(), "there are no cases");
        return cases.stream().map(file -> DynamicTest.dynamicTest(file.getFileName().toString(), () -> {
            JsonObject kase = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            TimetableJson.Result result = TimetableJson.parse(kase.get("timetable").toString());
            assertEquals(List.of(), result.problems());
            List<TagWays.NoWay> expected = new ArrayList<>();
            for (JsonElement element : kase.getAsJsonArray("noWays")) {
                JsonObject way = element.getAsJsonObject();
                expected.add(new TagWays.NoWay(way.get("train").getAsString(), way.get("stop").getAsInt(), way.get("from").getAsString(),
                    way.get("to").getAsString(), way.get("tag").getAsString()));
            }
            assertEquals(expected, TagWays.of(result.timetable(), track(kase.getAsJsonObject("network"))), kase.get("about").getAsString());
        }));
    }

    @Test
    void theTagsATimetableHoldsTrainsToAreThoseOfTheWaysTheyRun() throws IOException, URISyntaxException {
        Path siding = cases().stream().filter(file -> file.getFileName().toString().equals("siding.json")).findFirst().orElseThrow();
        JsonObject kase = JsonParser.parseString(Files.readString(siding, StandardCharsets.UTF_8)).getAsJsonObject();
        Timetable timetable = TimetableJson.parse(kase.get("timetable").toString()).timetable();
        assertEquals(List.of("local", "express", "freight"), TagWays.tags(timetable));
        assertEquals(List.of("freight"), TagWays.unknown(timetable, List.of("express", "local")));
        assertTrue(TagWays.unknown(OperationTimetables.shuttle(), List.of()).isEmpty(), "a timetable with no tags has none the world lacks");
    }
}
