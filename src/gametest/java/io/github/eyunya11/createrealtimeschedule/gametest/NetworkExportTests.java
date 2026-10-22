package io.github.eyunya11.createrealtimeschedule.gametest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.railway.CreateNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import io.github.eyunya11.createrealtimeschedule.railway.NetworkJson;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpec;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpecs;
import io.github.eyunya11.createrealtimeschedule.travel.TravelTime;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks {@link CreateNetwork} against track that Create built, run with
 * {@code ./gradlew runGameTestServer}.
 *
 * <p>The line is Alpha, Beta and Gamma in a row, 20 blocks apart, on a {@link TrackLine}. The first
 * test reads the network and checks the stations, the legs, the stations passed on the way and the
 * running times; the second sends a real train from Alpha to Beta and checks that the distance
 * Create itself reports for the trip is the one the network gives. Curves and switches are not here:
 * they need track the tests do not build yet, and {@code RailNetworkTest} covers the search on them
 * with networks written out by hand.
 *
 * <p>Each test has a batch of its own (see {@link TrackLine}).
 */
@GameTestHolder(Createrealtimeschedule.MODID)
@PrefixGameTestTemplate(false)
public class NetworkExportTests {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String TRACK_LINE = "track_line";
    private static final int TIMEOUT_TICKS = 1500;
    private static final int LAST_X = 50;
    private static final int[] STATION_X = {6, 26, 46};
    private static final String[] NAMES = {"Alpha", "Beta", "Gamma"};

    private static final double DISTANCE_TOLERANCE = 0.01;

    @GameTest(template = TRACK_LINE, batch = "realtimenetwork_legs", timeoutTicks = TIMEOUT_TICKS)
    public static void theLegsOfALineAreTheDistancesBetweenItsStations(GameTestHelper helper) {
        TrackLine line = new TrackLine(helper, LAST_X, STATION_X, NAMES);
        line.cleanUpWhenTheTestEnds();
        boolean[] done = {false};
        helper.onEachTick(() -> {
            if (done[0] || !line.setUp())
                return;
            done[0] = true;

            RailNetwork network = CreateNetwork.read(Create.RAILWAYS.trackNetworks.values());
            helper.assertValueEqual(List.of("Alpha", "Beta", "Gamma"), network.names(), "names of the stations");
            List<Leg> legs = network.legs();
            LOGGER.info("[{}] legs: {}", helper.testInfo.getTestName(), legs);
            helper.assertValueEqual(3, legs.size(), "number of legs (the track runs one way only)");

            Leg alphaBeta = leg(helper, legs, "Alpha", "Beta");
            Leg alphaGamma = leg(helper, legs, "Alpha", "Gamma");
            Leg betaGamma = leg(helper, legs, "Beta", "Gamma");
            near(helper, 20, alphaBeta.distance(), "distance from Alpha to Beta");
            near(helper, 40, alphaGamma.distance(), "distance from Alpha to Gamma");
            near(helper, 20, betaGamma.distance(), "distance from Beta to Gamma");
            helper.assertValueEqual(List.of(), alphaBeta.via(), "stations between Alpha and Beta");
            helper.assertValueEqual(List.of("Beta"), alphaGamma.via(), "stations between Alpha and Gamma");
            helper.assertValueEqual(List.of(), alphaGamma.curves(), "curves between Alpha and Gamma");

            // 20 and 40 blocks at full throttle took 96 and 133 ticks on a real train (TravelTimeRunTests).
            TrainSpec spec = TrainSpecs.unpowered();
            helper.assertValueEqual(96, alphaBeta.ticks(spec, 1.0).orElseThrow(), "ticks from Alpha to Beta");
            helper.assertValueEqual(133, alphaGamma.ticks(spec, 1.0).orElseThrow(), "ticks from Alpha to Gamma");
            helper.assertValueEqual(TravelTime.ticks(spec, 1.0, betaGamma.distance(), List.of()).orElseThrow(),
                betaGamma.ticks(spec, 1.0).orElseThrow(), "ticks from Beta to Gamma");

            String json = NetworkJson.write(network.names(), legs, spec, 1.0);
            helper.assertTrue(json.contains("\"from\": \"Alpha\", \"to\": \"Gamma\", \"distance\": 40, \"via\": [\"Beta\"], \"ticks\": 133}"),
                "the JSON does not have the leg from Alpha to Gamma as expected: " + json);

            checkTheCommand(helper, json);
            helper.succeed();
        });
    }

