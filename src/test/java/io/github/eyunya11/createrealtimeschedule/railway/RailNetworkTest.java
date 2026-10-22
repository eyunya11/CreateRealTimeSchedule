package io.github.eyunya11.createrealtimeschedule.railway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Edge;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Station;
import io.github.eyunya11.createrealtimeschedule.travel.SpeedSign;
import io.github.eyunya11.createrealtimeschedule.travel.SpeedZone;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpec;
import io.github.eyunya11.createrealtimeschedule.travel.TravelTime;

class RailNetworkTest {

    private static final double DELTA = 1e-9;

    private static Edge straight(double length, Integer... next) {
        return new Edge(length, false, List.of(next));
    }

    private static Edge curved(double length, Integer... next) {
        return new Edge(length, true, List.of(next));
    }

    private static RailNetwork network(List<Edge> edges, Station... stations) {
        return new RailNetwork(edges, List.of(stations));
    }

    private static Optional<Leg> leg(RailNetwork network, String from, String to) {
        return network.legs().stream().filter(l -> l.from().equals(from) && l.to().equals(to)).findFirst();
    }

    private static Leg legOf(RailNetwork network, String from, String to) {
        return leg(network, from, to).orElseThrow(() -> new AssertionError("no leg from " + from + " to " + to + " in " + network.legs()));
    }

    private static List<String> pairs(RailNetwork network) {
        List<String> pairs = new ArrayList<>();
        for (Leg leg : network.legs())
            pairs.add(leg.from() + ">" + leg.to());
        return pairs;
    }

    // ------------------------------------------------------------------ straight track

    @Test
    void stationsInARowOnOneEdgeAreReachedInTheDirectionOfTheTrack() {
        RailNetwork network = network(List.of(straight(60)),
            new Station("A", 0, 10), new Station("B", 0, 30), new Station("C", 0, 50));
        assertEquals(List.of("A>B", "A>C", "B>C"), pairs(network));
        assertEquals(20, legOf(network, "A", "B").distance(), DELTA);
        assertEquals(40, legOf(network, "A", "C").distance(), DELTA);
        assertEquals(20, legOf(network, "B", "C").distance(), DELTA);
    }

    @Test
    void aStationPassedOnTheWayIsNamedAsVia() {
        RailNetwork network = network(List.of(straight(60)),
            new Station("A", 0, 10), new Station("B", 0, 30), new Station("C", 0, 50));
        assertEquals(List.of("B"), legOf(network, "A", "C").via());
        assertEquals(List.of(), legOf(network, "A", "B").via());
    }

    @Test
    void stationsAcrossSeveralEdgesAddUpTheLengthsOfTheEdgesBetween() {
        RailNetwork network = network(List.of(straight(40, 1), straight(30, 2), straight(50)),
            new Station("A", 0, 10), new Station("B", 1, 5), new Station("C", 2, 20));
        assertEquals(35, legOf(network, "A", "B").distance(), DELTA);
        assertEquals(30 + 30 + 20, legOf(network, "A", "C").distance(), DELTA);
        assertEquals(List.of("B"), legOf(network, "A", "C").via());
        assertEquals(25 + 20, legOf(network, "B", "C").distance(), DELTA);
    }

    @Test
    void aStationAtTheVeryStartOfAnEdgeIsPassedAndReached() {
        RailNetwork network = network(List.of(straight(40, 1), straight(30)),
            new Station("A", 0, 10), new Station("B", 1, 0), new Station("C", 1, 30));
        assertEquals(30, legOf(network, "A", "B").distance(), DELTA);
        assertEquals(List.of("B"), legOf(network, "A", "C").via());
    }

    @Test
    void nothingIsReachedAgainstTheDirectionOfTheTrackOrFromTheEndOfIt() {
        RailNetwork network = network(List.of(straight(60)), new Station("A", 0, 10), new Station("B", 0, 30));
        assertTrue(leg(network, "B", "A").isEmpty());
        assertEquals(List.of("A>B"), pairs(network));
    }

    // ------------------------------------------------------------------ curves

