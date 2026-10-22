package io.github.eyunya11.createrealtimeschedule.gametest;

import java.util.List;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.gametest.TimedTrip.CurveCost;
import io.github.eyunya11.createrealtimeschedule.gametest.TrackLine.Turn;
import io.github.eyunya11.createrealtimeschedule.railway.CreateNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Holds the export and the model to a junction of real track, run with
 * {@code ./gradlew runGameTestServer}.
 *
 * <p>The line runs east from Alpha past Beta, and at x = 60 a branch turns off it to the south, as
 * when a player starts a curve from the middle of a straight, and runs on to Gamma. A train runs from
 * Alpha either straight on to Beta or onto the branch to Gamma ({@link TimedTrip}). Before it starts,
 * the network is read with {@link CreateNetwork}: it has to have the two ways out of the junction as
 * the only legs, the one straight on with no curve and the one onto the branch with the turn.
 *
 * <p>Each test has a batch of its own (see {@link TrackLine}).
 */
@GameTestHolder(Createrealtimeschedule.MODID)
@PrefixGameTestTemplate(false)
public class JunctionRunTests {

    /** 116 blocks along x and 60 along z. */
    private static final String TURN = "track_turn";
    private static final int TIMEOUT_TICKS = 1500;
    private static final int ALPHA_X = 6;
    private static final int BETA_X = 110;
    private static final int LAST_X = 115;
    /** The branch: a quarter turn from x = 60 to the line south along x = 72. */
    private static final Turn BRANCH = new Turn(60, 72, 14, 55);
    private static final int GAMMA_Z = 51;

    /** Straight on through the junction: the branch's curve is not on the way, so the run is a straight one, to the tick. */
    @GameTest(template = TURN, batch = "realtimejunction_through", timeoutTicks = TIMEOUT_TICKS)
    public static void straightOnThroughTheJunction(GameTestHelper helper) {
        new TimedTrip(helper, junction(helper), 1, 1.0, 0, TIMEOUT_TICKS - 300, (leg, network) -> {
            holdTheNetwork(helper, network);
            helper.assertTrue(leg.curves().isEmpty() && leg.via().isEmpty() && Math.abs(leg.distance() - (BETA_X - ALPHA_X)) < 0.01,
                "the leg from Alpha to Beta is " + leg);
        }).begin();
    }

    /** Onto the branch: the train turns off at the junction and is held to its turn speed round the turn. */
    @GameTest(template = TURN, batch = "realtimejunction_branch", timeoutTicks = TIMEOUT_TICKS)
    public static void ontoTheBranch(GameTestHelper helper) {
        double expectedStart = BRANCH.fromX() + 1 - (ALPHA_X + 0.5);
        TrackLine line = junction(helper);
        new TimedTrip(helper, line, 2, 1.0, CurveRunTests.TOLERANCE_TICKS, TIMEOUT_TICKS - 300, (leg, network) -> {
            holdTheNetwork(helper, network);
            double expectedEnd = expectedStart + line.curveLength();
            helper.assertTrue(leg.curves().size() == 1 && Math.abs(leg.curves().get(0).start() - expectedStart) <= 1
                    && Math.abs(leg.curves().get(0).end() - expectedEnd) <= 1 && leg.via().isEmpty(),
                "the leg from Alpha to Gamma is " + leg + ", and the turn runs from " + expectedStart + " to " + expectedEnd + " blocks on");
        }).curvesCost(CurveCost.LESS_THAN_THE_TOLERANCE).begin();
    }

    private static TrackLine junction(GameTestHelper helper) {
        return new TrackLine(helper, LAST_X, BRANCH,
            new BlockPos[] {at(ALPHA_X, TrackLine.TRACK_Z), at(BETA_X, TrackLine.TRACK_Z), at(BRANCH.toX(), GAMMA_Z)},
            new String[] {"Alpha", "Beta", "Gamma"});
    }

    private static BlockPos at(int x, int z) {
        return new BlockPos(x, TrackLine.TRACK_Y, z);
    }

    /** Every station is for trains running east or south, and the track ends past Beta and Gamma, so all the legs are from Alpha. */
    private static void holdTheNetwork(GameTestHelper helper, List<Leg> network) {
        List<String> legs = network.stream().map(leg -> leg.from() + " to " + leg.to()).toList();
        helper.assertTrue(legs.equals(List.of("Alpha to Beta", "Alpha to Gamma")), "the network has the legs " + legs);
    }
}
