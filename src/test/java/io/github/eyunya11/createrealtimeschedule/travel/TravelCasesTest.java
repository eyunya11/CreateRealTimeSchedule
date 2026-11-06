package io.github.eyunya11.createrealtimeschedule.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The runs in {@code cases.json}, which the timetable editor's tests read too (src/test/js): the editor
 * works out the running time of a train that starts under a speed limit itself, and has to come to the
 * same tick as the mod.
 */
class TravelCasesTest {

    @TestFactory
    Stream<DynamicTest> eachRunTakesTheTicksItSays() throws IOException, URISyntaxException {
        Path file = Path.of(TravelCasesTest.class.getResource("cases.json").toURI());
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        List<JsonObject> cases = new ArrayList<>();
        for (JsonElement each : root.getAsJsonArray("cases"))
            cases.add(each.getAsJsonObject());
        assertFalse(cases.isEmpty(), "there are no cases");
        return cases.stream().map(kase -> DynamicTest.dynamicTest(kase.get("about").getAsString(), () -> {
            JsonObject train = kase.getAsJsonObject("train");
            TrainSpec spec = new TrainSpec(train.get("topSpeed").getAsDouble(), train.get("turnSpeed").getAsDouble(),
                train.get("acceleration").getAsDouble());
            double distance = kase.get("distance").getAsDouble();
            List<SpeedZone> zones = new ArrayList<>();
            if (kase.has("curves"))
                for (JsonElement curve : kase.getAsJsonArray("curves"))
                    zones.add(SpeedZone.curve(curve.getAsJsonArray().get(0).getAsDouble(), curve.getAsJsonArray().get(1).getAsDouble(), spec));
            List<SpeedSign> signs = new ArrayList<>();
            if (kase.has("signs"))
                for (JsonElement each : kase.getAsJsonArray("signs")) {
                    JsonObject sign = each.getAsJsonObject();
                    double at = sign.get("at").getAsDouble();
                    signs.add(sign.has("release") ? SpeedSign.release(at) : SpeedSign.limit(at, sign.get("kmh").getAsInt()));
                }
            OptionalInt start = kase.has("limitAtStart") ? OptionalInt.of(kase.get("limitAtStart").getAsInt()) : OptionalInt.empty();
            SpeedSigns all = new SpeedSigns(start, signs);
            assertEquals(OptionalInt.of(kase.get("ticks").getAsInt()),
                TravelTime.ticks(spec, kase.get("throttle").getAsDouble(), distance, zones, all));
            OptionalInt after = kase.has("limitAfter") ? OptionalInt.of(kase.get("limitAfter").getAsInt()) : OptionalInt.empty();
            assertEquals(after, all.limitAfter(distance), "the limit at the stop");
        }));
    }
}