    @Test
    void aCurveBetweenTwoStationsIsAZoneMeasuredFromTheStart() {
        RailNetwork network = network(List.of(straight(40, 1), curved(30, 2), straight(40)),
            new Station("A", 0, 10), new Station("B", 2, 15));
        Leg leg = legOf(network, "A", "B");
        assertEquals(75, leg.distance(), DELTA);
        assertEquals(List.of(new Leg.Curve(30, 60)), leg.curves());
    }

    @Test
    void curvesThatTouchAreOneZoneAndOnesThatDoNotAreTwo() {
        RailNetwork joined = network(List.of(straight(10, 1), curved(30, 2), curved(20, 3), straight(40)),
            new Station("A", 0, 0), new Station("B", 3, 5));
        assertEquals(List.of(new Leg.Curve(10, 60)), legOf(joined, "A", "B").curves());

        RailNetwork apart = network(List.of(straight(10, 1), curved(30, 2), straight(5, 3), curved(20, 4), straight(40)),
            new Station("A", 0, 0), new Station("B", 4, 5));
        assertEquals(List.of(new Leg.Curve(10, 40), new Leg.Curve(45, 65)), legOf(apart, "A", "B").curves());
    }

    @Test
    void aStationInTheMiddleOfACurveEndsTheZoneThere() {
        RailNetwork network = network(List.of(straight(40, 1), curved(30)),
            new Station("A", 0, 10), new Station("B", 1, 12));
        assertEquals(List.of(new Leg.Curve(30, 42)), legOf(network, "A", "B").curves());
    }

    @Test
    void aTrainThatStartsInACurveHasItFromTheStart() {
        RailNetwork network = network(List.of(curved(40, 1), straight(30)),
            new Station("A", 0, 10), new Station("B", 1, 20));
        assertEquals(List.of(new Leg.Curve(0, 30)), legOf(network, "A", "B").curves());
        assertEquals(50, legOf(network, "A", "B").distance(), DELTA);
    }

    @Test
    void straightTrackHasNoCurves() {
        RailNetwork network = network(List.of(straight(60)), new Station("A", 0, 10), new Station("B", 0, 30));
        assertEquals(List.of(), legOf(network, "A", "B").curves());
    }

    // ------------------------------------------------------------------ choosing the way

    @Test
    void aSwitchGivesALegToEachBranchAndNoneBetweenThem() {
        RailNetwork network = network(List.of(straight(40, 1, 2), straight(30), straight(50)),
            new Station("A", 0, 10), new Station("B", 1, 20), new Station("C", 2, 20));
        assertEquals(List.of("A>B", "A>C"), pairs(network));
        assertEquals(50, legOf(network, "A", "B").distance(), DELTA);
        assertEquals(50, legOf(network, "A", "C").distance(), DELTA);
    }

    @Test
    void theShorterOfTwoRoutesIsTaken() {
        // 0 -> (1 long way | 2 short way) -> 3
        RailNetwork network = network(List.of(straight(10, 1, 2), straight(100, 3), straight(50, 3), straight(20)),
            new Station("A", 0, 0), new Station("Long", 1, 50), new Station("B", 3, 10));
        Leg leg = legOf(network, "A", "B");
        assertEquals(10 + 50 + 10, leg.distance(), DELTA);
        assertEquals(List.of(), leg.via());
        assertEquals(List.of("Long>B"), pairs(network).stream().filter(p -> p.startsWith("Long")).toList());
    }

    @Test
    void aStationOnTheLongerRouteOnlyIsNotVia() {
        RailNetwork network = network(List.of(straight(10, 1, 2), straight(100, 3), straight(50, 3), straight(20)),
            new Station("A", 0, 0), new Station("Long", 1, 50), new Station("B", 3, 10));
        assertTrue(legOf(network, "A", "B").via().isEmpty());
        assertEquals(60, legOf(network, "A", "Long").distance(), DELTA);
    }

    @Test
    void anEdgeThatLeadsNowhereIsNotAWay() {
        RailNetwork network = network(List.of(straight(40, 1), straight(30)), new Station("A", 0, 10));
        assertEquals(List.of(), network.legs());
    }

