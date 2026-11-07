package io.github.eyunya11.createrealtimeschedule.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Edge;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Point;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Station;
import io.github.eyunya11.createrealtimeschedule.railway.SignalBlocks;
import io.github.eyunya11.createrealtimeschedule.traffic.Traffic.Run;
import io.github.eyunya11.createrealtimeschedule.traffic.Traffic.Stint;
import io.github.eyunya11.createrealtimeschedule.traffic.Traffic.Trip;
import io.github.eyunya11.createrealtimeschedule.travel.SpeedSign;
import io.github.eyunya11.createrealtimeschedule.travel.SpeedSigns;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpec;
import io.github.eyunya11.createrealtimeschedule.travel.TravelTime;

class TrafficTest {

    private static final TrainSpec SPEC = TrainSpec.STANDARD;
    private static final double LENGTH = 10;
    /** Five minutes: long enough for every train to have arrived where it stops first. */
    private static final long LATER = 20 * 60 * 5;

    private static Leg leg(RailNetwork network, String from, String to) {
        return network.legs().stream().filter(l -> l.from().equals(from) && l.to().equals(to)).findFirst().orElseThrow();
    }

    private static Run run(String name, TrainSpec spec, Stint... stints) {
        return new Run(name, spec, 1, List.of(stints));
    }

    private static Stint first(Leg leg, long departs) {
        return new Stint(leg, departs, 0, true, LENGTH);
    }

    private static Stint then(Leg leg, long departs) {
        return new Stint(leg, departs, 0, false, LENGTH);
    }

    /**
     * A train that comes in from a little way back and stands at the station named {@code at} until
     * {@link #LATER}, then runs on to {@code to}, where it is taken off.
     */
    private static Run standing(RailNetwork network, String from, String at, String to) {
        return standing(network, from, at, to, LATER);
    }

    private static Run standing(RailNetwork network, String from, String at, String to, long until) {
        return run("standing", SPEC, first(leg(network, from, at), 0), then(leg(network, at, to), until));
    }

    private static int ticks(TrainSpec spec, double distance, SpeedSign... signs) {
        return TravelTime.ticks(spec, 1, distance, List.of(), SpeedSigns.of(List.of(signs))).getAsInt();
    }

    @Test
    void aTrainAloneTakesWhatTheTrackTakes() {
        for (int aspects : new int[] {0, 2, 3, 4, 5, 6, -1}) {
            RailNetwork network = TestLines.line(aspects, SignalBlocks.LightSpeeds.DEFAULT, 10, 900);
            Trip trip = Traffic.run(network.blocks(), List.of(run("alone", SPEC, first(leg(network, "A", "B"), 0))))
                .trips().getFirst().getFirst();
            assertEquals(trip.free(), trip.ticks(), "aspects " + aspects);
            assertEquals(List.of(), trip.waits());
        }
    }

    /**
     * A train standing in the last block holds the signals before it at 警戒, 注意 and 減速, a block each,
     * and the one before those at 進行. A train that runs up behind it goes by those as by signs of the
     * same speeds where the signals stand.
     */
    @Test
    void aTrainBehindAStandingOneRunsAtTheAspectsOfTheSignals() {
        RailNetwork network = TestLines.line(5, SignalBlocks.LightSpeeds.DEFAULT, 10, 580, 630, 650, 900);
        Run behind = run("behind", SPEC, first(leg(network, "A", "B"), 200));
        Traffic.Result result = Traffic.run(network.blocks(), List.of(standing(network, "C", "D", "E"), behind));
        Trip trip = result.trips().get(1).getFirst();
        assertEquals(ticks(SPEC, 570, SpeedSign.limit(290, 75), SpeedSign.limit(390, 45), SpeedSign.limit(490, 25)), trip.ticks());
        assertTrue(trip.ticks() > trip.free(), trip.ticks() + " ticks, " + trip.free() + " on an empty line");
        assertEquals(List.of(), trip.waits());
    }

