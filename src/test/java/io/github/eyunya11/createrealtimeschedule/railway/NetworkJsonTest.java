package io.github.eyunya11.createrealtimeschedule.railway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Edge;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Station;
import io.github.eyunya11.createrealtimeschedule.travel.SpeedSign;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpec;

class NetworkJsonTest {

    private static final String EXAMPLE = resource("example.json");

    private static String resource(String name) {
        try (InputStream in = NetworkJsonTest.class.getResourceAsStream(name)) {
            // A checkout on Windows may have turned the line ends into CR LF.
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String write(RailNetwork network, double throttle) {
        return NetworkJson.write(network.names(), network.legs(), TrainSpec.STANDARD, throttle);
    }

    private static RailNetwork threeInARow() {
        return new RailNetwork(List.of(new Edge(60, false, List.of())),
            List.of(new Station("Alpha", 0, 10), new Station("Beta", 0, 30), new Station("Gamma", 0, 50)));
    }

    @Test
    void writesTheStationsAndTheLegsWithTheirRunningTimes() {
        // 20 and 40 blocks at full throttle are two of the runs that were measured on a real train.
        assertEquals(EXAMPLE, write(threeInARow(), 1.0));
    }

    /** The proposal shows this example; if it is not found, the test fails, as there is nothing to compare. */
    @Test
    void theExampleInTheProposalIsTheExampleHere() throws IOException {
        String proposal = Files.readString(Path.of("docs", "proposal.md"), StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(proposal.contains(EXAMPLE.strip()), "docs/proposal.md does not show the example of the network JSON as it is in example.json");
    }

    @Test
    void aLegWithCurvesListsThemAsStartAndEnd() {
        RailNetwork network = new RailNetwork(
            List.of(new Edge(40, false, List.of(1)), new Edge(30, true, List.of(2)), new Edge(40, false, List.of())),
            List.of(new Station("A", 0, 10), new Station("B", 2, 15)));
        String json = write(network, 1.0);
        JsonObject leg = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("legs").get(0).getAsJsonObject();
        assertEquals(75, leg.get("distance").getAsDouble(), 1e-9);
        JsonArray curves = leg.getAsJsonArray("curves");
        assertEquals(1, curves.size());
        assertEquals(30, curves.get(0).getAsJsonArray().get(0).getAsDouble(), 1e-9);
        assertEquals(60, curves.get(0).getAsJsonArray().get(1).getAsDouble(), 1e-9);
        assertTrue(json.contains("\"curves\": [[30, 60]]"), json);
    }

    @Test
    void aLegWithSignsListsThemWithTheirLimitsAndCountsThemInItsTime() {
        RailNetwork network = new RailNetwork(
            List.of(new Edge(1100, false, List.of()).withSigns(List.of(SpeedSign.limit(410, 40), SpeedSign.release(710)))),
            List.of(new Station("A", 0, 10), new Station("B", 0, 1010)));
        String json = write(network, 1.0);
        // Too wide for one line, the leg is laid out a member to a line, and the signs stay on theirs.
        assertTrue(json.contains("\"signs\": [{\"at\": 400, \"kmh\": 40}, {\"at\": 700, \"release\": true}],\n"), json);
        assertTrue(json.contains("\"ticks\": 1293\n"), json);
        JsonArray signs = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("legs").get(0).getAsJsonObject()
            .getAsJsonArray("signs");
        assertEquals(2, signs.size());
        assertEquals(40, signs.get(0).getAsJsonObject().get("kmh").getAsInt());
        assertTrue(signs.get(1).getAsJsonObject().get("release").getAsBoolean());
        assertFalse(signs.get(1).getAsJsonObject().has("kmh"));
    }

    @Test
    void aLegWithoutSignsHasNoSignsMember() {
        assertFalse(write(threeInARow(), 1.0).contains("signs"));
    }

    @Test
    void distancesAreGivenToAHundredthWithoutNeedlessZeros() {
        RailNetwork network = new RailNetwork(List.of(new Edge(100, false, List.of())),
            List.of(new Station("A", 0, 0), new Station("B", 0, 12.5), new Station("C", 0, 20.005), new Station("D", 0, 20.0049999),
                new Station("E", 0, 33.3333333)));
        String json = write(network, 1.0);
        assertTrue(json.contains("\"to\": \"B\", \"distance\": 12.5,"), json);
        assertTrue(json.contains("\"to\": \"C\", \"distance\": 20.01,"), json);
        assertTrue(json.contains("\"to\": \"D\", \"distance\": 20,"), json);
        assertTrue(json.contains("\"to\": \"E\", \"distance\": 33.33,"), json);
    }

    @Test
    void theTrainsSpecAndThrottleAreWrittenSoThatTheTimesCanBeChecked() {
        String json = NetworkJson.write(List.of(), List.of(), new TrainSpec(40, 20, 3), 0.25);
        JsonObject train = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("train");
        assertEquals(40, train.get("topSpeed").getAsDouble(), 1e-9);
        assertEquals(20, train.get("turnSpeed").getAsDouble(), 1e-9);
        assertEquals(3, train.get("acceleration").getAsDouble(), 1e-9);
        assertEquals(0.25, train.get("throttle").getAsDouble(), 1e-9);
    }

    @Test
    void aLegWithoutATimeHasNoTicksMember() {
        String json = write(threeInARow(), 0);
        assertFalse(json.contains("ticks"), json);
        assertEquals(3, JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("legs").size());
    }

    @Test
    void anEmptyNetworkIsWritten() {
        String json = NetworkJson.write(List.of(), List.of(), TrainSpec.STANDARD, 1.0);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("createrealtimeschedule.network", root.get("format").getAsString());
        assertEquals(0, root.getAsJsonArray("stations").size());
        assertEquals(0, root.getAsJsonArray("legs").size());
    }

    @Test
    void namesWithQuotesAndNonAsciiCharactersAreWrittenAsValidJson() {
        RailNetwork network = new RailNetwork(List.of(new Edge(60, false, List.of())),
            List.of(new Station("Say \"hi\"", 0, 10), new Station("中央駅", 0, 30)));
        String json = write(network, 1.0);
        assertTrue(json.contains("中央駅"), json);
        JsonObject leg = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("legs").get(0).getAsJsonObject();
        assertEquals("Say \"hi\"", leg.get("from").getAsString());
    }
}