    // ------------------------------------------------------------------ loops

    @Test
    void aLoopOfSeveralEdgesGoesRoundAndBackToTheStart() {
        RailNetwork network = network(List.of(straight(30, 1), straight(30, 2), straight(30, 0)),
            new Station("A", 0, 5), new Station("B", 1, 5), new Station("C", 2, 5));
        assertEquals(30, legOf(network, "A", "B").distance(), DELTA);
        assertEquals(60, legOf(network, "A", "C").distance(), DELTA);
        assertEquals(List.of("B"), legOf(network, "A", "C").via());
        assertEquals(30, legOf(network, "C", "A").distance(), DELTA);
        assertEquals(60, legOf(network, "B", "A").distance(), DELTA);
        assertEquals(6, network.legs().size());
    }

    @Test
    void aLoopMadeOfOneEdgeReachesAStationBehindTheStartByGoingRound() {
        RailNetwork network = network(List.of(straight(100, 0)), new Station("A", 0, 20), new Station("B", 0, 60));
        assertEquals(40, legOf(network, "A", "B").distance(), DELTA);
        assertEquals(60, legOf(network, "B", "A").distance(), DELTA);
    }

    @Test
    void aCurveOnTheWayRoundALoopIsMeasuredFromTheStart() {
        RailNetwork network = network(List.of(straight(100, 1), curved(40, 0)),
            new Station("A", 0, 80), new Station("B", 1, 10));
        assertEquals(List.of(new Leg.Curve(20, 30)), legOf(network, "A", "B").curves());
        assertEquals(30, legOf(network, "A", "B").distance(), DELTA);
        // From B the train finishes the curve (30 blocks), runs the whole straight and stops 80 in.
        assertEquals(30 + 80, legOf(network, "B", "A").distance(), DELTA);
        assertEquals(List.of(new Leg.Curve(0, 30)), legOf(network, "B", "A").curves());
    }

    // ------------------------------------------------------------------ names

    @Test
    void stationsThatShareANameAreOneStopAndTheNearestIsTaken() {
        RailNetwork network = network(List.of(straight(100, 1), straight(100)),
            new Station("A", 0, 10), new Station("X", 0, 50), new Station("X", 1, 10));
        assertEquals(List.of("A", "X"), network.names());
        assertEquals(40, legOf(network, "A", "X").distance(), DELTA);
        assertEquals(List.of("A>X"), pairs(network));
    }

    @Test
    void aStationWithTheNameOfTheStartIsNotViaAndNoLegGoesBackToTheSameName() {
        RailNetwork network = network(List.of(straight(100)),
            new Station("A", 0, 10), new Station("A", 0, 30), new Station("B", 0, 60));
        assertEquals(List.of("A>B"), pairs(network));
        assertEquals(30, legOf(network, "A", "B").distance(), DELTA);
        assertEquals(List.of(), legOf(network, "A", "B").via());
    }

    @Test
    void aStationIsViaOnlyOnce() {
        RailNetwork network = network(List.of(straight(100)),
            new Station("A", 0, 10), new Station("M", 0, 30), new Station("M", 0, 40), new Station("B", 0, 60));
        assertEquals(List.of("M"), legOf(network, "A", "B").via());
    }

    @Test
    void legsComeInTheOrderOfTheNames() {
        RailNetwork network = network(List.of(straight(60, 1), straight(60)),
            new Station("Z", 0, 10), new Station("M", 0, 30), new Station("A", 1, 20));
        assertEquals(List.of("Z", "M", "A"), network.names());
        assertEquals(List.of("Z>M", "Z>A", "M>A"), pairs(network));
    }

    @Test
    void legsAreInTheOrderOfTheNamesWhateverTheOrderTheSearchFindsThemIn() {
        // The search finds A (30 away) before M (50 away), but M comes before A in the list of names.
        RailNetwork network = network(List.of(straight(100)),
            new Station("Z", 0, 10), new Station("M", 0, 50), new Station("A", 0, 30));
        assertEquals(List.of("Z", "M", "A"), network.names());
        assertEquals(List.of("Z>M", "Z>A", "A>M"), pairs(network));
    }

