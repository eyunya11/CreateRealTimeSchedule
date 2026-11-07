package io.github.eyunya11.createrealtimeschedule.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.eyunya11.createrealtimeschedule.operation.TimetableTraffic;
import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import io.github.eyunya11.createrealtimeschedule.railway.SignalBlocks;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.TimetableJson;
import io.github.eyunya11.createrealtimeschedule.travel.SpeedSign;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpec;

/**
 * The shared cases of the simulation ({@code traffic/cases.json}), which the editor's {@code traffic.js}
 * is held to as well (src/test/js/traffic.test.js): the trips each case's trains make, run here by
 * {@link Traffic} and {@link TimetableTraffic}, have to be the ones the file says.
 *
 * <p>The file has {@code runs} cases, of trains given leg by leg, and {@code timetables} cases, of a
 * timetable in its JSON and the legs between its stations. A trip is {@code {departed, arrived, free,
 * waits: [{signal, from, ticks}]}}, with -1 for a leg not arrived at.
 */
class TrafficCasesTest {

    static JsonObject cases() throws IOException {
        try (InputStream in = TrafficCasesTest.class.getResourceAsStream("cases.json")) {
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @TestFactory
    Stream<DynamicTest> theTrainsOfEachCaseMakeTheTripsItSays() throws IOException {
        JsonObject root = cases();
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement each : root.getAsJsonArray("runs")) {
            JsonObject kase = each.getAsJsonObject();
            tests.add(DynamicTest.dynamicTest(kase.get("about").getAsString(), () -> {
                List<Traffic.Run> runs = new ArrayList<>();
                for (JsonElement run : kase.getAsJsonArray("runs"))
                    runs.add(run(run.getAsJsonObject()));
                Traffic.Result result = Traffic.run(blocks(kase.getAsJsonObject("blocks")), runs);
                JsonArray trips = new JsonArray();
                for (List<Traffic.Trip> ran : result.trips()) {
                    JsonArray of = new JsonArray();
                    ran.forEach(trip -> of.add(trip(trip)));
                    trips.add(of);
                }
                assertEquals(kase.get("trips"), trips);
            }));
        }
        for (JsonElement each : root.getAsJsonArray("timetables")) {
            JsonObject kase = each.getAsJsonObject();
            tests.add(DynamicTest.dynamicTest(kase.get("about").getAsString(), () -> {
                TimetableJson.Result read = TimetableJson.parse(kase.get("timetable").toString());
                assertTrue(read.ok(), read.problems().toString());
                Map<List<String>, Leg> legs = new HashMap<>();
                for (JsonElement leg : kase.getAsJsonArray("legs")) {
                    JsonObject way = leg.getAsJsonObject();
                    legs.put(List.of(way.get("from").getAsString(), way.get("to").getAsString(), way.get("tag").getAsString()), leg(way));
                }
                JsonObject defaults = kase.getAsJsonObject("defaults");
                TimetableTraffic traffic = TimetableTraffic.of(read.timetable(), legs, blocks(kase.getAsJsonObject("blocks")),
                    spec(kase.getAsJsonObject("spec")), defaults.get("minimumStop").getAsLong() * 20, defaults.get("length").getAsInt());
                assertEquals(kase.get("trips"), timetableTrips(read.timetable(), traffic));
            }));
        }
        assertFalse(tests.isEmpty());
        return tests.stream();
    }

    /**
     * The trips of every leg of every train of timetable that the train set out on, as the cases have them,
     * each with the ticks the signals add to it ({@link TimetableTraffic#signalTicks}).
     */
    static JsonArray timetableTrips(Timetable timetable, TimetableTraffic traffic) {
        JsonArray trips = new JsonArray();
        for (Timetable.Train train : timetable.trains())
            for (int stop = 0; stop + 1 < train.stops().size(); stop++) {
                Optional<Traffic.Trip> trip = traffic.trip(train.number(), stop);
                if (trip.isEmpty())
                    continue;
                JsonObject each = new JsonObject();
                each.addProperty("train", train.number());
                each.addProperty("stop", stop);
                each.add("trip", trip(trip.get()));
                each.addProperty("signalTicks", traffic.signalTicks(train.number(), stop));
                trips.add(each);
            }
        return trips;
    }

    static JsonObject trip(Traffic.Trip trip) {
        JsonObject made = new JsonObject();
        made.addProperty("departed", trip.departed());
        made.addProperty("arrived", trip.arrived());
        made.addProperty("free", trip.free());
        JsonArray waits = new JsonArray();
        for (Traffic.Wait wait : trip.waits()) {
            JsonObject each = new JsonObject();
            each.addProperty("signal", wait.signal());
            each.addProperty("from", wait.from());
            each.addProperty("ticks", wait.ticks());
            waits.add(each);
        }
        made.add("waits", waits);
        return made;
    }

    static SignalBlocks blocks(JsonObject blocks) {
        List<SignalBlocks.Signal> signals = new ArrayList<>();
        for (JsonElement each : blocks.getAsJsonArray("signals")) {
            JsonObject signal = each.getAsJsonObject();
            signals.add(new SignalBlocks.Signal(signal.get("block").getAsInt(), ints(signal.getAsJsonArray("next")),
                ints(signal.getAsJsonArray("lights")), signal.get("cross").getAsBoolean()));
        }
        List<List<Integer>> crossings = new ArrayList<>();
        for (JsonElement pair : blocks.getAsJsonArray("crossings"))
            crossings.add(ints(pair.getAsJsonArray()));
        SignalBlocks.LightSpeeds speeds = null;
        if (blocks.has("lightSpeeds")) {
            JsonObject kmh = blocks.getAsJsonObject("lightSpeeds");
            speeds = new SignalBlocks.LightSpeeds(kmh.get("restricted").getAsInt(), kmh.get("approach").getAsInt(),
                kmh.get("reduced").getAsInt(), kmh.get("sixProceed").getAsInt());
        }
        return new SignalBlocks(List.of(), signals, blocks.get("count").getAsInt(), crossings, speeds);
    }

    static Traffic.Run run(JsonObject run) {
        List<Traffic.Stint> stints = new ArrayList<>();
        for (JsonElement each : run.getAsJsonArray("stints")) {
            JsonObject stint = each.getAsJsonObject();
            stints.add(new Traffic.Stint(leg(stint.getAsJsonObject("leg")), stint.get("departs").getAsLong(),
                stint.get("minimumStop").getAsLong(), stint.get("newTrain").getAsBoolean(), stint.get("length").getAsDouble()));
        }
        return new Traffic.Run(run.get("name").getAsString(), spec(run.getAsJsonObject("spec")), run.get("throttle").getAsDouble(), stints,
            run.get("stays").getAsBoolean());
    }

    static TrainSpec spec(JsonObject spec) {
        return new TrainSpec(spec.get("topSpeed").getAsDouble(), spec.get("turnSpeed").getAsDouble(), spec.get("acceleration").getAsDouble());
    }

    static Leg leg(JsonObject leg) {
        List<Leg.Curve> curves = new ArrayList<>();
        for (JsonElement each : leg.getAsJsonArray("curves"))
            curves.add(new Leg.Curve(each.getAsJsonArray().get(0).getAsDouble(), each.getAsJsonArray().get(1).getAsDouble()));
        List<SpeedSign> signs = new ArrayList<>();
        for (JsonElement each : leg.getAsJsonArray("signs")) {
            JsonObject sign = each.getAsJsonObject();
            signs.add(sign.has("release") ? SpeedSign.release(sign.get("at").getAsDouble())
                : SpeedSign.limit(sign.get("at").getAsDouble(), sign.get("kmh").getAsInt()));
        }
        List<Leg.PassedSignal> signals = new ArrayList<>();
        for (JsonElement each : leg.getAsJsonArray("signals")) {
            JsonObject signal = each.getAsJsonObject();
            signals.add(new Leg.PassedSignal(signal.get("at").getAsDouble(), signal.get("signal").getAsInt()));
        }
        return new Leg("", "", leg.get("distance").getAsDouble(), curves, List.of(), signs, List.of(), signals, leg.get("block").getAsInt());
    }

    private static List<Integer> ints(JsonArray array) {
        List<Integer> made = new ArrayList<>();
        array.forEach(each -> made.add(each.getAsInt()));
        return made;
    }
}
