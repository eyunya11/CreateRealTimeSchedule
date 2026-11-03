package io.github.eyunya11.createrealtimeschedule.gametest;

import java.util.List;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.Create;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.gametest.TrackLine.Signal;
import io.github.eyunya11.createrealtimeschedule.gametest.TrackLine.Turn;
import io.github.eyunya11.createrealtimeschedule.map.SignalLooks;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Lamp;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.SignalMark;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Spot;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.StationMark;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Track;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.TrainMark;
import io.github.eyunya11.createrealtimeschedule.railway.CreateMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks {@link CreateMap} against track that Create built, run with {@code ./gradlew runGameTestServer}:
 * that the map has the track where the blocks are, a curve along its shape, the stations, the signals
 * with what they show, and the train.
 *
 * <p>Each test has a batch of its own (see {@link TrackLine}). The map is of all the track of the
 * game, so a test looks only at what is inside its own structure.
 */
@GameTestHolder(Createrealtimeschedule.MODID)
@PrefixGameTestTemplate(false)
public class MapTests {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String TRACK_LINE = "track_line";
    /** 116 blocks along x and 60 along z, for the quarter turn. */
    private static final String TURN = "track_turn";
    private static final int TIMEOUT_TICKS = 1500;
    private static final String[] NAMES = {"Alpha", "Beta"};
    /** A quarter turn of radius 11.5 blocks from x = 100 to (112, 14), as {@code CurveRunTests} has it. */
    private static final Turn QUARTER_TURN = new Turn(100, 112, 14, 55);
    private static final int ALPHA_X = 6;
    private static final int BETA_Z = 51;

    /** How far a place on the map may be from where the block says, in blocks. */
    private static final double NEAR = 0.6;

    @GameTest(template = TURN, batch = "realtimemap_turn", timeoutTicks = TIMEOUT_TICKS)
    public static void aCurveRunsAlongItsShapeAndTheStationsAndTheTrainAreWhereTheyStand(GameTestHelper helper) {
        TrackLine line = new TrackLine(helper, QUARTER_TURN, ALPHA_X, BETA_Z, NAMES);
        line.cleanUpWhenTheTestEnds();
        boolean[] done = {false};
        helper.onEachTick(() -> {
            if (done[0] || !line.setUp())
                return;
            done[0] = true;
            TrackMap map = read(helper);
            List<Track> tracks = map.tracks().stream().filter(track -> inside(helper, track.points().getFirst())).toList();
            LOGGER.info("[{}] tracks: {}", helper.testInfo.getTestName(), tracks);

            Spot turnStart = centre(helper, new BlockPos(QUARTER_TURN.fromX(), TrackLine.TRACK_Y, TrackLine.TRACK_Z));
            Spot turnEnd = centre(helper, new BlockPos(QUARTER_TURN.toX(), TrackLine.TRACK_Y, QUARTER_TURN.toZ()));
            List<Track> curves = tracks.stream().filter(track -> track.points().size() > 2).toList();
            helper.assertValueEqual(1, curves.size(), "number of curved tracks");
            List<Spot> curve = curves.getFirst().points();
            boolean forwards = near(curve.getFirst(), turnStart);
            helper.assertTrue(forwards ? near(curve.getLast(), turnEnd) : near(curve.getFirst(), turnEnd) && near(curve.getLast(), turnStart),
                "the curve runs from " + curve.getFirst() + " to " + curve.getLast() + ", not from " + turnStart + " to " + turnEnd);
            double length = 0;
            for (int i = 1; i < curve.size(); i++)
                length += distance(curve.get(i - 1), curve.get(i));
            helper.assertTrue(Math.abs(length - line.curveLength()) < line.curveLength() * 0.02,
                "the curve's points are " + length + " blocks long, but Create's curve is " + line.curveLength());
            // A quarter circle of radius r bulges r (1 - cos 45°) from its chord in the middle: 3.4 blocks here.
            Spot middle = curve.get(curve.size() / 2);
            double bulge = fromLine(middle, turnStart, turnEnd);
            helper.assertTrue(bulge > 2.5, "the middle of the curve is " + bulge + " blocks from the chord, not bent like the turn");

            StationMark alpha = station(helper, map, "Alpha");
            StationMark beta = station(helper, map, "Beta");
            helper.assertTrue(near(alpha.at(), centre(helper, new BlockPos(ALPHA_X, TrackLine.TRACK_Y, TrackLine.TRACK_Z))),
                "Alpha is at " + alpha.at());
            helper.assertTrue(near(beta.at(), centre(helper, new BlockPos(QUARTER_TURN.toX(), TrackLine.TRACK_Y, BETA_Z))),
                "Beta is at " + beta.at());
            helper.assertTrue(tracks.contains(map.tracks().get(alpha.track())), "Alpha's track is not one of the line's");
            // The trains come in east to Alpha, and south, past the turn, to Beta.
            helper.assertTrue(near(alpha.direction(), new Spot(1, 0)), "trains come in to Alpha running " + alpha.direction());
            helper.assertTrue(near(beta.direction(), new Spot(0, 1)), "trains come in to Beta running " + beta.direction());

            List<TrainMark> trains = map.trains().stream()
                .filter(train -> inside(helper, train.carriages().getFirst().front())).toList();
            helper.assertValueEqual(1, trains.size(), "number of trains");
            TrackMap.Carriage carriage = trains.getFirst().carriages().getFirst();
            helper.assertTrue(distance(carriage.front(), alpha.at()) < 6 && distance(carriage.back(), alpha.at()) < 6,
                "the train is not at Alpha, where it was built: " + carriage);
            helper.succeed();
        });
    }

