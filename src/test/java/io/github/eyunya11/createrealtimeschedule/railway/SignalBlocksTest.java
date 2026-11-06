package io.github.eyunya11.createrealtimeschedule.railway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Edge;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Point;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Station;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTags;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpec;

class SignalBlocksTest {

    private static final double DELTA = 1e-9;

    /**
     * 100 blocks one way, with signals at 25 and 60 and stations at 10, 40 and 80: block 0 up to the first
     * signal, a five-aspect light signal into block 1, and a cross signal into block 2.
     */
    static RailNetwork signalled() {
        Edge edge = new Edge(100, false, List.of(), List.of(), List.of(), -1,
            List.of(new Point(25, Point.Kind.SIGNAL, 0), new Point(60, Point.Kind.SIGNAL, 1)), -1, -1);
        SignalBlocks blocks = new SignalBlocks(
            List.of(List.of(new SignalBlocks.Change(0, 0), new SignalBlocks.Change(25, 1), new SignalBlocks.Change(60, 2))),
            List.of(new SignalBlocks.Signal(1, List.of(1), List.of(5), false), new SignalBlocks.Signal(2, List.of(), List.of(), true)),
            3, List.of(), SignalBlocks.LightSpeeds.DEFAULT);
        return new RailNetwork(List.of(edge),
            List.of(new Station("Alpha", 0, 10), new Station("Beta", 0, 40), new Station("Gamma", 0, 80)), List.of(), List.of(), blocks);
    }

    private static Leg legOf(RailNetwork network, String from, String to) {
        return network.legs().stream().filter(l -> l.from().equals(from) && l.to().equals(to)).findFirst().orElseThrow();
    }

    @Test
    void anEdgeIsInTheBlockOfTheLastSignalUpToAPosition() {
        SignalBlocks blocks = signalled().blocks();
        assertEquals(0, blocks.blockAt(0, 0));
        assertEquals(0, blocks.blockAt(0, 24.9));
        assertEquals(1, blocks.blockAt(0, 25), "at the signal, the block it leads into");
        assertEquals(2, blocks.blockAt(0, 100));
        assertEquals(-1, blocks.blockAt(1, 0), "an edge it does not know");
        assertEquals(-1, SignalBlocks.NONE.blockAt(0, 0));
    }

    @Test
    void aLegListsTheSignalsItPassesAndTheBlockItStartsIn() {
        RailNetwork network = signalled();
        Leg alphaGamma = legOf(network, "Alpha", "Gamma");
        assertEquals(0, alphaGamma.block());
        assertEquals(List.of(new Leg.PassedSignal(15, 0), new Leg.PassedSignal(50, 1)), alphaGamma.signals());
        Leg betaGamma = legOf(network, "Beta", "Gamma");
        assertEquals(1, betaGamma.block());
        assertEquals(1, betaGamma.signals().size());
        assertEquals(20, betaGamma.signals().getFirst().at(), DELTA);
    }

    @Test
    void aSignalAtTheStartIsBehindTheTrainAndOneAtTheEndIsPassed() {
        Edge edge = new Edge(100, false, List.of(), List.of(), List.of(), -1,
            List.of(new Point(10, Point.Kind.SIGNAL, 0), new Point(40, Point.Kind.SIGNAL, 1)), -1, -1);
        SignalBlocks blocks = new SignalBlocks(
            List.of(List.of(new SignalBlocks.Change(0, 0), new SignalBlocks.Change(10, 1), new SignalBlocks.Change(40, 2))),
            List.of(new SignalBlocks.Signal(1, List.of(1), List.of(), false), new SignalBlocks.Signal(2, List.of(), List.of(), false)),
            3, List.of(), null);
        RailNetwork network = new RailNetwork(List.of(edge), List.of(new Station("A", 0, 10), new Station("B", 0, 40)),
            List.of(), List.of(), blocks);
        Leg leg = legOf(network, "A", "B");
        assertEquals(1, leg.block(), "the signal at A is behind a train standing there");
        assertEquals(List.of(new Leg.PassedSignal(30, 1)), leg.signals());
    }

    @Test
    void aNetworkWithoutBlocksHasLegsWithoutSignals() {
        RailNetwork network = new RailNetwork(List.of(new Edge(60, false, List.of())), List.of(new Station("A", 0, 10), new Station("B", 0, 30)));
        Leg leg = legOf(network, "A", "B");
        assertEquals(-1, leg.block());
        assertEquals(List.of(), leg.signals());
    }

    @Test
    void blocksThatAreNotThereAreRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> new SignalBlocks(List.of(List.of(new SignalBlocks.Change(0, 3))), List.of(), 3, List.of(), null));
        assertThrows(IllegalArgumentException.class,
            () -> new SignalBlocks(List.of(), List.of(new SignalBlocks.Signal(0, List.of(1), List.of(), false)), 1, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new SignalBlocks.Signal(0, List.of(), List.of(7), false));
        assertThrows(IllegalArgumentException.class, () -> new SignalBlocks(List.of(), List.of(), 2, List.of(List.of(0)), null));
        assertThrows(IllegalArgumentException.class, () -> new RailNetwork(List.of(new Edge(60, false, List.of())), List.of(), List.of(),
            List.of(), new SignalBlocks(List.of(List.of(), List.of()), List.of(), 0, List.of(), null)));
    }

    /** The example the editor's tests read too (src/test/js). */
    @Test
    void aNetworkWithSignalsIsWrittenAsVersion5() throws IOException {
        String expected;
        try (InputStream in = SignalBlocksTest.class.getResourceAsStream("example-signals.json")) {
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        assertEquals(expected, NetworkJson.write(signalled(), new TrackTags(), TrainSpec.STANDARD, 1.0));
    }
}
