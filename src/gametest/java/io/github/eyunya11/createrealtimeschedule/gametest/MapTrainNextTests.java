package io.github.eyunya11.createrealtimeschedule.gametest;

import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Lamp;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.SignalMark;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.SpeedMark;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Spot;
import io.github.eyunya11.createrealtimeschedule.railway.CreateMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Checks that the map has Create Train Next's speed-limit signs and colour light signals, run with
 * {@code ./gradlew runGameTestServer}. Like {@code SpeedSignRunTests}, these need a jar of a build of
 * Create Train Next in {@code libs/createtrainnext.jar}, and are not registered without it. Its blocks
 * are taken from the registry by name and set up as placing the item does.
 *
 * <p>Each test has a batch of its own (see {@link TrackLine}).
 */
@GameTestHolder(Createrealtimeschedule.MODID)
public class MapTrainNextTests {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String CREATE_TRAIN_NEXT = "createtrainnext";
    private static final ResourceLocation LIMIT_SIGN = ResourceLocation.fromNamespaceAndPath(CREATE_TRAIN_NEXT, "track_speed_limit");
    private static final ResourceLocation RELEASE_SIGN = ResourceLocation.fromNamespaceAndPath(CREATE_TRAIN_NEXT, "track_speed_release");
    /** The colour light signal of four aspects: stop, approach, reduced speed and proceed. */
    private static final ResourceLocation LIGHT_SIGNAL = ResourceLocation.fromNamespaceAndPath(CREATE_TRAIN_NEXT, "multi_aspect_signal_4");

    private static final String TRACK_LINE = Createrealtimeschedule.MODID + ":track_line";
    private static final int TIMEOUT_TICKS = 1500;
    private static final int[] STATION_X = {6, 26, 46};
    private static final String[] NAMES = {"Alpha", "Beta", "Gamma"};

    /** How far a place on the map may be from where the block says, in blocks. */
    private static final double NEAR = 0.6;

    @GameTestGenerator
    public static Collection<TestFunction> mapsWithCreateTrainNext() {
        if (!ModList.get().isLoaded(CREATE_TRAIN_NEXT))
            return List.of();
        return List.of(
            new TestFunction("realtimemap_speed_signs", "maptrainnexttests.thespeedsignsareonthemapwiththeirlimits", TRACK_LINE,
                TIMEOUT_TICKS, 0, true, MapTrainNextTests::theSpeedSignsAreOnTheMapWithTheirLimits),
            new TestFunction("realtimemap_light_signals", "maptrainnexttests.acolourlightsignalshowsitsaspect", TRACK_LINE,
                TIMEOUT_TICKS, 0, true, MapTrainNextTests::aColourLightSignalShowsItsAspect));
    }

    /** A limit of 40 km/h for trains running east at x = 20, and a release at x = 30. */
    private static void theSpeedSignsAreOnTheMapWithTheirLimits(GameTestHelper helper) {
        TrackLine line = new TrackLine(helper, 50, STATION_X, NAMES);
        line.cleanUpWhenTheTestEnds();
        BlockPos limitAt = line.track(20).south();
        BlockPos releaseAt = line.track(30).south();
        int[] phase = {0};
        int[] waited = {0};
        helper.onEachTick(() -> {
            if (phase[0] == 0) {
                if (!line.setUp())
                    return;
                place(helper, LIMIT_SIGN, limitAt, line.track(20), true, 40);
                place(helper, RELEASE_SIGN, releaseAt, line.track(30), true, 0);
                phase[0] = 1;
                return;
            }
            if (phase[0] != 1)
                return;
            if (!(inTheGraph(helper, limitAt) && inTheGraph(helper, releaseAt))) {
                if (++waited[0] > 40)
                    helper.fail("the signs have no place in the graph after 40 ticks");
                return;
            }
            phase[0] = 2;
            TrackMap map = read(helper);
            List<SpeedMark> signs = map.speedLimits().stream().filter(sign -> inside(helper, sign.at())).toList();
            LOGGER.info("[{}] signs: {}", helper.testInfo.getTestName(), signs);
            helper.assertValueEqual(2, signs.size(), "number of signs on the map");
            SpeedMark limit = signs.get(0);
            SpeedMark release = signs.get(1);
            helper.assertTrue(near(limit.at(), centre(helper, line.track(20))), "the limit is at " + limit.at());
            helper.assertTrue(!limit.release() && limit.kmh() == 40, "the limit is " + limit);
            helper.assertTrue(near(limit.direction(), new Spot(1, 0)), "the limit holds trains running " + limit.direction());
            helper.assertTrue(near(release.at(), centre(helper, line.track(30))), "the release is at " + release.at());
            helper.assertTrue(release.release(), "the sign at x = 30 is not a release: " + release);
            helper.succeed();
        });
    }

