package io.github.eyunya11.createrealtimeschedule.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.station.GlobalStation;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.gametest.TrackLine.Loop;
import io.github.eyunya11.createrealtimeschedule.gametest.TrackLine.Turn;
import io.github.eyunya11.createrealtimeschedule.railway.CreateRoutes;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The way this mod's own search sends a train ({@link CreateRoutes}) is the one Create's
 * {@code Navigation.findPathTo} finds, on real track, with {@code ./gradlew runGameTestServer}: the
 * same platform, the same length, the same direction, and the same choices at the junctions. Each
 * test has a batch of its own (see {@link TrackLine}).
 */
@GameTestHolder(Createrealtimeschedule.MODID)
@PrefixGameTestTemplate(false)
public class RouteParityTests {

    private static final String TURN = "track_turn";
    private static final int TIMEOUT_TICKS = 600;
    private static final Turn BRANCH = new Turn(60, 72, 14, 55);
    private static final Loop LOOP = new Loop(100, 12, 40);

    /** Two platforms named A, straight on and on a branch: both free, and the one straight on taken. */
    @GameTest(template = TURN, batch = "realtimeroute_platforms", timeoutTicks = TIMEOUT_TICKS)
    public static void theSamePlatformIsTakenAsCreateTakes(GameTestHelper helper) {
        TrackLine line = new TrackLine(helper, 115, BRANCH,
            new BlockPos[] {at(6, TrackLine.TRACK_Z), at(110, TrackLine.TRACK_Z), at(BRANCH.toX(), 51)}, new String[] {"Depot", "A", "A"});
        compare(helper, line, l -> List.of(new Sent(l.train(), "A")));
    }

    @GameTest(template = TURN, batch = "realtimeroute_platform_taken", timeoutTicks = TIMEOUT_TICKS)
    public static void theFreePlatformIsTakenAsCreateTakes(GameTestHelper helper) {
        TrackLine line = new TrackLine(helper, 115, BRANCH,
            new BlockPos[] {at(6, TrackLine.TRACK_Z), at(110, TrackLine.TRACK_Z), at(BRANCH.toX(), 51)}, new String[] {"Depot", "A", "A"})
            .parkATrainAt(1);
        compare(helper, line, l -> List.of(new Sent(l.train(), "A")));
    }

    /** Round a loop, past four turns, to a station on another edge behind the train. */
    @GameTest(template = TURN, batch = "realtimeroute_loop", timeoutTicks = TIMEOUT_TICKS)
    public static void theWayRoundALoopIsTheOneCreateTakes(GameTestHelper helper) {
        TrackLine line = new TrackLine(helper, LOOP, new int[] {80, 30}, new String[] {"Bravo", "Alpha"});
        compare(helper, line, l -> List.of(new Sent(l.train(), "Alpha")));
    }

    /**
     * One track with a siding at A and signals: the express out of its depot to A and to B, and the
     * local, parked in the depot behind, to the platform on the siding.
     */
    @GameTest(template = TURN, batch = "realtimeroute_siding", timeoutTicks = TIMEOUT_TICKS)
    public static void theWaysByASidingWithSignalsAreTheOnesCreateTakes(GameTestHelper helper) {
        TrackLine line = OperationTests.sidingLine(helper);
        compare(helper, line, l -> List.of(new Sent(l.train(), "A"), new Sent(l.train(), "B"), new Sent(l.train(), "A2"),
            new Sent(l.parked(0), "A2"), new Sent(l.parked(0), "B")));
    }

    private static BlockPos at(int x, int z) {
        return new BlockPos(x, TrackLine.TRACK_Y, z);
    }

    /** A train to be sent to the stations of a name. */
    private record Sent(Train train, String station) {}

    /** Once the line is set up, every train of {@code sends} is sent both ways, and the two must agree. */
    private static void compare(GameTestHelper helper, TrackLine line, Function<TrackLine, List<Sent>> sends) {
        line.cleanUpWhenTheTestEnds();
        helper.onEachTick(() -> {
            if (!line.setUp())
                return;
            for (Sent sent : sends.apply(line)) {
                Train train = sent.train();
                ArrayList<GlobalStation> stations = new ArrayList<>();
                for (GlobalStation station : train.graph.getPoints(EdgePointType.STATION))
                    if (sent.station().equals(station.name))
                        stations.add(station);
                DiscoveredPath create = train.navigation.findPathTo(stations, Double.MAX_VALUE);
                DiscoveredPath ours = CreateRoutes.pathTo(train, stations);
                String what = train.name.getString() + " to " + sent.station() + ": Create " + show(create) + ", ours " + show(ours)
                    + " (" + line.describe() + ")";
                helper.assertTrue(create != null, "Create finds no way for " + what);
                helper.assertTrue(ours != null, "our search finds no way for " + what);
                helper.assertTrue(ours.destination == create.destination, "another platform for " + what);
                helper.assertTrue(Math.abs(ours.distance - create.distance) < 1e-6, "another length for " + what);
                helper.assertTrue(junctions(ours).equals(junctions(create)), "other turns at the junctions for " + what);
            }
            helper.succeed();
        });
    }

    private static List<String> junctions(DiscoveredPath path) {
        List<String> nodes = new ArrayList<>();
        for (Couple<TrackNode> couple : path.path)
            nodes.add(couple.getFirst().getLocation() + ">" + couple.getSecond().getLocation());
        return nodes;
    }

    private static String show(DiscoveredPath path) {
        if (path == null)
            return "none";
        return (path.destination == null ? "?" : path.destination.name + "@" + path.destination.id) + " " + path.distance + " cost "
            + path.cost + " turns " + junctions(path);
    }
}
