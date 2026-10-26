package io.github.eyunya11.createrealtimeschedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The cases in {@code cases/}, which the timetable editor's tests read too (src/test/js): each is a
 * timetable and what is wrong with it, word for word. The mod and the editor have to say the same, so
 * that a timetable the editor finds nothing wrong with is one the mod takes.
 */
class TimetableCasesTest {

    static List<Path> cases() throws IOException, URISyntaxException {
        Path folder = Path.of(TimetableCasesTest.class.getResource("cases").toURI());
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    @TestFactory
    Stream<DynamicTest> eachCaseHasTheProblemsItSays() throws IOException, URISyntaxException {
        List<Path> cases = cases();
        assertFalse(cases.isEmpty(), "there are no cases");
        return cases.stream().map(file -> DynamicTest.dynamicTest(file.getFileName().toString(), () -> {
            JsonObject kase = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            List<String> expected = new ArrayList<>();
            for (JsonElement problem : kase.getAsJsonArray("problems"))
                expected.add(problem.getAsString());
            TimetableJson.Result result = TimetableJson.parse(kase.get("timetable").toString());
            assertEquals(expected, result.problems(), kase.get("about").getAsString());
            assertEquals(expected.isEmpty(), result.ok(), "whether the timetable is taken");
        }));
    }
}