    /** The six-aspect signal's 進行 holds a train to 130 km/h, which a powered train is faster than. */
    /** A train that stays where it ends holds the signals before it as one standing does; one taken off there, none. */
    @Test
    void aTrainThatStaysWhereItEndsHoldsItsBlock() {
        RailNetwork network = TestLines.line(5, SignalBlocks.LightSpeeds.DEFAULT, 10, 580, 630, 650);
        for (boolean stays : new boolean[] {true, false}) {
            Run ahead = new Run("ahead", SPEC, 1, List.of(first(leg(network, "C", "D"), 0)), stays);
            Run behind = run("behind", SPEC, first(leg(network, "A", "B"), 200));
            Trip trip = Traffic.run(network.blocks(), List.of(ahead, behind)).trips().get(1).getFirst();
            assertEquals(stays ? ticks(SPEC, 570, SpeedSign.limit(290, 75), SpeedSign.limit(390, 45), SpeedSign.limit(490, 25)) : trip.free(),
                trip.ticks(), stays ? "it stays" : "it is taken off");
        }
    }

    @Test
    void theProceedOfASixAspectSignalHoldsATrainTo130() {
        TrainSpec powered = TrainSpec.POWERED;
        RailNetwork network = TestLines.line(6, SignalBlocks.LightSpeeds.DEFAULT, 10, 580, 630, 650, 900);
        Run behind = run("behind", powered, first(leg(network, "A", "B"), 200));
        Trip trip = Traffic.run(network.blocks(), List.of(standing(network, "C", "D", "E"), behind)).trips().get(1).getFirst();
        assertEquals(ticks(powered, 570, SpeedSign.limit(290, 130), SpeedSign.limit(390, 75), SpeedSign.limit(490, 45)), trip.ticks());
    }

    @Test
    void theSpeedsOfTheConfigAreTheOnesTaken() {
        RailNetwork network = TestLines.line(5, new SignalBlocks.LightSpeeds(20, 40, 60, 130), 10, 580, 630, 650, 900);
        Run behind = run("behind", SPEC, first(leg(network, "A", "B"), 200));
        Trip trip = Traffic.run(network.blocks(), List.of(standing(network, "C", "D", "E"), behind)).trips().get(1).getFirst();
        assertEquals(ticks(SPEC, 570, SpeedSign.limit(290, 60), SpeedSign.limit(390, 40), SpeedSign.limit(490, 20)), trip.ticks());
    }

    /**
     * A train for beyond the standing one stops at the signal into its block until it has gone on and
     * been taken off the line, which those that come in for the last time are; the block is clear the tick
     * after.
     */
    @Test
    void aTrainStopsAtRedUntilTheBlockIsClear() {
        RailNetwork network = TestLines.line(5, SignalBlocks.LightSpeeds.DEFAULT, 10, 630, 650, 700, 950);
        Run behind = run("behind", SPEC, first(leg(network, "A", "E"), 200));
        Traffic.Result result = Traffic.run(network.blocks(), List.of(standing(network, "B", "C", "D"), behind));
        Trip ahead = result.trips().get(0).get(1);
        Trip trip = result.trips().get(1).getFirst();
        assertEquals(1, trip.waits().size(), trip.waits().toString());
        Traffic.Wait wait = trip.waits().getFirst();
        assertEquals(4, wait.signal(), "the signal into the block of the standing train");
        assertEquals(ahead.arrived() + 1, wait.from() + wait.ticks(), "it goes when the one ahead is off the line");
        assertTrue(trip.arrived() > ahead.arrived());
        assertTrue(trip.ticks() > wait.ticks() + trip.free(), "it slows down for the signal and starts from a stand");
    }

