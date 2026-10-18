package io.github.eyunya11.createrealtimeschedule.gametest;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.station.GlobalStation;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpecs;
import io.github.eyunya11.createrealtimeschedule.travel.TravelTime;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Holds {@link TravelTime} against a train that really runs, run with
 * {@code ./gradlew runGameTestServer}.
 *
 * <p>A train is built on a {@link TrackLine}, given a schedule with one stop, Beta, and left to run
 * there from Alpha. The test counts the ticks from the first one in which the train moves to the one
 * in which it has arrived, and compares that with what the model says for the same distance, the
 * same throttle and the speeds the train itself reports.
 *
 * <p>Only straight track, no signals and one train are tried. Most lines are 50 blocks long, so the
 * runs are short: a low throttle makes the train cruise on them as a full one does on a long run,
 * and a full throttle shows the part that speeds up and brakes. One run of 300 blocks on a longer
 * template reaches the real top speed. Curves and speed-limit zones are not measured here.
 *
 * <p>The model counts a run the way Create ends it: the train is stopped at the stop's marker, in
 * the tick whose move reaches it. An earlier version counted to the point a quarter of a block past
 * the marker that the train aims for, and was ten ticks too long on every run; these tests are what
 * showed it.
 *
 * <p>Each test has a batch of its own (see {@link TrackLine}).
 */
@GameTestHolder(Createrealtimeschedule.MODID)
@PrefixGameTestTemplate(false)
public class TravelTimeRunTests {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String TRACK_LINE = "track_line";
    private static final String LONG_TRACK_LINE = "track_line_long";
    private static final int TIMEOUT_TICKS = 1500;
    private static final int ALPHA_X = 6;

    /**
     * How far the model may be from the train, in ticks (20 to a second): not at all. All five runs
     * agree to the tick, and {@code TravelTimeTest} pins the same counts, so a train that takes a
     * different number of ticks (after an update of Create, say) fails here and is noticed.
     */
    private static final int TOLERANCE_TICKS = 0;

    @GameTest(template = TRACK_LINE, batch = "realtimetravel_20_full", timeoutTicks = TIMEOUT_TICKS)
    public static void twentyBlocksAtFullThrottle(GameTestHelper helper) {
        new Run(helper, 20, 1.0).begin();
    }

    @GameTest(template = TRACK_LINE, batch = "realtimetravel_40_full", timeoutTicks = TIMEOUT_TICKS)
    public static void fortyBlocksAtFullThrottle(GameTestHelper helper) {
        new Run(helper, 40, 1.0).begin();
    }

    @GameTest(template = TRACK_LINE, batch = "realtimetravel_40_quarter", timeoutTicks = TIMEOUT_TICKS)
    public static void fortyBlocksAtAQuarterThrottle(GameTestHelper helper) {
        new Run(helper, 40, 0.25).begin();
    }

    @GameTest(template = TRACK_LINE, batch = "realtimetravel_40_tenth", timeoutTicks = TIMEOUT_TICKS)
    public static void fortyBlocksAtATenthThrottle(GameTestHelper helper) {
        new Run(helper, 40, 0.1).begin();
    }

    /** Long enough to reach the top speed (about 131 blocks) and cruise on it. */
    @GameTest(template = LONG_TRACK_LINE, batch = "realtimetravel_300_full", timeoutTicks = TIMEOUT_TICKS)
    public static void threeHundredBlocksAtFullThrottle(GameTestHelper helper) {
        new Run(helper, 300, 1.0).begin();
    }

    /** One train on one line, stepped once a tick by the game test. */
    private static final class Run {
        private final GameTestHelper helper;
        private final MinecraftServer server;
        private final int stationDistance;
        private final double throttle;
        private final TrackLine line;

        private Train train;
        private GlobalStation beta;
        private int ticksSinceSetUp;
        private long startTick = -1;
        private double distance;

        Run(GameTestHelper helper, int stationDistance, double throttle) {
            this.helper = helper;
            this.server = helper.getLevel().getServer();
            this.stationDistance = stationDistance;
            this.throttle = throttle;
            this.line = new TrackLine(helper, ALPHA_X + stationDistance + 4, new int[] {ALPHA_X, ALPHA_X + stationDistance},
                new String[] {"Alpha", "Beta"});
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
            else if (gameTime - startTick > 1200)
                helper.fail("the train has not arrived after " + (gameTime - startTick) + " ticks (" + line.describe() + ")");
        }

        private void finish(long measured) {
            // The speeds are the ones this train reports, so that the model is given what Create uses.
            int model = TravelTime.ticks(TrainSpecs.of(train), throttle, distance, List.of()).orElseThrow();
            LOGGER.info("[{}] stations {} blocks apart, the train went {} blocks, throttle {}: measured {} ticks ({}s), model {} ticks ({}s)",
                helper.testInfo.getTestName(), stationDistance, String.format(Locale.ROOT, "%.3f", distance), throttle,
                measured, String.format(Locale.ROOT, "%.2f", measured / 20.0), model, String.format(Locale.ROOT, "%.2f", model / 20.0));

            helper.assertTrue(Math.abs(distance - stationDistance) <= 1.0,
                "the train went " + distance + " blocks between stations " + stationDistance + " blocks apart");
            helper.assertTrue(Math.abs(measured - model) <= TOLERANCE_TICKS,
                "the train took " + measured + " ticks and the model says " + model);
            helper.succeed();
        }
    }
}
