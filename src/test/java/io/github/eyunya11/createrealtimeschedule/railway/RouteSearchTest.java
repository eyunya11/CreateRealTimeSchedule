package io.github.eyunya11.createrealtimeschedule.railway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Edge;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Point;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Station;
import io.github.eyunya11.createrealtimeschedule.railway.RouteSearch.Presence;
import io.github.eyunya11.createrealtimeschedule.railway.RouteSearch.Route;
import io.github.eyunya11.createrealtimeschedule.railway.RouteSearch.SignalState;
import io.github.eyunya11.createrealtimeschedule.railway.RouteSearch.Start;
import io.github.eyunya11.createrealtimeschedule.railway.RouteSearch.Weights;

/** The way a train is sent, weighed as Create's search weighs it. */
class RouteSearchTest {

    private static final double DELTA = 1e-9;
    private static final Weights EMPTY = new Weights() {};

    private static Edge straight(double length, Integer... next) {
        return new Edge(length, false, List.of(next));
    }

    private static IntPredicate named(RailNetwork network, String name) {
        return station -> network.stations().get(station).name().equals(name);
    }

    /**
     * Out of the depot (edge 0, 10 long) to a junction: straight on (edge 1) to a platform A 90 in, 104
     * from the start of the depot edge, and off to the side (edge 2) to another platform A 96 in, 106.
     * The way to each ends at the end of its edge: 104 and 110.
     */
    private static RailNetwork twoPlatforms() {
        return new RailNetwork(List.of(straight(10, 1, 2), straight(94), straight(100)),
            List.of(new Station("Depot", 0, 0, 0), new Station("A", 1, 90, 1), new Station("A", 2, 96, 2)));
    }

    @Test
    void aTrainIsSentToTheNearerPlatformWhenBothAreFree() {
        RailNetwork network = twoPlatforms();
        Route route = RouteSearch.first(network, new Start(0, 0), named(network, "A"), EMPTY);
        assertNotNull(route);
        assertEquals(1, route.station());
        assertEquals(10 + 90, route.distance(), DELTA);
        assertEquals(List.of(0, 1), route.edges());
        assertEquals(List.of(1), route.junctions(), "the edge taken at the junction steers the train");
    }

    @Test
    void aPlatformWithAnotherTrainWeighs300AndTheTrainGoesToTheFreeOne() {
        RailNetwork network = twoPlatforms();
        Weights occupied = new Weights() {
            @Override
            public Presence presence(int platform) {
                return platform == 0 ? Presence.OWN : platform == 1 ? Presence.OTHER : Presence.NONE;
            }
        };
        Route route = RouteSearch.first(network, new Start(0, 0), named(network, "A"), occupied);
        assertNotNull(route);
        assertEquals(2, route.station());
        assertEquals(List.of(2), route.junctions());
        assertEquals(0, route.cost(), DELTA, "the depot the train stands at, and the platform taken, weigh nothing on the way");
    }

    /**
     * Two ways from A to B: across edge 1 (40 long) with station C on it, or across edge 2 (70 long)
     * with none. Each station passed weighs 50, more than the 30 blocks between the ways.
     */
    private static RailNetwork aStationOnTheShorterWay() {
        return new RailNetwork(List.of(straight(10, 1, 2), straight(40, 3), straight(70, 3), straight(20)),
            List.of(new Station("A", 0, 0), new Station("C", 1, 20), new Station("B", 3, 10)));
    }

    @Test
    void aStationPassedWeighs50SoTheWayRoundItIsTaken() {
        RailNetwork network = aStationOnTheShorterWay();
        Route route = RouteSearch.first(network, new Start(0, 0), named(network, "B"), EMPTY);
        assertNotNull(route);
        assertEquals(List.of(0, 2, 3), route.edges());
        assertEquals(10 + 70 + 10, route.distance(), DELTA);
        // The legs the running times are worked out from go the same way.
        Leg leg = network.legs().stream().filter(l -> l.from().equals("A") && l.to().equals("B")).findFirst().orElseThrow();
        assertEquals(90, leg.distance(), DELTA);
        assertEquals(List.of(), leg.via());
    }

    @Test
    void theOwnStationOfTheTrainWeighsNothing() {
        // A train standing at C, which is passed on the way to B, is not weighed for it.
        RailNetwork network = new RailNetwork(List.of(straight(100)),
            List.of(new Station("C", 0, 10, 0), new Station("D", 0, 30, 1), new Station("B", 0, 80, 2)));
        Weights atC = new Weights() {
            @Override
            public Presence presence(int platform) {
                return platform == 0 ? Presence.OWN : Presence.NONE;
            }
        };
        Route route = RouteSearch.first(network, new Start(0, 10), named(network, "B"), atC);
        assertNotNull(route);
        // On the train's own edge a station is taken at once, with its length to the end of the edge
        // and the weight of the stations before it: D, not C.
        assertEquals(70, route.distance(), DELTA);
        assertEquals(90 + 50, route.cost(), DELTA);
    }