    /**
     * A train held up more than ten minutes longer than the line takes is taken off the line, and one that
     * would leave a station more than ten minutes late does not set out: a timetable that asks for more
     * than the line can take does not pile its trains up.
     */
    @Test
    void aTrainTenMinutesLateIsTakenOff() {
        RailNetwork network = TestLines.line(5, SignalBlocks.LightSpeeds.DEFAULT, 10, 450, 630, 650, 700, 950);
        Run held = run("held", SPEC, first(leg(network, "A", "B"), 200), then(leg(network, "B", "F"), 300));
        Run late = run("late", SPEC, first(leg(network, "A", "B"), 0),
            new Stint(leg(network, "B", "F"), 0, Traffic.TOO_LATE_TICKS + 1, false, LENGTH));
        Traffic.Result result = Traffic.run(network.blocks(), List.of(standing(network, "C", "D", "E", Traffic.TOO_LATE_TICKS * 2), held));
        List<Trip> trips = result.trips().get(1);
        assertEquals(2, trips.size());
        Trip lost = trips.get(1);
        assertFalse(lost.hasArrived());
        assertEquals(List.of(), lost.waits(), "the wait it was still in is not over");
        List<Trip> lateTrips = Traffic.run(network.blocks(), List.of(late)).trips().getFirst();
        assertEquals(2, lateTrips.size(), "it does not set out from B, and the leg from there says so");
        Trip notSetOut = lateTrips.get(1);
        assertFalse(notSetOut.hasArrived());
        assertEquals(lateTrips.getFirst().arrived() + Traffic.TOO_LATE_TICKS + 1, notSetOut.departed(), "when it was to leave");
        assertEquals(leg(network, "B", "F").ticks(SPEC, 1, OptionalInt.empty()).getAsInt(), notSetOut.free());
        assertEquals(List.of(), notSetOut.waits());
    }

    /**
     * A signal at the stop marker a train stops at is passed, as the leg has it: with a train standing two
     * blocks on, the signal at B shows 警戒 where the one before showed 注意, and the train leaves B still
     * held to 25 km/h.
     */
    @Test
    void aSignalAtTheStopMarkerIsPassed() {
        RailNetwork network = TestLines.line(5, SignalBlocks.LightSpeeds.DEFAULT, 10, 300, 340, 420, 450, 900);
        Run behind = run("behind", SPEC, first(leg(network, "A", "B"), 200), then(leg(network, "B", "C"), 2000));
        Trip trip = Traffic.run(network.blocks(), List.of(standing(network, "D", "E", "F"), behind)).trips().get(1).get(1);
        int expected = TravelTime.ticks(SPEC, 1, 40, List.of(), new SpeedSigns(OptionalInt.of(25), List.of())).getAsInt();
        assertEquals(expected, trip.ticks());
    }

    /**
     * A train held up for good is taken off at its deadline though nothing else moves until then, and the
     * train behind it, which waits for its block, goes on: the clock is not run on past the deadline.
     */
    @Test
    void aTrainHeldUpForGoodIsTakenOffInTimeForTheOnesBehind() {
        RailNetwork network = TestLines.line(0, null, 10, 320, 350, 450, 550);
        Run parked = new Run("parked", SPEC, 1, List.of(first(leg(network, "C", "D"), 0)), true);
        Run held = run("held", SPEC, first(leg(network, "A", "E"), 200));
        Run behind = run("behind", SPEC, first(leg(network, "A", "B"), 1000));
        Traffic.Result result = Traffic.run(network.blocks(), List.of(parked, held, behind));
        assertFalse(result.trips().get(1).getFirst().hasArrived(), "it never gets past the set that stays");
        Trip trip = result.trips().get(2).getFirst();
        assertTrue(trip.hasArrived(), "it goes on once the train ahead is taken off");
        Trip stuck = result.trips().get(1).getFirst();
        assertEquals(stuck.departed() + stuck.free() + Traffic.TOO_LATE_TICKS + 2, trip.waits().getFirst().from() + trip.waits().getFirst().ticks(),
            "the tick after the one ahead is taken off");
    }

    @Test
    void withoutCreateTrainNextOnlyTheRedSignalsCount() {
        RailNetwork network = TestLines.line(0, null, 10, 580, 630, 650, 900);
        Run behind = run("behind", SPEC, first(leg(network, "A", "B"), 200));
        Trip trip = Traffic.run(network.blocks(), List.of(standing(network, "C", "D", "E"), behind)).trips().get(1).getFirst();
        assertEquals(trip.free(), trip.ticks());
    }

