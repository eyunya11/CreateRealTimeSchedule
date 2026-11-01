package io.github.eyunya11.createrealtimeschedule.railway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.OptionalInt;
import java.util.function.IntPredicate;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Edge;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Marker;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Place;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Station;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Way;
import io.github.eyunya11.createrealtimeschedule.railway.RouteSearch.Route;
import io.github.eyunya11.createrealtimeschedule.railway.RouteSearch.Start;
import io.github.eyunya11.createrealtimeschedule.railway.RouteSearch.Weights;

/** The way a train held to a track tag is sent: on track open to its tag, and at a junction the way with it. */
class ForcedTagSearchTest {

    private static final double DELTA = 1e-9;
    private static final Weights EMPTY = new Weights() {};
    private static final Place BLOCK = new Place(0, 0, 0);
    private static final OptionalInt NONE = OptionalInt.empty();
    private static final OptionalInt EXPRESS = OptionalInt.of(1);
    private static final OptionalInt LOCAL = OptionalInt.of(2);
    /** A tag no section has, as a timetable's tag that the world has no tag of is taken to be. */
    private static final OptionalInt UNKNOWN = OptionalInt.of(3);

    private static final int DEPOT = 0;
    private static final int A_MAIN = 1;
    private static final int A_SIDING = 2;
    private static final int B = 3;
    private static final int A_MAIN_WEST = 4;
    private static final int A_SIDING_WEST = 5;

    private static Edge edge(double length, int start, int end, Integer... next) {
        return new Edge(length, false, List.of(next)).withNodes(start, end);
    }

    private static Marker block(int edge, double offset, boolean oneWay, Integer... tags) {
        return new Marker(edge, offset, List.of(tags), oneWay, BLOCK);
    }

    /**
     * Out of the depot (edge 0, node 0 to 1) to a junction at node 1: the main line (edge 1, to node 2)
     * and a siding beside it (edge 2, to node 3), each 50 long with a platform A 30 in, and back
     * together at node 4 (edges 3 and 4, 10 and 12 long) on to B, 30 in on edge 5. The edges the other
     * way are 6 (from B to node 4, a junction), 7 and 8, 9 and 10, with platforms A 20 in, and 11 back
     * to the depot. The main line has the express's tag (1) from 5 in on edge 1 to 5 in on edge 3, and
     * the siding the local's (2) from 5 in on edge 2 to 5 in on edge 4, by blocks for both ways; or for
     * the siding, if {@code oneWaySiding}, by one-way blocks facing east.
     */
    static RailNetwork siding(boolean oneWaySiding) {
        return siding(oneWaySiding, true);
    }

    /** {@link #siding(boolean)}, with no tag on the main line unless {@code taggedMain}. */
    private static RailNetwork siding(boolean oneWaySiding, boolean taggedMain) {
        List<Edge> edges = List.of(
            edge(10, 0, 1, 1, 2), edge(50, 1, 2, 3), edge(50, 1, 3, 4), edge(10, 2, 4, 5), edge(12, 3, 4, 5), edge(40, 4, 5),
            edge(40, 5, 4, 7, 8), edge(10, 4, 2, 9), edge(12, 4, 3, 10), edge(50, 2, 1, 11), edge(50, 3, 1, 11), edge(10, 1, 0));
        List<Station> stations = List.of(new Station("Depot", 0, 0, 0), new Station("A", 1, 30, 1), new Station("A", 2, 30, 2),
            new Station("B", 5, 30, 3), new Station("A", 9, 20, 4), new Station("A", 10, 20, 5));
        List<Marker> siding = List.of(block(2, 5, oneWaySiding, 2), block(4, 5, oneWaySiding));
        List<Marker> markers = taggedMain ? List.of(block(1, 5, false, 1), block(3, 5, false), siding.get(0), siding.get(1)) : siding;
        return new RailNetwork(edges, stations, List.of(), markers);
    }

    private static IntPredicate named(RailNetwork network, String name) {
        return station -> network.stations().get(station).name().equals(name);
    }

    private static @Nullable Route from(RailNetwork network, Start start, String to, OptionalInt tag) {
        return RouteSearch.first(network, start, named(network, to), EMPTY, tag);
    }

