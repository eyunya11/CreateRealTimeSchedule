package io.github.eyunya11.createrealtimeschedule.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.station.GlobalStation;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.gametest.TrackLine.Bend;
import io.github.eyunya11.createrealtimeschedule.railway.CreateNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import io.github.eyunya11.createrealtimeschedule.travel.SpeedZone;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpec;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpecs;
import io.github.eyunya11.createrealtimeschedule.travel.TravelTime;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Holds what {@link TravelTime} makes of a curve to trains that really run round one, run with
 * {@code ./gradlew runGameTestServer}.
 *
 * <p>A train runs 300 blocks from Alpha to Beta on a {@link TrackLine} with a {@link Bend}: one of
 * Create's curved tracks, an S-bend that takes the line two blocks over. Create holds a train to its
 * turn speed while the front is on a curve, whatever the curve's shape, so an S-bend serves for any
 * curve, and it fits the narrow template the straight runs use. Before the train starts, the network
 * is read with {@link CreateNetwork}, and the leg from Alpha to Beta has to have the bend as its one
 * curve, where it was laid. The ticks the train takes are then compared with what the model says for
 * that curve, as {@code TravelTimeRunTests} does for straight runs, though not to the tick (see
 * {@link #TOLERANCE_TICKS}).
 *
 * <p>Each test has a batch of its own (see {@link TrackLine}).
 */
@GameTestHolder(Createrealtimeschedule.MODID)
@PrefixGameTestTemplate(false)
public class CurveRunTests {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String LONG_TRACK_LINE = "track_line_long";
    private static final int TIMEOUT_TICKS = 1500;
    private static final int ALPHA_X = 6;
    private static final int DISTANCE = 300;
    private static final int SHIFT = 2;

    /**
     * How far the model may be from the train, in ticks: half a second. On a curve Create keeps the two
     * wheels of a bogey a fixed straight distance apart, and the nudges that do it leave the front wheel
     * up to about 0.3 blocks off the distance the train counts, mostly ahead of it. The train takes the
     * difference into its count when it next looks its way up again (with 320, 220 or 120 blocks to
     * go), and with less than a hundred blocks to go it arrives that much early instead. Near the stop
     * Create's braking makes up to 9 ticks of a block (see {@code TravelTimeTest}), and the nudges
     * depend on where in the world the track is, so a run over a curve ends a few ticks either side of
     * the model, differently from one run to the next: from 8 early to 2 late over six runs of these
     * four. The model leaves the nudges out. A model without the turn speed would be 33 ticks off the
     * run at full throttle.
     */
    private static final int TOLERANCE_TICKS = 10;

    /** The train comes to the bend at 24 blocks a second, and has to slow to its turn speed of 14 for it. */
    @GameTest(template = LONG_TRACK_LINE, batch = "realtimecurve_full", timeoutTicks = TIMEOUT_TICKS)
    public static void anSBendAtFullThrottle(GameTestHelper helper) {
        new Run(helper, new Bend(100, 113, SHIFT), 1.0).begin();
    }

    /** At a quarter of the throttle the train goes at 7 blocks a second, under the turn speed, so the bend changes nothing. */
    @GameTest(template = LONG_TRACK_LINE, batch = "realtimecurve_quarter", timeoutTicks = TIMEOUT_TICKS)
    public static void anSBendAtAQuarterThrottle(GameTestHelper helper) {
        new Run(helper, new Bend(100, 113, SHIFT), 0.25).begin();
    }

    /** The bend is where the train is braking for the stop already. */
    @GameTest(template = LONG_TRACK_LINE, batch = "realtimecurve_before_the_stop", timeoutTicks = TIMEOUT_TICKS)
    public static void anSBendJustBeforeTheStop(GameTestHelper helper) {
        new Run(helper, new Bend(280, 293, SHIFT), 1.0).begin();
    }

    /** The train comes to the bend still slower than its turn speed, and reaches the turn speed on it. */
    @GameTest(template = LONG_TRACK_LINE, batch = "realtimecurve_after_the_start", timeoutTicks = TIMEOUT_TICKS)
    public static void anSBendJustAfterTheStart(GameTestHelper helper) {
        new Run(helper, new Bend(20, 33, SHIFT), 1.0).begin();
    }

    /** One train on one line with a bend, stepped once a tick by the game test. */
    private static final class Run {
        private final GameTestHelper helper;
        private final MinecraftServer server;
        private final Bend bend;
        private final double throttle;
        private final TrackLine line;

        private Train train;
        private GlobalStation beta;
        private Leg leg;
        private int ticksSinceSetUp;
        private long startTick = -1;
        private double distance;

        Run(GameTestHelper helper, Bend bend, double throttle) {
            this.helper = helper;
            this.server = helper.getLevel().getServer();
            this.bend = bend;
            this.throttle = throttle;
            this.line = new TrackLine(helper, ALPHA_X + DISTANCE + 4, new int[] {ALPHA_X, ALPHA_X + DISTANCE},
                new String[] {"Alpha", "Beta"}, bend);
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
                beta = line.station(1);
                readTheLeg();
                train.throttle = throttle;
                train.runtime.setSchedule(ScheduleTests.schedule(ScheduleTests.stop("Beta")), false);
                return;
            }

            long gameTime = server.overworld().getGameTime();
            if (startTick < 0) {
                // The first tick in which the train moves is the first tick of the model.
                if (Math.abs(train.speed) > 0) {
                    startTick = gameTime;
                    distance = train.navigation.distanceToDestination + Math.abs(train.speed);
                } else if (++ticksSinceSetUp > 200)
                    helper.fail("the train did not start (" + line.describe() + ")");
                return;
            }

            if (train.getCurrentStation() == beta && train.runtime.state == ScheduleRuntime.State.POST_TRANSIT)
                finish(gameTime - startTick + 1);
            else if (gameTime - startTick > TIMEOUT_TICKS - 300)
                helper.fail("the train has not arrived after " + (gameTime - startTick) + " ticks (" + line.describe() + ")");
        }

        /**
         * The leg from Alpha to Beta as the export reads it: the bend has to be its one curve, from the
         * east face of the block it starts at, measured from Alpha's marker in the middle of its block.
         */
        private void readTheLeg() {
            leg = CreateNetwork.read(Create.RAILWAYS.trackNetworks.values()).legs().stream()
                .filter(l -> l.from().equals("Alpha") && l.to().equals("Beta")).findFirst()
                .orElseThrow(() -> new AssertionError("the network has no leg from Alpha to Beta"));
            double expectedStart = bend.fromX() + 1 - (ALPHA_X + 0.5);
            helper.assertTrue(leg.curves().size() == 1 && Math.abs(leg.curves().get(0).start() - expectedStart) <= 1,
                "the leg from Alpha to Beta has the curves " + leg.curves() + ", and the bend starts " + expectedStart + " blocks on");
        }

        private void finish(long measured) {
            TrainSpec spec = TrainSpecs.of(train);
            // The leg has the curve from Alpha's marker, and the trip is measured from where the front
            // stood, which is this far past the marker.
            double ahead = leg.distance() - distance;
            List<SpeedZone> zones = new ArrayList<>();
            for (Leg.Curve curve : leg.curves())
                zones.add(SpeedZone.curve(curve.start() - ahead, curve.end() - ahead, spec));
            int model = TravelTime.ticks(spec, throttle, distance, zones).orElseThrow();
            int straight = TravelTime.ticks(spec, throttle, distance, List.of()).orElseThrow();
            LOGGER.info("[{}] curve {}, leg {} blocks, throttle {}, the train went {} blocks: measured {} ticks ({}s), model {} ticks ({} off), {} without the curve",
                helper.testInfo.getTestName(), leg.curves(), String.format(Locale.ROOT, "%.3f", leg.distance()), throttle,
                String.format(Locale.ROOT, "%.3f", distance), measured, String.format(Locale.ROOT, "%.2f", measured / 20.0), model,
                measured - model, straight);

            helper.assertTrue(Math.abs(distance - leg.distance()) <= 1,
                "the train went " + distance + " blocks on a leg the export makes " + leg.distance() + " blocks long");
            helper.assertTrue(Math.abs(measured - model) <= TOLERANCE_TICKS,
                "the train took " + measured + " ticks round the curve " + leg.curves() + " and the model says " + model);
            helper.succeed();
        }
    }
}