    // ------------------------------------------------------------------ running times

    @Test
    void aLegTakesAsLongAsTravelTimeSaysForItsDistanceAndCurves() {
        RailNetwork network = network(List.of(straight(40, 1), curved(30, 2), straight(40)),
            new Station("A", 0, 10), new Station("B", 2, 15));
        Leg leg = legOf(network, "A", "B");
        TrainSpec spec = TrainSpec.STANDARD;
        int expected = TravelTime.ticks(spec, 1.0, 75, List.of(SpeedZone.curve(30, 60, spec))).orElseThrow();
        assertEquals(expected, leg.ticks(spec, 1.0).orElseThrow());
        int withoutCurve = TravelTime.ticks(spec, 1.0, 75, List.of()).orElseThrow();
        assertTrue(expected > withoutCurve, expected + " is not longer than " + withoutCurve);
    }

    @Test
    void aLegHasNoTimeAtAThrottleOfNothing() {
        RailNetwork network = network(List.of(straight(60)), new Station("A", 0, 10), new Station("B", 0, 30));
        assertTrue(legOf(network, "A", "B").ticks(TrainSpec.STANDARD, 0).isEmpty());
    }

    // ------------------------------------------------------------------ speed-limit signs

    private static Edge signed(double length, List<SpeedSign> signs, Integer... next) {
        return new Edge(length, false, List.of(next), signs);
    }

    private static List<Double> positions(Leg leg) {
        return leg.signs().stream().map(SpeedSign::position).toList();
    }

    @Test
    void theSignsOnTheWayAreGivenAsDistancesFromTheStart() {
        RailNetwork network = network(List.of(
                signed(40, List.of(SpeedSign.limit(25, 40)), 1),
                signed(30, List.of(SpeedSign.release(30), SpeedSign.limit(0, 60)), 2),
                signed(50, List.of(SpeedSign.limit(10, 20)))),
            new Station("A", 0, 10), new Station("B", 2, 20));
        Leg leg = legOf(network, "A", "B");
        assertEquals(80, leg.distance(), DELTA);
        assertEquals(List.of(15.0, 30.0, 60.0, 70.0), positions(leg));
        assertEquals(List.of(40, 60, 0, 20), leg.signs().stream().map(SpeedSign::kmh).toList());
        assertTrue(leg.signs().get(2).release());
    }

    @Test
    void aSignAtTheStartMarkerIsBehindTheTrainAndOneAtTheEndMarkerIsPassed() {
        RailNetwork network = network(List.of(signed(60, List.of(SpeedSign.limit(10, 40), SpeedSign.limit(30, 20)))),
            new Station("A", 0, 10), new Station("B", 0, 30));
        assertEquals(List.of(20.0), positions(legOf(network, "A", "B")));
    }

    @Test
    void theSignsPastTheStopAreThoseATrainComingInBrakesFor() {
        // The track goes on without a choice of way, so the train coming in to B looks along it: the
        // signs 10 and 80 blocks past B count, and the one 730 blocks past is beyond the search.
        RailNetwork network = network(List.of(
                signed(60, List.of(SpeedSign.limit(40, 10)), 1),
                signed(100, List.of(SpeedSign.limit(50, 20)), 2),
                signed(1000, List.of(SpeedSign.limit(600, 30)))),
            new Station("A", 0, 10), new Station("B", 0, 30));
        assertEquals(List.of(30.0, 100.0), positions(legOf(network, "A", "B")));
    }

    @Test
    void pastTheStopTheSearchGoesTheWayATrainLooksWhenNothingSteersIt() {
        // Past its path, Create's look ahead takes the first way of the graph at a switch: here edge 2,
        // though the train could also go on to edge 1.
        RailNetwork network = network(List.of(
                new Edge(60, false, List.of(1, 2), List.of(SpeedSign.limit(40, 10)), 2),
                signed(100, List.of(SpeedSign.limit(5, 20))),
                signed(100, List.of(SpeedSign.limit(5, 30)))),
            new Station("A", 0, 10), new Station("B", 0, 30));
        Leg leg = legOf(network, "A", "B");
        assertEquals(List.of(30.0, 55.0), positions(leg));
        assertEquals(List.of(10, 30), leg.signs().stream().map(SpeedSign::kmh).toList());
        // Without that edge, the look ahead ends with the track.
        RailNetwork deadEnd = network(List.of(
                new Edge(60, false, List.of(), List.of(SpeedSign.limit(40, 10)), -1)),
            new Station("A", 0, 10), new Station("B", 0, 30));
        assertEquals(List.of(30.0), positions(legOf(deadEnd, "A", "B")));
    }