    @GameTest(template = TRACK_LINE, batch = "realtimemap_signals", timeoutTicks = TIMEOUT_TICKS)
    public static void aSignalShowsRedTowardsTheTrainAndGreenTowardsTheEmptyTrack(GameTestHelper helper) {
        // The train stands at Alpha, west of both signals: the westward one looks into its section.
        TrackLine line = new TrackLine(helper, 50, new int[] {6, 26, 46}, new String[] {"Alpha", "Beta", "Gamma"},
            List.of(new Signal(16, false), new Signal(36, true)));
        line.cleanUpWhenTheTestEnds();
        boolean[] done = {false};
        int[] waited = {0};
        helper.onEachTick(() -> {
            if (done[0] || !line.setUp())
                return;
            TrackMap map = read(helper);
            List<SignalMark> signals = map.signals().stream().filter(signal -> inside(helper, signal.at())).toList();
            SignalMark west = signals.stream().filter(signal -> near(signal.at(), centre(helper, line.track(16)))).findFirst().orElse(null);
            SignalMark east = signals.stream().filter(signal -> near(signal.at(), centre(helper, line.track(36)))).findFirst().orElse(null);
            boolean shown = west != null && east != null && west.sides().getFirst().lamps().equals(List.of(Lamp.RED))
                && east.sides().getFirst().lamps().equals(List.of(Lamp.GREEN));
            // Create works out what its signals show as the railways tick, which may be a few ticks after they are set up.
            if (!shown && ++waited[0] < 100)
                return;
            done[0] = true;
            LOGGER.info("[{}] signals: {}", helper.testInfo.getTestName(), signals);
            helper.assertValueEqual(2, signals.size(), "number of signals");
            helper.assertTrue(west != null && east != null, "the signals are not beside the track at x = 16 and 36: " + signals);
            helper.assertValueEqual(1, west.sides().size(), "number of sides of the signal for trains running west");
            helper.assertValueEqual(1, east.sides().size(), "number of sides of the signal for trains running east");
            helper.assertValueEqual(SignalLooks.CREATE, west.sides().getFirst().kind(), "kind of signal");
            helper.assertTrue(near(west.sides().getFirst().direction(), new Spot(-1, 0)),
                "the signal at x = 16 is for trains running " + west.sides().getFirst().direction() + ", not west");
            helper.assertTrue(near(east.sides().getFirst().direction(), new Spot(1, 0)),
                "the signal at x = 36 is for trains running " + east.sides().getFirst().direction() + ", not east");
            helper.assertValueEqual(List.of(Lamp.RED), west.sides().getFirst().lamps(), "what the signal towards the train shows");
            helper.assertValueEqual(List.of(Lamp.GREEN), east.sides().getFirst().lamps(), "what the signal towards empty track shows");
            helper.succeed();
        });
    }

    private static TrackMap read(GameTestHelper helper) {
        return CreateMap.read(Create.RAILWAYS.trackNetworks.values(), Create.RAILWAYS.trains.values(),
            helper.getLevel().getServer()::getLevel).map();
    }

    private static StationMark station(GameTestHelper helper, TrackMap map, String name) {
        List<StationMark> found = map.stations().stream().filter(station -> station.name().equals(name) && inside(helper, station.at()))
            .toList();
        helper.assertValueEqual(1, found.size(), "number of stations called " + name);
        return found.getFirst();
    }

    /** Where the middle of a block of the test is, from above. */
    private static Spot centre(GameTestHelper helper, BlockPos pos) {
        BlockPos at = helper.absolutePos(pos);
        return new Spot(at.getX() + 0.5, at.getZ() + 0.5);
    }

    /** Whether a place is inside the test's structure, from above. */
    private static boolean inside(GameTestHelper helper, Spot spot) {
        var bounds = helper.getBounds();
        return spot.x() >= bounds.minX && spot.x() <= bounds.maxX && spot.z() >= bounds.minZ && spot.z() <= bounds.maxZ;
    }

    private static boolean near(Spot a, Spot b) {
        return distance(a, b) <= NEAR;
    }

    private static double distance(Spot a, Spot b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    /** How far {@code point} is from the straight line through {@code a} and {@code b}. */
    private static double fromLine(Spot point, Spot a, Spot b) {
        double dx = b.x() - a.x();
        double dz = b.z() - a.z();
        return Math.abs(dx * (point.z() - a.z()) - dz * (point.x() - a.x())) / Math.hypot(dx, dz);
    }
}