    @Test
    void aTrainBehindAMovingOneIsSlowedByIt() {
        RailNetwork network = TestLines.line(5, SignalBlocks.LightSpeeds.DEFAULT, 10, 900);
        Leg way = leg(network, "A", "B");
        Traffic.Result result = Traffic.run(network.blocks(), List.of(run("first", SPEC, first(way, 0)), run("second", SPEC, first(way, 200))));
        Trip ahead = result.trips().get(0).getFirst();
        Trip behind = result.trips().get(1).getFirst();
        assertEquals(ahead.free(), ahead.ticks());
        assertTrue(behind.ticks() > behind.free(), behind.ticks() + " ticks, " + behind.free() + " on an empty line");
    }

    /**
     * Light signal limits last from stop to stop of a train, and a new train of the timetable starts
     * under none: past the signal at 警戒, a train that stops and goes on still runs at 25 km/h, unless it
     * goes on as another train.
     */
    @Test
    void aSignalLimitIsCarriedPastAStopButNotIntoANewTrain() {
        RailNetwork network = TestLines.line(5, SignalBlocks.LightSpeeds.DEFAULT, 10, 550, 590, 630, 650, 900);
        for (boolean newTrain : new boolean[] {false, true}) {
            Run behind = run("behind", SPEC, first(leg(network, "A", "B"), 200),
                new Stint(leg(network, "B", "C"), 2000, 0, newTrain, LENGTH));
            Trip trip = Traffic.run(network.blocks(), List.of(standing(network, "D", "E", "F"), behind)).trips().get(1).get(1);
            int expected = TravelTime.ticks(SPEC, 1, 40, List.of(), new SpeedSigns(newTrain ? OptionalInt.empty() : OptionalInt.of(25), List.of()))
                .getAsInt();
            assertEquals(expected, trip.ticks(), newTrain ? "a new train" : "the same train");
        }
    }

    /**
     * A cross signal holds a train until the block after the next signal is clear too, so it waits at
     * the cross signal and not at that one.
     */
    @Test
    void aTrainWaitsAtTheCrossSignalBeforeABlockedBlock() {
        RailNetwork network = TestLines.line(-1, null, 10, 330, 350, 380, 950);
        Run behind = run("behind", SPEC, first(leg(network, "A", "E"), 200));
        Trip trip = Traffic.run(network.blocks(), List.of(standing(network, "B", "C", "D"), behind)).trips().get(1).getFirst();
        assertEquals(1, trip.waits().size(), trip.waits().toString());
        assertEquals(0, trip.waits().getFirst().signal(), "the cross signal, not the one into the block of the standing train");
    }

    /**
     * Single track all the way: 100 blocks both ways, with a block in the middle from 40 to 60 that trains running
     * either way go through. Signal 0 at 40 east leads into it and 1 at 60 out of it; signals 2 and 3 do
     * the same for trains running west.
     */
    private static RailNetwork singleTrack(double... eastStations) {
        Edge east = new Edge(100, false, List.of(), List.of(), List.of(), -1,
            List.of(new Point(40, Point.Kind.SIGNAL, 0), new Point(60, Point.Kind.SIGNAL, 1)), -1, -1);
        Edge west = new Edge(100, false, List.of(), List.of(), List.of(), -1,
            List.of(new Point(40, Point.Kind.SIGNAL, 2), new Point(60, Point.Kind.SIGNAL, 3)), -1, -1);
        SignalBlocks blocks = new SignalBlocks(
            List.of(List.of(new SignalBlocks.Change(0, 0), new SignalBlocks.Change(40, 1), new SignalBlocks.Change(60, 2)),
                List.of(new SignalBlocks.Change(0, 2), new SignalBlocks.Change(40, 1), new SignalBlocks.Change(60, 0))),
            List.of(new SignalBlocks.Signal(1, List.of(1), List.of(), false), new SignalBlocks.Signal(2, List.of(), List.of(), false),
                new SignalBlocks.Signal(1, List.of(3), List.of(), false), new SignalBlocks.Signal(0, List.of(), List.of(), false)),
            3, List.of(), null);
        List<Station> stations = new ArrayList<>();
        for (int i = 0; i < eastStations.length; i++) {
            stations.add(new Station("E" + i, 0, eastStations[i]));
            stations.add(new Station("W" + i, 1, eastStations[i]));
        }
        return new RailNetwork(List.of(east, west), stations, List.of(), List.of(), blocks);
    }