    @Test
    void atAJunctionATrainHeldToATagTakesTheWayWithIt() {
        RailNetwork network = siding(false);
        Route local = from(network, new Start(0, 0), "A", LOCAL);
        assertNotNull(local);
        assertEquals(A_SIDING, local.station());
        assertEquals(List.of(0, 2), local.edges());
        assertEquals(List.of(2), local.junctions(), "the junction is set for the siding");
        Route express = from(network, new Start(0, 0), "A", EXPRESS);
        assertNotNull(express);
        assertEquals(A_MAIN, express.station());
        assertEquals(List.of(1), express.junctions());
        Route toB = from(network, new Start(0, 0), "B", LOCAL);
        assertNotNull(toB);
        assertEquals(List.of(0, 2, 4, 5), toB.edges(), "the local runs through the siding on to B");
        assertEquals(10 + 50 + 12 + 30, toB.distance(), DELTA);
    }

    /** The main line is open to the local and the shorter way, but at the junction it takes the siding, which has its tag. */
    @Test
    void atAJunctionATrainHeldToATagTakesTheWayWithItThoughAnotherIsOpen() {
        RailNetwork network = siding(false, false);
        Route plain = from(network, new Start(0, 0), "B", NONE);
        assertNotNull(plain);
        assertEquals(List.of(0, 1, 3, 5), plain.edges());
        Route local = from(network, new Start(0, 0), "B", LOCAL);
        assertNotNull(local);
        assertEquals(List.of(0, 2, 4, 5), local.edges());
        Route express = from(network, new Start(0, 0), "B", EXPRESS);
        assertNotNull(express);
        assertEquals(List.of(0, 1, 3, 5), express.edges(), "a train of a tag no way has goes either way, but not into the siding");
    }

    /**
     * A junction whose one way has the express's section and then the local's, and whose other way,
     * longer, has no section; both come to B. The local's section lies past the express's, which the
     * local may not run into, so the first way is no way to it: the local takes the other.
     */
    @Test
    void aWayWhoseTagLiesPastAStretchClosedToItIsNotTakenAtAJunction() {
        List<Edge> edges = List.of(edge(10, 0, 1, 1, 2), edge(50, 1, 2, 3), edge(60, 1, 3, 4), edge(10, 2, 4, 5), edge(10, 3, 4, 5),
            edge(40, 4, 5));
        List<Marker> markers = List.of(block(1, 5, false, 1), block(1, 30, false, 2), block(3, 5, false));
        RailNetwork network = new RailNetwork(edges, List.of(new Station("Depot", 0, 0, 0), new Station("B", 5, 30, 1)), List.of(), markers);
        assertEquals(List.of(), network.tagSections().problems());
        Route local = from(network, new Start(0, 0), "B", LOCAL);
        assertNotNull(local, "the local has a way round the other side");
        assertEquals(List.of(0, 2, 4, 5), local.edges());
        // The express's section comes first on that way, so the express takes it, and the local's closes it further on.
        assertNull(from(network, new Start(0, 0), "B", EXPRESS), "at a junction a train takes the way with its tag, even with no way on");
    }

    /**
     * With the siding's blocks one-way, A on the siding running west has no section, and so is open to
     * any tag, while both A running east are in sections. A set that came to A running east on another
     * tag is still timed on to B from there, as no A open to the tag has a way to B.
     */
    @Test
    void aLegIsTimedFromAPlatformClosedToTheTagWhereNoOpenOneHasAWay() {
        RailNetwork network = siding(true);
        Leg unknown = network.legs(List.of("A", "B"), UNKNOWN).getFirst();
        assertEquals(List.of("A", "B", 60.0), List.of(unknown.from(), unknown.to(), unknown.distance()));
        RailNetwork.Routes both = network.routes(List.of("Depot", "A", "B"), LOCAL);
        assertEquals(network.legs(List.of("Depot", "A", "B"), LOCAL), both.legs());
        assertEquals(network.ways(List.of("Depot", "A", "B"), LOCAL), both.ways());
    }