    /** {@code /rtnetwork export} is registered, runs as an operator, and writes the same text to the world folder. */
    private static void checkTheCommand(GameTestHelper helper, String expectedJson) {
        MinecraftServer server = helper.getLevel().getServer();
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("createrealtimeschedule").resolve("network.json");
        try {
            Files.deleteIfExists(file);
            int result = server.getCommands().getDispatcher().execute("rtnetwork export", server.createCommandSourceStack());
            helper.assertValueEqual(3, result, "what the command returns (the number of legs)");
            helper.assertValueEqual(expectedJson, Files.readString(file, StandardCharsets.UTF_8), "the text the command wrote");
        } catch (CommandSyntaxException | IOException e) {
            helper.fail("/rtnetwork export did not work: " + e);
        } finally {
            try {
                Files.deleteIfExists(file);
                Files.deleteIfExists(file.getParent());
            } catch (IOException e) {
                LOGGER.warn("could not remove {}", file, e);
            }
        }
    }

    @GameTest(template = TRACK_LINE, batch = "realtimenetwork_run", timeoutTicks = TIMEOUT_TICKS)
    public static void aRealTrainRunsTheDistanceTheNetworkGives(GameTestHelper helper) {
        TrackLine line = new TrackLine(helper, LAST_X, STATION_X, NAMES);
        line.cleanUpWhenTheTestEnds();
        double[] expected = {-1};
        boolean[] scheduled = {false};
        boolean[] done = {false};
        int[] waited = {0};
        helper.onEachTick(() -> {
            if (done[0])
                return;
            if (!scheduled[0]) {
                if (!line.setUp())
                    return;
                RailNetwork network = CreateNetwork.read(Create.RAILWAYS.trackNetworks.values());
                expected[0] = leg(helper, network.legs(), "Alpha", "Beta").distance();
                Train train = line.train();
                train.throttle = 1.0;
                train.runtime.setSchedule(ScheduleTests.schedule(ScheduleTests.stop("Beta")), false);
                scheduled[0] = true;
                return;
            }
            Train train = line.train();
            if (Math.abs(train.speed) > 0) {
                // The first tick in which the train moves: the distance Create still sees to the stop, as before the move.
                double measured = train.navigation.distanceToDestination + Math.abs(train.speed);
                LOGGER.info("[{}] the network says {} blocks, Create says {}", helper.testInfo.getTestName(),
                    String.format(Locale.ROOT, "%.3f", expected[0]), String.format(Locale.ROOT, "%.3f", measured));
                near(helper, measured, expected[0], "distance of the trip Create reports against the network's");
                done[0] = true;
                helper.succeed();
            } else if (++waited[0] > 200)
                helper.fail("the train did not start (" + line.describe() + ")");
        });
    }

    private static Leg leg(GameTestHelper helper, List<Leg> legs, String from, String to) {
        return legs.stream().filter(l -> l.from().equals(from) && l.to().equals(to)).findFirst()
            .orElseThrow(() -> new GameTestAssertException("there is no leg from " + from + " to " + to + " in " + legs));
    }

    private static void near(GameTestHelper helper, double expected, double actual, String what) {
        helper.assertTrue(Math.abs(expected - actual) <= DISTANCE_TOLERANCE,
            what + ": expected " + expected + " but it is " + actual);
    }
}