    /**
     * Double track either side of 20 blocks of single track, from 40 to 60 east: signal 0 into it and 1 out
     * of it for trains running east, 2 into it and 3 out of it for trains running west. Blocks 0 and 2 are
     * the east track's, 3 and 4 the west track's, and 1 is the single track.
     */
    private static RailNetwork singleStretch(double... eastStations) {
        Edge east = new Edge(100, false, List.of(), List.of(), List.of(), -1,
            List.of(new Point(40, Point.Kind.SIGNAL, 0), new Point(60, Point.Kind.SIGNAL, 1)), -1, -1);
        Edge west = new Edge(100, false, List.of(), List.of(), List.of(), -1,
            List.of(new Point(40, Point.Kind.SIGNAL, 2), new Point(60, Point.Kind.SIGNAL, 3)), -1, -1);
        SignalBlocks blocks = new SignalBlocks(
            List.of(List.of(new SignalBlocks.Change(0, 0), new SignalBlocks.Change(40, 1), new SignalBlocks.Change(60, 2)),
                List.of(new SignalBlocks.Change(0, 3), new SignalBlocks.Change(40, 1), new SignalBlocks.Change(60, 4))),
            List.of(new SignalBlocks.Signal(1, List.of(1), List.of(), false), new SignalBlocks.Signal(2, List.of(), List.of(), false),
                new SignalBlocks.Signal(1, List.of(3), List.of(), false), new SignalBlocks.Signal(4, List.of(), List.of(), false)),
            5, List.of(), null);
        List<Station> stations = new ArrayList<>();
        for (int i = 0; i < eastStations.length; i++) {
            stations.add(new Station("E" + i, 0, eastStations[i]));
            stations.add(new Station("W" + i, 1, eastStations[i]));
        }
        return new RailNetwork(List.of(east, west), stations, List.of(), List.of(), blocks);
    }

    /**
     * Two trains for the single track at once: one goes through, and the other stops at the signal into
     * it until the first is out of it. Which one is Create's to say: the first to reserve the block makes
     * the other wait, and a train that waits is taken first the next tick, so it reserves the block
     * itself if the other is not in it yet. Here the one sent second is through first.
     */
    @Test
    void trainsMeetingOnSingleTrackTakeTurns() {
        RailNetwork network = singleStretch(10, 90);
        Traffic.Result result = Traffic.run(network.blocks(),
            List.of(run("east", SPEC, first(leg(network, "E0", "E1"), 0)), run("west", SPEC, first(leg(network, "W0", "W1"), 0))));
        Trip east = result.trips().get(0).getFirst();
        Trip west = result.trips().get(1).getFirst();
        assertEquals(List.of(), west.waits());
        assertEquals(1, east.waits().size(), east.waits().toString());
        assertEquals(0, east.waits().getFirst().signal(), "the signal into the single track");
        assertTrue(east.arrived() > west.arrived());
        assertTrue(east.ticks() > east.free() + east.waits().getFirst().ticks());
    }