    @Test
    void aSignalHeldRedWeighs400AndOneWithATrainInItsBlockLessEachTime() {
        // Three signals on the straight way, and a way round 600 longer with none.
        Edge throughSignals = straight(100, 3).withPoints(List.of(
            new Point(10, Point.Kind.SIGNAL, 0), new Point(20, Point.Kind.SIGNAL, 1), new Point(30, Point.Kind.SIGNAL, 2)));
        RailNetwork network = new RailNetwork(List.of(straight(10, 1, 2), throughSignals, straight(700, 3), straight(20)),
            List.of(new Station("A", 0, 0), new Station("B", 3, 10)));
        Weights allTaken = new Weights() {
            @Override
            public SignalState signal(int signal) {
                return SignalState.OCCUPIED;
            }

            @Override
            public int firstSignalWeight() {
                return 100;
            }
        };
        Route through = RouteSearch.first(network, new Start(0, 0), named(network, "B"), allTaken);
        assertNotNull(through);
        // A, which the train is not said to stand at, weighs 50 as well.
        assertEquals(50 + 100 + 50 + 25, through.cost(), DELTA, "each block with a train in it weighs half the one before");
        Weights heldRed = new Weights() {
            @Override
            public SignalState signal(int signal) {
                return signal == 1 ? SignalState.HELD_RED : SignalState.CLEAR;
            }
        };
        Route red = RouteSearch.first(network, new Start(0, 0), named(network, "B"), heldRed);
        assertNotNull(red);
        assertEquals(List.of(0, 1, 3), red.edges(), "400 is less than the 600 blocks round");
        assertEquals(50 + 400, red.cost(), DELTA);
    }

    /**
     * Platforms of one name on two ways from a junction: straight on, an edge 100 long ending 100
     * blocks away with A 80 in; and round a long curve, an edge 105 long ending only 50 blocks from
     * the junction, with A 100 in. Create's estimate of the way left, which takes off twice the length
     * of the edge a station is on, puts the curve first.
     */
    private static RailNetwork twoPlatformsWhereTheNodesAre(boolean placed) {
        List<Edge> edges = List.of(straight(10, 1, 2).withNodes(0, 1), straight(100).withNodes(1, 2), straight(105).withNodes(1, 3));
        List<Station> stations = List.of(new Station("Depot", 0, 0, 0), new Station("A", 1, 80, 1), new Station("A", 2, 100, 2));
        List<RailNetwork.Place> places = List.of(new RailNetwork.Place(0, 0, 0), new RailNetwork.Place(10, 0, 0),
            new RailNetwork.Place(110, 0, 0), new RailNetwork.Place(10, 0, 50));
        return new RailNetwork(edges, stations, placed ? places : List.of());
    }

    @Test
    void ofTwoPlatformsTheOneCreatesEstimateOfTheWayLeftPutsFirstIsTaken() {
        RailNetwork placed = twoPlatformsWhereTheNodesAre(true);
        Route route = RouteSearch.first(placed, new Start(0, 0), named(placed, "A"), EMPTY);
        assertNotNull(route);
        assertEquals(2, route.station(), "Create orders the straight at 110 - 20 = 90 and the curve at 115 - 80 = 35");
        // Not knowing where the nodes are, the search goes by the length to the ends of the edges: 110 before 115.
        RailNetwork unplaced = twoPlatformsWhereTheNodesAre(false);
        Route plain = RouteSearch.first(unplaced, new Start(0, 0), named(unplaced, "A"), EMPTY);
        assertNotNull(plain);
        assertEquals(1, plain.station());
    }

    @Test
    void anEdgeTheTrainCannotRunOnOrAnotherTrainIsOnIsWeighedOrLeftOut() {
        RailNetwork network = twoPlatforms();
        Weights noStraight = new Weights() {
            @Override
            public boolean canUse(int edge) {
                return edge != 1;
            }
        };
        Route route = RouteSearch.first(network, new Start(0, 0), named(network, "A"), noStraight);
        assertNotNull(route);
        assertEquals(2, route.station());
        Weights trainOnStraight = new Weights() {
            @Override
            public int edge(int edge) {
                return edge == 1 ? 350 : 0;
            }
        };
        route = RouteSearch.first(network, new Start(0, 0), named(network, "A"), trainOnStraight);
        assertNotNull(route);
        assertEquals(2, route.station());
    }

    @Test
    void aSignalForTrainsTheOtherWayEndsTheWayButNotTheStationsBeforeIt() {
        RailNetwork network = new RailNetwork(List.of(straight(10, 1), straight(100, 2).withBarriers(List.of(50.0)), straight(20)),
            List.of(new Station("A", 0, 0), new Station("B", 1, 40), new Station("C", 2, 10)));
        assertNotNull(RouteSearch.first(network, new Start(0, 0), named(network, "B"), EMPTY));
        assertNull(RouteSearch.first(network, new Start(0, 0), named(network, "C"), EMPTY));
    }

    @Test
    void theWayToEveryNameIsTheFirstStationOfThatNameReached() {
        RailNetwork network = twoPlatforms();
        Map<String, Route> routes = RouteSearch.toEveryName(network, new Start(0, 0), station -> station != 0, EMPTY);
        assertEquals(1, routes.get("A").station());
    }

    @Test
    void ofTheWaysForwardAndBackTheOneOfLessCostIsTaken() {
        // The train stands in the middle of a line, a station each way: B ahead past a station C, D
        // behind with none. Backing up is on the edge the other way.
        RailNetwork network = new RailNetwork(List.of(straight(100), straight(100)),
            List.of(new Station("C", 0, 60), new Station("B", 0, 90), new Station("B", 1, 80)));
        Start front = new Start(0, 50);
        Start back = new Start(1, 50);
        IntPredicate toB = named(network, "B");
        Route either = RouteSearch.find(network, front, back, true, true, toB, EMPTY);
        assertNotNull(either);
        assertTrue(either.signedDistance() < 0, "backing up passes no station, so it costs less");
        assertEquals(30, either.distance(), DELTA);
        Route forwardOnly = RouteSearch.find(network, front, back, true, false, toB, EMPTY);
        assertNotNull(forwardOnly);
        assertEquals(40, forwardOnly.signedDistance(), DELTA);
        assertNull(RouteSearch.find(network, front, null, false, false, toB, EMPTY), "a train no one drives goes nowhere");
    }
}
