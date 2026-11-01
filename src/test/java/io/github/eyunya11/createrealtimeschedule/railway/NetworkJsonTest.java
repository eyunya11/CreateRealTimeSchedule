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

import io.github.eyunya11.createrealtimeschedule.map.TrackMap;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Carriage;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Lamp;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.SignalMark;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.SignalSide;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.SpeedMark;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Spot;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.StationMark;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Track;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.TrainMark;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Edge;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Station;
import io.github.eyunya11.createrealtimeschedule.travel.SpeedSign;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpec;

class NetworkJsonTest {

    private static final String EXAMPLE = resource("example.json");
    private static final String EXAMPLE_MAP = resource("example-map.json");

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

    /**
     * The three stations in a row on a straight track running east, and a curve off its east end
     * towards the south that no leg runs along, with a signal, two signs and a train. The editor's
     * tests read the same file.
     */
    static NetworkMap exampleMap() {
        String overworld = "minecraft:overworld";
        Track line = new Track("minecraft:overworld 0.5,2,3.5 60.5,2,3.5", 0, List.of(new Spot(0.5, 3.5), new Spot(60.5, 3.5)));
        Track curve = new Track("minecraft:overworld 60.5,2,3.5 70.5,2,13.5", 0,
            List.of(new Spot(60.5, 3.5), new Spot(66.36, 5.84), new Spot(68.66, 8.14), new Spot(70.5, 13.5)));
        Spot east = new Spot(1, 0);
        Spot west = new Spot(-1, 0);
        TrackMap map = new TrackMap(List.of(overworld), List.of(line, curve),
            List.of(new StationMark("Alpha", 0, new Spot(10.5, 3.5)), new StationMark("Beta", 0, new Spot(30.5, 3.5)),
                new StationMark("Gamma", 0, new Spot(50.5, 3.5))),
            List.of(new SignalMark("3f1c", 0, new Spot(40.5, 3.5),
                List.of(new SignalSide(east, "create", List.of(Lamp.GREEN)),
                    new SignalSide(west, "light4", List.of(Lamp.YELLOW, Lamp.GREEN))))),
            List.of(new SpeedMark(0, new Spot(20.5, 3.5), east, 60, false), new SpeedMark(1, new Spot(68.66, 8.14),
                new Spot(0.7071, 0.7071), 0, true)),
            List.of(new TrainMark("Train 1", List.of(new Carriage(new Spot(25.04, 3.5), new Spot(16.04, 3.5), 0, 0)))));
        return new NetworkMap(map, List.of(0));
    }

    @Test
    void withAMapItIsVersion3AndListsTheTracksOfEachLeg() {
        RailNetwork network = threeInARow();
        assertEquals(EXAMPLE_MAP, NetworkJson.write(network.names(), network.legs(), TrainSpec.STANDARD, 1.0, exampleMap()));
    }

    @Test
    void theExampleOfTheMapIsInTheProposal() throws IOException {
        String proposal = Files.readString(Path.of("docs", "proposal.md"), StandardCharsets.UTF_8).replace("\r\n", "\n");
        // The member as it is in the file, without the brace that closes the whole.
        String map = EXAMPLE_MAP.substring(EXAMPLE_MAP.indexOf("  \"map\""), EXAMPLE_MAP.stripTrailing().length() - 1);
        assertTrue(proposal.contains(map.strip()), "docs/proposal.md does not show the map of example-map.json");
    }

    @Test
    void aLegComingBackOntoATrackListsItAgainButNotTwiceInARow() {
        NetworkMap map = new NetworkMap(TrackMap.EMPTY, List.of(0, 0, 1, -1, 0));
        Leg leg = new Leg("A", "B", 10, List.of(), List.of(), List.of(), List.of(0, 1, 2, 3, 4));
        assertEquals(List.of(0, 1, 0), map.tracks(leg));
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
