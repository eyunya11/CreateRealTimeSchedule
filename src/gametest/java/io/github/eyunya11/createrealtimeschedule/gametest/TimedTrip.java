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

import io.github.eyunya11.createrealtimeschedule.railway.CreateNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import io.github.eyunya11.createrealtimeschedule.travel.SpeedSign;
import io.github.eyunya11.createrealtimeschedule.travel.SpeedSigns;
import io.github.eyunya11.createrealtimeschedule.travel.SpeedZone;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpec;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpecs;
import io.github.eyunya11.createrealtimeschedule.travel.TravelTime;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;

/**
 * A train run from the first station of a {@link TrackLine} to another of its stations, timed and
 * held to the model, stepped once a tick by a game test.
 *
 * <p>Before the train starts, the network is read with {@link CreateNetwork}. The leg to the
 * destination has to be in it, and the test holds the leg, and the network's other legs, to the
 * track it laid. The ticks from the first one in which the train moves to the one in which it has
 * arrived are then compared with what the model says for the leg's distance, curves and signs. The
 * leg is measured from the first station's marker, and the train from where its front stood, a
 * little past the marker, so the curves and signs are moved back by the difference.
 */
final class TimedTrip {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** What a test has to say about the leg, and about the network it was read from. */
    @FunctionalInterface
    interface LegCheck {
        void check(Leg leg, List<Leg> network);
    }

    private final GameTestHelper helper;
    private final MinecraftServer server;
    private final TrackLine line;
    private final int destination;
    private final double throttle;
    private final int toleranceTicks;
    private final int maxTicks;
    private final LegCheck legCheck;

    private Train train;
    private GlobalStation to;
    private Leg leg;
    private int ticksSinceSetUp;
    private long startTick = -1;
    private double distance;

    /**
     * @param destination    the index of the station the train runs to
     * @param toleranceTicks how far the model may be from the train
     * @param maxTicks       how long the train may take before the test fails
     */
    TimedTrip(GameTestHelper helper, TrackLine line, int destination, double throttle, int toleranceTicks, int maxTicks,
        LegCheck legCheck) {
        this.helper = helper;
        this.server = helper.getLevel().getServer();
        this.line = line;
        this.destination = destination;
        this.throttle = throttle;
        this.toleranceTicks = toleranceTicks;
        this.maxTicks = maxTicks;
        this.legCheck = legCheck;
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
            to = line.station(destination);
            readTheLeg();
            train.throttle = throttle;
            train.runtime.setSchedule(ScheduleTests.schedule(ScheduleTests.stop(to.name)), false);
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

        if (train.getCurrentStation() == to && train.runtime.state == ScheduleRuntime.State.POST_TRANSIT)
            finish(gameTime - startTick + 1);
        else if (gameTime - startTick > maxTicks)
            helper.fail("the train has not arrived after " + (gameTime - startTick) + " ticks (" + line.describe() + ")");
    }

    private void readTheLeg() {
        String from = line.station(0).name;
        List<Leg> network = CreateNetwork.read(Create.RAILWAYS.trackNetworks.values()).legs();
        leg = network.stream().filter(l -> l.from().equals(from) && l.to().equals(to.name)).findFirst()
            .orElseThrow(() -> new AssertionError("the network has no leg from " + from + " to " + to.name + ": " + network));
        legCheck.check(leg, network);
    }

    private void finish(long measured) {
        TrainSpec spec = TrainSpecs.of(train);
        double ahead = leg.distance() - distance;
        List<SpeedZone> zones = new ArrayList<>();
        for (Leg.Curve curve : leg.curves())
            zones.add(SpeedZone.curve(curve.start() - ahead, curve.end() - ahead, spec));
        List<SpeedSign> signs = new ArrayList<>();
        for (SpeedSign sign : leg.signs())
            signs.add(new SpeedSign(sign.position() - ahead, sign.kmh(), sign.release()));
        int model = TravelTime.ticks(spec, throttle, distance, zones, SpeedSigns.of(signs)).orElseThrow();
        LOGGER.info("[{}] leg {} to {}, {} blocks, curves {}, throttle {}, the train went {} blocks: measured {} ticks ({}s), model {} ticks ({} off)",
            helper.testInfo.getTestName(), leg.from(), leg.to(), String.format(Locale.ROOT, "%.3f", leg.distance()), leg.curves(),
            throttle, String.format(Locale.ROOT, "%.3f", distance), measured, String.format(Locale.ROOT, "%.2f", measured / 20.0),
            model, measured - model);

        helper.assertTrue(Math.abs(distance - leg.distance()) <= 1,
            "the train went " + distance + " blocks on a leg the export makes " + leg.distance() + " blocks long");
        helper.assertTrue(Math.abs(measured - model) <= toleranceTicks,
            "the train took " + measured + " ticks from " + leg.from() + " to " + leg.to() + " and the model says " + model);
        helper.succeed();
    }
}
