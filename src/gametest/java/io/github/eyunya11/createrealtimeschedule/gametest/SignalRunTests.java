package io.github.eyunya11.createrealtimeschedule.gametest;

import java.util.List;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.gametest.TrackLine.Signal;
import io.github.eyunya11.createrealtimeschedule.railway.CreateNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.Leg;
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
 * edge Alpha is on, in all but a few places in the world, and Create looks at the edge a train starts
 * on in a way of its own.
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
    /** How long a train that has no way is watched, in ticks. */
    private static final int WATCH_TICKS = 100;

    @GameTest(template = LONG_TRACK_LINE, batch = "realtimesignal_green", timeoutTicks = TIMEOUT_TICKS)
    public static void signalsForTheTrainLetItRunAsWithoutThem(GameTestHelper helper) {
        TrackLine line = withSignals(helper, new Signal(100, true), new Signal(200, true));
        new TimedTrip(helper, line, 1, 1.0, 0, TIMEOUT_TICKS - 300, (leg, network) ->
            helper.assertTrue(Math.abs(leg.distance() - DISTANCE) < 0.01 && leg.curves().isEmpty(), "the leg from Alpha to Beta is " + leg))
            .begin();
    }

    @GameTest(template = LONG_TRACK_LINE, batch = "realtimesignal_against", timeoutTicks = TIMEOUT_TICKS)
    public static void aSignalForTrainsRunningWestClosesTheLine(GameTestHelper helper) {
        new NoWay(helper, withSignals(helper, new Signal(150, false))).begin();
    }

    @GameTest(template = LONG_TRACK_LINE, batch = "realtimesignal_against_near", timeoutTicks = TIMEOUT_TICKS)
    public static void aSignalForTrainsRunningWestJustPastTheStationClosesItToo(GameTestHelper helper) {
        new NoWay(helper, withSignals(helper, new Signal(ALPHA_X + 2, false))).begin();
    }

    private static TrackLine withSignals(GameTestHelper helper, Signal... signals) {
        return new TrackLine(helper, ALPHA_X + DISTANCE + 4, new int[] {ALPHA_X, ALPHA_X + DISTANCE}, NAMES, List.of(signals));
    }

    /**
     * A train given a schedule to Beta that it has no way to: the export must have no leg to Beta, and
     * the train must not set off, as Create finds no path for it.
     */
    private static final class NoWay {
        private final GameTestHelper helper;
        private final TrackLine line;
        private Train train;
        private int watched;

        NoWay(GameTestHelper helper, TrackLine line) {
            this.helper = helper;
            this.line = line;
        }

        void begin() {
            line.cleanUpWhenTheTestEnds();
            helper.onEachTick(this::tick);
        }

        private void tick() {
            if (train == null) {
                if (!line.setUp())
                    return;
                train = line.train();
                List<Leg> network = CreateNetwork.read(Create.RAILWAYS.trackNetworks.values()).legs();
                List<String> legs = network.stream().map(leg -> leg.from() + " to " + leg.to()).toList();
                helper.assertTrue(!legs.contains("Alpha to Beta"), "the export has a leg from Alpha to Beta past the signal: " + legs);
                train.runtime.setSchedule(ScheduleTests.schedule(ScheduleTests.stop("Beta")), false);
                return;
            }
            helper.assertTrue(train.speed == 0 && train.navigation.destination == null,
                "the train set off for Beta past the signal (" + line.describe() + ")");
            if (++watched >= WATCH_TICKS)
                helper.succeed();
        }
    }
}
