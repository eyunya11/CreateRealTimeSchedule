package io.github.eyunya11.createrealtimeschedule.gametest;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.gametest.TimedTrip.CurveCost;
import io.github.eyunya11.createrealtimeschedule.gametest.TrackLine.Loop;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Holds the export to a loop of real track, run with {@code ./gradlew runGameTestServer}: four
 * quarter turns of Create's curved track and the straights between them, with two stations on the
 * first row. The train is built at Bravo and sent to Alpha, behind it, which it can only get to by
 * going round.
 *
 * <p>Create's search counts the edge a train starts on as visited from the start, and coming round to
 * it again it looks only at what lies ahead of where the train stood. So a station behind the train
 * on a different edge is reached by going round, and one behind it on the same edge is not reached at
 * all: the train finds no path. The export has to say the same. Create splits a straight line into
 * edges at chunk borders, so the stations are put where the world's chunks make them share an edge,
 * or not.
 *
 * <p>Each test has a batch of its own (see {@link TrackLine}).
 */
@GameTestHolder(Createrealtimeschedule.MODID)
@PrefixGameTestTemplate(false)
public class LoopRunTests {

    /** 116 blocks along x and 60 along z. */
    private static final String TURN = "track_turn";
    private static final int TIMEOUT_TICKS = 2000;
    /** About 280 blocks round, with turns of radius 12. */
    private static final Loop LOOP = new Loop(100, 12, 40);
    private static final String[] NAMES = {"Bravo", "Alpha"};

    /** Alpha 50 blocks behind Bravo, on another edge: the train goes round, past the four turns. */
    @GameTest(template = TURN, batch = "realtimeloop_round", timeoutTicks = TIMEOUT_TICKS)
    public static void aStationOnAnotherEdgeBehindIsReachedByGoingRound(GameTestHelper helper) {
        TrackLine line = new TrackLine(helper, LOOP, new int[] {80, 30}, NAMES);
        new TimedTrip(helper, line, 1, 1.0, CurveRunTests.TOLERANCE_TICKS, TIMEOUT_TICKS - 300, (leg, network) ->
            helper.assertTrue(leg.curves().size() == 4, "the leg from Bravo to Alpha is " + leg + ", which should go round the four turns"))
            .curvesCost(CurveCost.MORE_THAN_THE_TOLERANCE).begin();
    }

    /** Alpha 6 blocks behind Bravo, on the same edge: Create finds no way there, and nor may the export. */
    @GameTest(template = TURN, batch = "realtimeloop_same_edge", timeoutTicks = TIMEOUT_TICKS)
    public static void aStationOnTheSameEdgeBehindIsNotReached(GameTestHelper helper) {
        int bravoX = TrackLine.inOneChunk(helper, 40, 7, 2);
        TrackLine line = new TrackLine(helper, LOOP, new int[] {bravoX, bravoX - 6}, NAMES);
        new NoWayTrip(helper, line, 1).begin();
    }
}