    /**
     * Two tracks: the first 300 blocks long, with signal 0 at 100 into block 1 and signal 1 at 200 into
     * block 2, and stations A at 10 and B at 190; the second 100 blocks long, apart from it, with signal 2
     * at 20 into block 3, and stations C at 30, D at 50 and E at 90. Block 1 leads on to signals 1 and 2,
     * as a block with a junction in it does. The signals are five-aspect light signals.
     */
    private static RailNetwork twoTracks(List<List<Integer>> crossings) {
        Edge first = new Edge(300, false, List.of(), List.of(), List.of(), -1,
            List.of(new Point(100, Point.Kind.SIGNAL, 0), new Point(200, Point.Kind.SIGNAL, 1)), -1, -1);
        Edge second = new Edge(100, false, List.of(), List.of(), List.of(), -1, List.of(new Point(20, Point.Kind.SIGNAL, 2)), -1, -1);
        SignalBlocks blocks = new SignalBlocks(
            List.of(List.of(new SignalBlocks.Change(0, 0), new SignalBlocks.Change(100, 1), new SignalBlocks.Change(200, 2)),
                List.of(new SignalBlocks.Change(0, 4), new SignalBlocks.Change(20, 3))),
            List.of(new SignalBlocks.Signal(1, List.of(1, 2), List.of(5), false), new SignalBlocks.Signal(2, List.of(), List.of(5), false),
                new SignalBlocks.Signal(3, List.of(), List.of(5), false)),
            5, crossings, SignalBlocks.LightSpeeds.DEFAULT);
        return new RailNetwork(List.of(first, second), List.of(new Station("A", 0, 10), new Station("B", 0, 190),
            new Station("C", 1, 30), new Station("D", 1, 50), new Station("E", 1, 90)), List.of(), List.of(), blocks);
    }

    /**
     * Where the block after a signal branches, the aspect is counted along the branch with the fewest
     * blocks clear: a train standing past one branch holds the signal before the junction at 警戒, though
     * the way on along the other is clear.
     */
    @Test
    void theAspectIsCountedAlongTheBranchWithTheFewestBlocksClear() {
        RailNetwork network = twoTracks(List.of());
        Run behind = run("behind", SPEC, first(leg(network, "A", "B"), 200));
        Trip trip = Traffic.run(network.blocks(), List.of(standing(network, "C", "D", "E"), behind)).trips().get(1).getFirst();
        assertEquals(ticks(SPEC, 180, SpeedSign.limit(90, 25)), trip.ticks());
    }

    /** A train in a block whose track crosses another takes that one up too: a train for it stops at its signal. */
    @Test
    void aTrainInABlockTakesUpTheBlocksThatCrossIt() {
        for (boolean crossing : new boolean[] {true, false}) {
            RailNetwork network = twoTracks(crossing ? List.of(List.of(1, 3)) : List.of());
            Run behind = run("behind", SPEC, first(leg(network, "A", "B"), 200));
            Traffic.Result result = Traffic.run(network.blocks(), List.of(standing(network, "C", "D", "E"), behind));
            Trip trip = result.trips().get(1).getFirst();
            if (!crossing) {
                assertEquals(List.of(), trip.waits());
                continue;
            }
            assertEquals(1, trip.waits().size(), trip.waits().toString());
            assertEquals(0, trip.waits().getFirst().signal());
            assertEquals(result.trips().get(0).get(1).arrived() + 1, trip.waits().getFirst().from() + trip.waits().getFirst().ticks());
        }
    }

    /** Each standing in the block the other needs: neither gets anywhere, and it says so. */
    @Test
    void trainsThatHoldEachOtherUpNeverArrive() {
        RailNetwork network = singleTrack(10, 30, 50, 90);
        // East stands at 50, in the middle block; west at 30 its way, 70 east's, in the east end block.
        Run east = run("east", SPEC, first(leg(network, "E0", "E2"), 0), then(leg(network, "E2", "E3"), 1000));
        Run west = run("west", SPEC, first(leg(network, "W0", "W1"), 0), then(leg(network, "W1", "W3"), 1000));
        Traffic.Result result = Traffic.run(network.blocks(), List.of(east, west));
        for (List<Trip> trips : result.trips()) {
            assertEquals(2, trips.size());
            assertTrue(trips.get(0).hasArrived());
            assertFalse(trips.get(1).hasArrived());
        }
    }
}