    @Test
    void aTrainHeldToATagNoSectionHasRunsOnTrackWithNoSectionOnly() {
        RailNetwork network = siding(false);
        assertNull(from(network, new Start(0, 0), "A", UNKNOWN), "both platforms are in sections of other tags");
        assertNull(from(network, new Start(0, 0), "B", UNKNOWN), "both ways to B go through them");
        assertNull(from(network, new Start(6, 0), "A", UNKNOWN), "nor the other way, as the blocks are for both ways");
    }

    @Test
    void aTrainHeldToNoTagGoesAsCreatesWouldWhateverTheTags() {
        RailNetwork tagged = siding(false);
        RailNetwork plain = new RailNetwork(tagged.edges(), tagged.stations());
        for (String name : List.of("A", "B")) {
            Route expected = RouteSearch.first(plain, new Start(0, 0), named(plain, name), EMPTY);
            assertEquals(expected, from(tagged, new Start(0, 0), name, NONE));
        }
        assertEquals(plain.legs(), tagged.legs(List.of("Depot", "A", "B"), NONE));
    }

    /** A train standing in a section of another tag may run on out of it, and no further into another. */
    @Test
    void aTrainMayLeaveTheSectionItStandsIn() {
        RailNetwork network = siding(false);
        Route out = from(network, new Start(1, 30), "B", LOCAL);
        assertNotNull(out, "the local on the main line leaves the express's section");
        assertEquals(List.of(1, 3, 5), out.edges());
        Route back = from(network, new Start(1, 30), "B", UNKNOWN);
        assertNotNull(back, "a train of a tag no section has leaves the section it stands in too");
    }

    @Test
    void aBlockForBothWaysHoldsForTrainsRunningTheOtherWay() {
        RailNetwork network = siding(false);
        Route local = from(network, new Start(6, 0), "A", LOCAL);
        assertNotNull(local);
        assertEquals(A_SIDING_WEST, local.station());
        Route express = from(network, new Start(6, 0), "A", EXPRESS);
        assertNotNull(express);
        assertEquals(A_MAIN_WEST, express.station());
    }

    @Test
    void aOneWayBlockDoesNotHoldForTrainsRunningTheOtherWay() {
        RailNetwork network = siding(true);
        assertNull(from(network, new Start(0, 0), "A", UNKNOWN), "running east, the siding has the local's tag");
        Route west = from(network, new Start(6, 0), "A", UNKNOWN);
        assertNotNull(west, "running west, the siding has no tag");
        assertEquals(A_SIDING_WEST, west.station());
    }

    /**
     * A train of a tag is timed from the platforms of a name it can come to: the local from A on the
     * siding, though it may leave the main line's A if it stands there, which is the shorter way.
     */
    @Test
    void aTaggedLegStartsAtAPlatformOpenToTheTag() {
        RailNetwork network = siding(false);
        Leg plain = network.legs(List.of("A", "B"), NONE).getFirst();
        assertEquals(List.of("A", "B", 60.0), List.of(plain.from(), plain.to(), plain.distance()));
        Leg local = network.legs(List.of("A", "B"), LOCAL).getFirst();
        assertEquals(List.of("A", "B", 62.0), List.of(local.from(), local.to(), local.distance()));
        assertEquals(List.of(), network.legs(List.of("Depot", "A"), UNKNOWN), "no train of a tag no section has gets to A");
    }

    @Test
    void theWaysFromEachPlatformSayWhereATrainOfATagComesTo() {
        RailNetwork network = siding(false);
        List<Way> local = network.ways(List.of("Depot", "A", "B"), LOCAL);
        // A train may leave the section it stands in, so the local gets to B from the main line's A too.
        // Nothing runs on from B, at the end of the line, nor west from A.
        assertEquals(List.of(new Way(DEPOT, "A", A_SIDING), new Way(DEPOT, "B", B), new Way(A_MAIN, "B", B), new Way(A_SIDING, "B", B)),
            local);
        assertEquals(List.of(new Way(DEPOT, "A", A_MAIN), new Way(DEPOT, "B", B), new Way(A_MAIN, "B", B), new Way(A_SIDING, "B", B)),
            network.ways(List.of("Depot", "A", "B"), EXPRESS));
    }
}
