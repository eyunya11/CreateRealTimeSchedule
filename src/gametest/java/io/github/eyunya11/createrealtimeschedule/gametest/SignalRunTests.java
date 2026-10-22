package io.github.eyunya11.createrealtimeschedule.gametest;

import java.util.List;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.gametest.TrackLine.Signal;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Holds the export and the model to real track with Create's signals on it, run with
 * {@code ./gradlew runGameTestServer}.
 *
 * <p>The line runs 300 blocks east from Alpha to Beta. Signals for trains running east cut it into
 * blocks, which a train that has the line to itself finds green: it has to run as on the line without
 * them, to the tick ({@link TimedTrip}). A signal for trains running west lets no train east past it
 * ({@code SignalBoundary.canNavigateVia}), so Create finds no way from Alpha to Beta, and the export
 * must have no leg between them either. That is tried with the signal well on the way and just past
 * Alpha: Create splits a straight line into edges at chunk borders, so there the signal is on the
 * edge Alpha is on, in all but a few places in the world. A signal for trains running west just past
 * Beta, on Beta's edge, does not close the way to Beta: Create's search meets Beta first, so the
 * train runs there and the export has the leg ({@link NoWayTrip} for the runs with no way).
 *
 * <p>Each test has a batch of its own (see {@link TrackLine}).
 */
@GameTestHolder(Createrealtimeschedule.MODID)
@PrefixGameTestTemplate(false)
public class SignalRunTests {

    private static final String LONG_TRACK_LINE = "track_line_long";
    private static final int TIMEOUT_TICKS = 1500;
    private static final int ALPHA_X = 6;
    private static final int DISTANCE = 300;
    private static final String[] NAMES = {"Alpha", "Beta"};

    @GameTest(template = LONG_TRACK_LINE, batch = "realtimesignal_green", timeoutTicks = TIMEOUT_TICKS)
    public static void signalsForTheTrainLetItRunAsWithoutThem(GameTestHelper helper) {
        TrackLine line = withSignals(helper, new Signal(100, true), new Signal(200, true));
        new TimedTrip(helper, line, 1, 1.0, 0, TIMEOUT_TICKS - 300, (leg, network) ->
            helper.assertTrue(Math.abs(leg.distance() - DISTANCE) < 0.01 && leg.curves().isEmpty(), "the leg from Alpha to Beta is " + leg))
            .begin();
    }

    @GameTest(template = LONG_TRACK_LINE, batch = "realtimesignal_against", timeoutTicks = TIMEOUT_TICKS)
    public static void aSignalForTrainsRunningWestClosesTheLine(GameTestHelper helper) {
        new NoWayTrip(helper, withSignals(helper, new Signal(150, false)), 1).begin();
    }

    @GameTest(template = LONG_TRACK_LINE, batch = "realtimesignal_against_near", timeoutTicks = TIMEOUT_TICKS)
    public static void aSignalForTrainsRunningWestJustPastTheStationClosesItToo(GameTestHelper helper) {
        new NoWayTrip(helper, withSignals(helper, new Signal(ALPHA_X + 2, false)), 1).begin();
    }

    /**
     * A signal for trains running west a few blocks past Beta, on the same edge as Beta and not on
     * Alpha's: Create's search meets Beta before the signal and runs the train there, and the export
     * has to have the leg. Beta is put where the world's chunks make that so.
     */
    @GameTest(template = LONG_TRACK_LINE, batch = "realtimesignal_against_past_the_stop", timeoutTicks = TIMEOUT_TICKS)
    public static void aStationJustBeforeASignalForTrainsRunningWestIsReached(GameTestHelper helper) {
        int betaX = TrackLine.inOneChunk(helper, 100, 1, 3);
        TrackLine line = new TrackLine(helper, betaX + 10, new int[] {ALPHA_X, betaX}, NAMES, List.of(new Signal(betaX + 3, false)));
        new TimedTrip(helper, line, 1, 1.0, 0, TIMEOUT_TICKS - 300, (leg, network) ->
            helper.assertTrue(Math.abs(leg.distance() - (betaX - ALPHA_X)) < 0.01, "the leg from Alpha to Beta is " + leg))
            .begin();
    }

    private static TrackLine withSignals(GameTestHelper helper, Signal... signals) {
        return new TrackLine(helper, ALPHA_X + DISTANCE + 4, new int[] {ALPHA_X, ALPHA_X + DISTANCE}, NAMES, List.of(signals));
    }
}