    /**
     * A colour light signal for trains running west at x = 16, which looks into the section the train
     * stands in, and one for trains running east at x = 36, which looks into empty track. (The one for
     * trains running west at x = 36 would look first into the empty section between the two, and show
     * approach.)
     */
    private static void aColourLightSignalShowsItsAspect(GameTestHelper helper) {
        TrackLine line = new TrackLine(helper, 50, STATION_X, NAMES);
        line.cleanUpWhenTheTestEnds();
        BlockPos westAt = line.track(16).north();
        BlockPos eastAt = line.track(36).north();
        int[] phase = {0};
        int[] waited = {0};
        helper.onEachTick(() -> {
            if (phase[0] == 0) {
                if (!line.setUp())
                    return;
                place(helper, LIGHT_SIGNAL, westAt, line.track(16), false, -1);
                place(helper, LIGHT_SIGNAL, eastAt, line.track(36), true, -1);
                phase[0] = 1;
                return;
            }
            if (phase[0] != 1)
                return;
            TrackMap map = read(helper);
            List<SignalMark> signals = map.signals().stream().filter(signal -> inside(helper, signal.at())).toList();
            SignalMark west = signals.stream().filter(signal -> near(signal.at(), centre(helper, line.track(16)))).findFirst().orElse(null);
            SignalMark east = signals.stream().filter(signal -> near(signal.at(), centre(helper, line.track(36)))).findFirst().orElse(null);
            // The signal works its aspect out every half second, from stop; Create its own state as the railways tick.
            boolean shown = east != null && west != null && !east.sides().getFirst().lamps().equals(List.of(Lamp.RED))
                && west.sides().getFirst().lamps().equals(List.of(Lamp.RED));
            if (!shown && ++waited[0] < 200)
                return;
            phase[0] = 2;
            LOGGER.info("[{}] signals: {}", helper.testInfo.getTestName(), signals);
            helper.assertTrue(east != null && west != null, "the signals are not beside the track at x = 36 and 16: " + signals);
            helper.assertValueEqual(1, east.sides().size(), "number of sides of the signal for trains running east");
            helper.assertValueEqual("light4", east.sides().getFirst().kind(), "kind of the signal for trains running east");
            helper.assertValueEqual("light4", west.sides().getFirst().kind(), "kind of the signal for trains running west");
            helper.assertTrue(near(east.sides().getFirst().direction(), new Spot(1, 0)),
                "the signal at x = 36 is for trains running " + east.sides().getFirst().direction());
            helper.assertTrue(!east.sides().getFirst().lamps().equals(List.of(Lamp.RED)),
                "the signal into empty track shows " + east.sides().getFirst().lamps());
            helper.assertValueEqual(List.of(Lamp.RED), west.sides().getFirst().lamps(), "what the signal into the train's section shows");
            helper.succeed();
        });
    }

    /**
     * Does what placing the item does: the block, the track it points at, which way it faces, and,
     * for a limit, the number on it ({@code kmh} below 1 for none).
     */
    private static void place(GameTestHelper helper, ResourceLocation id, BlockPos at, BlockPos track, boolean eastward, int kmh) {
        ServerLevel level = helper.getLevel();
        Block block = BuiltInRegistries.BLOCK.get(id);
        helper.assertTrue(block != Blocks.AIR, "Create Train Next has no block " + id);
        BlockPos absolute = helper.absolutePos(at);
        level.setBlock(absolute, block.defaultBlockState(), 3);
        CompoundTag data = new CompoundTag();
        data.put("TargetTrack", NbtUtils.writeBlockPos(helper.absolutePos(track).subtract(absolute)));
        data.putBoolean("TargetDirection", !eastward);
        if (kmh > 0)
            data.putInt("ScrollValue", kmh);
        level.getBlockEntity(absolute).loadCustomOnly(data, level.registryAccess());
    }

    private static boolean inTheGraph(GameTestHelper helper, BlockPos at) {
        BlockEntity entity = helper.getLevel().getBlockEntity(helper.absolutePos(at));
        TrackTargetingBehaviour<?> target = entity == null ? null : BlockEntityBehaviour.get(entity, TrackTargetingBehaviour.TYPE);
        return target != null && target.getEdgePoint() != null;
    }

    private static TrackMap read(GameTestHelper helper) {
        return CreateMap.read(Create.RAILWAYS.trackNetworks.values(), Create.RAILWAYS.trains.values(),
            helper.getLevel().getServer()::getLevel).map();
    }

    private static Spot centre(GameTestHelper helper, BlockPos pos) {
        BlockPos at = helper.absolutePos(pos);
        return new Spot(at.getX() + 0.5, at.getZ() + 0.5);
    }

    private static boolean inside(GameTestHelper helper, Spot spot) {
        var bounds = helper.getBounds();
        return spot.x() >= bounds.minX && spot.x() <= bounds.maxX && spot.z() >= bounds.minZ && spot.z() <= bounds.maxZ;
    }

    private static boolean near(Spot a, Spot b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z()) <= NEAR;
    }
}
