package io.github.eyunya11.createrealtimeschedule.gametest;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.gametest.TrackLine.Bend;
import io.github.eyunya11.createrealtimeschedule.gametest.TrackLine.Turn;
import io.github.eyunya11.createrealtimeschedule.railway.CreateNetwork;
import io.github.eyunya11.createrealtimeschedule.travel.TravelTime;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Holds what {@link TravelTime} makes of a curve to trains that really run round one, run with
 * {@code ./gradlew runGameTestServer}.
 *
 * <p>A train runs from Alpha to Beta on a {@link TrackLine} with one of Create's curved tracks in it:
 * a {@link Bend}, an S-bend that takes the line two blocks over on a run of 300 blocks, or a
 * {@link Turn}, a quarter turn to the south on a run of 150. Create holds a train to its turn speed
 * while the front is on a curve, whatever the curve's shape, so the two are held to the same rule;
 * the turn also has the network read round a corner, with the second station on track that runs
 * along z. Before the train starts, the network is read with {@link CreateNetwork}, and the leg from
 * Alpha to Beta has to have the curve as its one curve, where it was laid. The ticks the train takes
 * are then compared with what the model says for that curve ({@link TimedTrip}), as
 * {@code TravelTimeRunTests} does for straight runs, though not to the tick (see
 * {@link #TOLERANCE_TICKS}).
 *
 * <p>Each test has a batch of its own (see {@link TrackLine}).
 */
@GameTestHolder(Createrealtimeschedule.MODID)
@PrefixGameTestTemplate(false)
public class CurveRunTests {

    private static final String LONG_TRACK_LINE = "track_line_long";
    /** 116 blocks along x and 60 along z, for the quarter turn. */
    private static final String TURN = "track_turn";
    private static final int TIMEOUT_TICKS = 1500;
    private static final int ALPHA_X = 6;
    private static final int DISTANCE = 300;
    private static final int SHIFT = 2;
    private static final String[] NAMES = {"Alpha", "Beta"};
    /** A quarter turn of radius 11.5 blocks from x = 100, after which Beta is 37.5 blocks on. */
    private static final Turn QUARTER_TURN = new Turn(100, 112, 14, 55);
    private static final int BETA_Z = 51;

    /**
     * How far the model may be from the train, in ticks: a second. On a curve Create keeps the two
     * wheels of a bogey a fixed straight distance apart, and the nudges that do it leave the front wheel
     * up to about 0.3 blocks off the distance the train counts, either way. The train takes the
     * difference into its count when it next looks its way up again (with 320, 220 or 120 blocks to
     * go). Past the last of those, the difference is still there at the stop: a front that is ahead
     * arrives early, and one that is behind finds its count run out short of the marker, and creeps the
     * rest at the speed Create gives a train that has overrun its count, which takes up to a dozen
     * ticks more. Near the stop Create's braking also makes up to 9 ticks of a block (see
     * {@code TravelTimeTest}). The nudges depend on where in the world the track is, so a run over a
     * curve ends a few ticks either side of the model, differently from one run to the next: from 8
     * early to 12 late over some ninety runs of these six and of copies of them placed elsewhere, most
     * within 6. The model leaves the nudges out. A model without the turn speed would be 33 ticks off
     * the run over the S-bend at full throttle.
     */
    static final int TOLERANCE_TICKS = 20;

    /** The train comes to the bend at 24 blocks a second, and has to slow to its turn speed of 14 for it. */
    @GameTest(template = LONG_TRACK_LINE, batch = "realtimecurve_full", timeoutTicks = TIMEOUT_TICKS)
    public static void anSBendAtFullThrottle(GameTestHelper helper) {
        overABend(helper, new Bend(100, 113, SHIFT), 1.0);
    }

    /** At a quarter of the throttle the train goes at 7 blocks a second, under the turn speed, so the bend changes nothing. */
    @GameTest(template = LONG_TRACK_LINE, batch = "realtimecurve_quarter", timeoutTicks = TIMEOUT_TICKS)
    public static void anSBendAtAQuarterThrottle(GameTestHelper helper) {
        overABend(helper, new Bend(100, 113, SHIFT), 0.25);
    }

    /** The bend is where the train is braking for the stop already. */
    @GameTest(template = LONG_TRACK_LINE, batch = "realtimecurve_before_the_stop", timeoutTicks = TIMEOUT_TICKS)
    public static void anSBendJustBeforeTheStop(GameTestHelper helper) {
        overABend(helper, new Bend(280, 293, SHIFT), 1.0);
    }

    /** The train comes to the bend still slower than its turn speed, and reaches the turn speed on it. */
    @GameTest(template = LONG_TRACK_LINE, batch = "realtimecurve_after_the_start", timeoutTicks = TIMEOUT_TICKS)
    public static void anSBendJustAfterTheStart(GameTestHelper helper) {
        overABend(helper, new Bend(20, 33, SHIFT), 1.0);
    }

    /** The train comes to the turn at 24 blocks a second, as to the S-bend at full throttle. */
    @GameTest(template = TURN, batch = "realtimecurve_turn_full", timeoutTicks = TIMEOUT_TICKS)
    public static void aQuarterTurnAtFullThrottle(GameTestHelper helper) {
        roundTheTurn(helper, 1.0);
    }

    /** At half the throttle the train goes at 14 blocks a second, which is its turn speed. */
    @GameTest(template = TURN, batch = "realtimecurve_turn_half", timeoutTicks = TIMEOUT_TICKS)
    public static void aQuarterTurnAtHalfThrottle(GameTestHelper helper) {
        roundTheTurn(helper, 0.5);
    }

    private static void overABend(GameTestHelper helper, Bend bend, double throttle) {
        TrackLine line = new TrackLine(helper, ALPHA_X + DISTANCE + 4, new int[] {ALPHA_X, ALPHA_X + DISTANCE}, NAMES, bend);
        run(helper, line, bend.fromX(), throttle);
    }

    private static void roundTheTurn(GameTestHelper helper, double throttle) {
        run(helper, new TrackLine(helper, QUARTER_TURN, ALPHA_X, BETA_Z, NAMES), QUARTER_TURN.fromX(), throttle);
    }

    /**
     * Runs the train from Alpha to Beta. The leg the export reads has to have one curve, from the east
     * face of the block it starts at, measured from Alpha's marker in the middle of its block.
     */
    private static void run(GameTestHelper helper, TrackLine line, int curveFromX, double throttle) {
        double expectedStart = curveFromX + 1 - (ALPHA_X + 0.5);
        new TimedTrip(helper, line, 1, throttle, TOLERANCE_TICKS, TIMEOUT_TICKS - 300, (leg, network) ->
            helper.assertTrue(leg.curves().size() == 1 && Math.abs(leg.curves().get(0).start() - expectedStart) <= 1,
                "the leg from Alpha to Beta has the curves " + leg.curves() + ", and the curve starts " + expectedStart + " blocks on"))
            .begin();
    }
}