    @Test
    void anEdgeWrittenByHandRunsOnToTheFirstOfItsWays() {
        assertEquals(1, signed(10, List.of(), 1, 2).onward());
        assertEquals(-1, signed(10, List.of()).onward());
    }

    @Test
    void aLegsSignsGoIntoItsRunningTime() {
        // As in TravelTimeTest: 1000 blocks with a 40 km/h sign at 400 and a release sign at 700.
        RailNetwork network = network(List.of(signed(1100, List.of(SpeedSign.limit(410, 40), SpeedSign.release(710)))),
            new Station("A", 0, 10), new Station("B", 0, 1010));
        Leg leg = legOf(network, "A", "B");
        assertEquals(1293, leg.ticks(TrainSpec.STANDARD, 1).orElseThrow());
        // Coming in under the limit of an earlier sign, the train is slower until the first sign of the leg.
        assertTrue(leg.ticks(TrainSpec.STANDARD, 1, OptionalInt.of(20)).orElseThrow() > 1293 + 100);
    }

    @Test
    void theLimitAtTheEndIsThatOfTheLastSignOrTheOneTheTrainCameWith() {
        Edge edge = signed(100, List.of(SpeedSign.limit(20, 40), SpeedSign.release(50), SpeedSign.limit(90, 60)));
        RailNetwork network = network(List.of(edge), new Station("A", 0, 0), new Station("B", 0, 30),
            new Station("C", 0, 70));
        assertEquals(OptionalInt.of(40), legOf(network, "A", "B").limitAtEnd(OptionalInt.empty()));
        assertEquals(OptionalInt.empty(), legOf(network, "A", "C").limitAtEnd(OptionalInt.of(60)));
        RailNetwork plain = network(List.of(straight(60)), new Station("A", 0, 10), new Station("B", 0, 30));
        assertEquals(OptionalInt.of(60), legOf(plain, "A", "B").limitAtEnd(OptionalInt.of(60)));
        // The sign past C is ahead of the train when it stops there, not yet its limit.
        assertEquals(OptionalInt.empty(), legOf(network, "B", "C").limitAtEnd(OptionalInt.of(40)));
    }

    // ------------------------------------------------------------------ what is refused

    @Test
    void refusesWhatDoesNotHangTogether() {
        assertThrows(IllegalArgumentException.class, () -> network(List.of(straight(-1))));
        assertThrows(IllegalArgumentException.class, () -> network(List.of(straight(10, 1))));
        assertThrows(IllegalArgumentException.class, () -> network(List.of(straight(10)), new Station("A", 1, 0)));
        assertThrows(IllegalArgumentException.class, () -> network(List.of(straight(10)), new Station("A", 0, 11)));
        assertThrows(IllegalArgumentException.class, () -> network(List.of(straight(10)), new Station("A", 0, -1)));
        assertThrows(IllegalArgumentException.class, () -> network(List.of(signed(10, List.of(SpeedSign.limit(11, 40))))));
        assertThrows(IllegalArgumentException.class, () -> network(List.of(signed(10, List.of(SpeedSign.limit(-1, 40))))));
        assertThrows(IllegalArgumentException.class, () -> network(List.of(new Edge(10, false, List.of(), List.of(), 1))));
        assertThrows(IllegalArgumentException.class, () -> network(List.of(new Edge(10, false, List.of(), List.of(), -2))));
    }

    @Test
    void anEmptyNetworkHasNoLegs() {
        assertEquals(List.of(), network(List.of()).legs());
        assertEquals(List.of(), network(List.of()).names());
    }
}
