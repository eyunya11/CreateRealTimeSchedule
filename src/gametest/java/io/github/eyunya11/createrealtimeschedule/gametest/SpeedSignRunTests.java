package io.github.eyunya11.createrealtimeschedule.gametest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.railway.CreateNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import io.github.eyunya11.createrealtimeschedule.travel.SpeedSign;
import io.github.eyunya11.createrealtimeschedule.travel.SpeedSigns;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpecs;
import io.github.eyunya11.createrealtimeschedule.travel.TravelTime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Holds what {@link TravelTime} makes of Create Train Next's speed-limit signs to trains that really
 * run under them, run with {@code ./gradlew runGameTestServer}.
 *
 * <p>Create Train Next has no release to depend on, so these need a jar of a build of it in
 * {@code libs/createtrainnext.jar} (see {@code build.gradle}). Without it, as on CI, they are not
 * registered at all, rather than passing without having run. Nothing of Create Train Next is needed to
 * compile them: the signs are its blocks, taken from the registry by name, and are set up through
 * Create's own behaviours, as placing the item does.
 *
 * <p>A train runs 300 blocks from Alpha to Beta on a {@link TrackLine}, with signs beside the track.
 * Before it starts, the network is read with {@link CreateNetwork}, and the leg from Alpha to Beta has
 * to list every sign, where it was put and with its limit; so the reading of the signs is held to the
 * world too. The ticks the train takes are then compared with what the model says for the signs of
 * that leg, as {@code TravelTimeRunTests} does for runs without signs.
 *
 * <p>Each test has a batch of its own (see {@link TrackLine}).
 */
@GameTestHolder(Createrealtimeschedule.MODID)
@PrefixGameTestTemplate(false)
public class SpeedSignRunTests {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String CREATE_TRAIN_NEXT = "createtrainnext";
    private static final ResourceLocation LIMIT_SIGN = ResourceLocation.fromNamespaceAndPath(CREATE_TRAIN_NEXT, "track_speed_limit");
    private static final ResourceLocation RELEASE_SIGN = ResourceLocation.fromNamespaceAndPath(CREATE_TRAIN_NEXT, "track_speed_release");

    private static final String LONG_TRACK_LINE = Createrealtimeschedule.MODID + ":track_line_long";
    private static final int TIMEOUT_TICKS = 4000;
    private static final int ALPHA_X = 6;
    private static final int DISTANCE = 300;

    /** How far the model may be from the train, in ticks. */
    private static final int TOLERANCE_TICKS = 0;

    /**
     * A sign to put beside the track, so many blocks past Alpha.
     *
     * @param kmh      its limit, or 0 for a release sign
     * @param eastward whether it faces east, the way the train runs; one facing west holds only trains
     *                 that run west, and the leg from Alpha to Beta must leave it out
     */
    private record Sign(int blocksPastAlpha, int kmh, boolean eastward) {
        Sign(int blocksPastAlpha, int kmh) {
            this(blocksPastAlpha, kmh, true);
        }

        boolean release() {
            return kmh == 0;
        }
    }

    @GameTestGenerator
    public static Collection<TestFunction> runsUnderSigns() {
        if (!ModList.get().isLoaded(CREATE_TRAIN_NEXT))
            return List.of();
        return List.of(
            run("alimitandarelease", 1.0, new Sign(100, 40), new Sign(200, 0)),
            run("alimitandareleaseathalfthrottle", 0.5, new Sign(100, 40), new Sign(200, 0)),
            run("alimituptothestop", 1.0, new Sign(150, 40)),
            run("aslowsignjustpastthestop", 1.0, new Sign(DISTANCE + 3, 10)),
            run("asignfacingtheotherway", 1.0, new Sign(150, 10, false)));
    }

    private static TestFunction run(String name, double throttle, Sign... signs) {
        return new TestFunction("realtimesigns_" + name, "speedsignruntests." + name, LONG_TRACK_LINE, TIMEOUT_TICKS, 0, true,
            helper -> new Run(helper, throttle, List.of(signs)).begin());
    }

    private enum Phase {
        SET_UP, WAIT_FOR_SIGNS, RUN
    }

    /** One train on one line with signs, stepped once a tick by the game test. */
    private static final class Run {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final MinecraftServer server;
        private final double throttle;
        private final List<Sign> signs;
        private final TrackLine line;

        private Phase phase = Phase.SET_UP;
        private int phaseTicks;
        private Train train;
        private GlobalStation beta;
        private Leg leg;
        private long startTick = -1;
        private double distance;

        Run(GameTestHelper helper, double throttle, List<Sign> signs) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.server = level.getServer();
            this.throttle = throttle;
            this.signs = signs;
            this.line = new TrackLine(helper, ALPHA_X + DISTANCE + 4, new int[] {ALPHA_X, ALPHA_X + DISTANCE},
                new String[] {"Alpha", "Beta"});
        }

        void begin() {
            line.cleanUpWhenTheTestEnds();
            helper.testInfo.addListener(new GameTestListener() {
                @Override
                public void testStructureLoaded(GameTestInfo test) {}

                @Override
                public void testPassed(GameTestInfo test, GameTestRunner runner) {
                    removeSigns();
                }

                @Override
                public void testFailed(GameTestInfo test, GameTestRunner runner) {
                    removeSigns();
                }

                @Override
                public void testAddedForRerun(GameTestInfo oldTest, GameTestInfo newTest, GameTestRunner runner) {}
            });
            helper.onEachTick(this::tick);
        }

        private void tick() {
            phaseTicks++;
            switch (phase) {
                case SET_UP -> {
                    if (line.setUp()) {
                        train = line.train();
                        beta = line.station(1);
                        placeSigns();
                        enter(Phase.WAIT_FOR_SIGNS);
                    }
                }
                case WAIT_FOR_SIGNS -> waitForSigns();
                case RUN -> run();
            }
        }

        private void enter(Phase next) {
            phase = next;
            phaseTicks = 0;
        }

        private BlockPos signBlock(Sign sign) {
            return new BlockPos(ALPHA_X + sign.blocksPastAlpha(), TrackLine.TRACK_Y, TrackLine.TRACK_Z + 1);
        }

        /**
         * Does what placing the item does, as {@code TrackLine} does for a station: the block, the
         * track it points at and, for a limit, the number on it. The direction is a station's, for
         * trains running east.
         */
        private void placeSigns() {
            for (Sign sign : signs) {
                Block block = BuiltInRegistries.BLOCK.get(sign.release() ? RELEASE_SIGN : LIMIT_SIGN);
                helper.assertTrue(block != Blocks.AIR, "Create Train Next has no block " + (sign.release() ? RELEASE_SIGN : LIMIT_SIGN));
                BlockPos at = helper.absolutePos(signBlock(sign));
                BlockPos track = helper.absolutePos(line.track(ALPHA_X + sign.blocksPastAlpha()));
                level.setBlock(at, block.defaultBlockState(), 3);
                CompoundTag data = new CompoundTag();
                data.put("TargetTrack", NbtUtils.writeBlockPos(track.subtract(at)));
                data.putBoolean("TargetDirection", !sign.eastward());
                if (!sign.release())
                    data.putInt("ScrollValue", sign.kmh());
                level.getBlockEntity(at).loadCustomOnly(data, level.registryAccess());
            }
        }

        /** A sign makes its point in the graph on its first tick; then the network is read, and the train set off. */
        private void waitForSigns() {
            for (Sign sign : signs) {
                BlockEntity entity = level.getBlockEntity(helper.absolutePos(signBlock(sign)));
                TrackTargetingBehaviour<?> target = entity == null ? null : BlockEntityBehaviour.get(entity, TrackTargetingBehaviour.TYPE);
                if (target == null || target.getEdgePoint() == null) {
                    if (phaseTicks > 40)
                        helper.fail("the sign " + sign + " has no place in the graph after " + phaseTicks + " ticks");
                    return;
                }
            }

            leg = CreateNetwork.read(Create.RAILWAYS.trackNetworks.values()).legs().stream()
                .filter(l -> l.from().equals("Alpha") && l.to().equals("Beta")).findFirst()
                .orElseThrow(() -> new AssertionError("the network has no leg from Alpha to Beta"));
            List<Sign> facing = signs.stream().filter(Sign::eastward).toList();
            helper.assertTrue(leg.signs().size() == facing.size(),
                "the leg from Alpha to Beta lists the signs " + leg.signs() + ", but " + facing + " face its way");
            for (int i = 0; i < facing.size(); i++) {
                Sign put = facing.get(i);
                SpeedSign read = leg.signs().get(i);
                helper.assertTrue(read.release() == put.release() && read.kmh() == put.kmh()
                        && Math.abs(read.position() - put.blocksPastAlpha()) <= 1,
                    "the sign " + put + " was read as " + read);
            }

            train.throttle = throttle;
            train.runtime.setSchedule(ScheduleTests.schedule(ScheduleTests.stop("Beta")), false);
            enter(Phase.RUN);
        }

        private void run() {
            long gameTime = server.overworld().getGameTime();
            if (startTick < 0) {
                // The first tick in which the train moves is the first tick of the model.
                if (Math.abs(train.speed) > 0) {
                    startTick = gameTime;
                    distance = train.navigation.distanceToDestination + Math.abs(train.speed);
                } else if (phaseTicks > 200)
                    helper.fail("the train did not start (" + line.describe() + ")");
                return;
            }

            if (train.getCurrentStation() == beta && train.runtime.state == ScheduleRuntime.State.POST_TRANSIT)
                finish(gameTime - startTick + 1);
            else if (gameTime - startTick > TIMEOUT_TICKS - 400)
                helper.fail("the train has not arrived after " + (gameTime - startTick) + " ticks (" + line.describe() + ")");
        }

        private void finish(long measured) {
            // The leg has the signs from Alpha's marker, and the trip is measured from where the front
            // stood, which is this far past the marker.
            double ahead = leg.distance() - distance;
            List<SpeedSign> fromTheFront = new ArrayList<>();
            for (SpeedSign sign : leg.signs())
                fromTheFront.add(new SpeedSign(sign.position() - ahead, sign.kmh(), sign.release()));
            int model = TravelTime.ticks(TrainSpecs.of(train), throttle, distance, List.of(), SpeedSigns.of(fromTheFront)).orElseThrow();
            int withoutSigns = TravelTime.ticks(TrainSpecs.of(train), throttle, distance, List.of()).orElseThrow();
            LOGGER.info("[{}] signs {}, throttle {}, the train went {} blocks: measured {} ticks ({}s), model {} ticks, {} without the signs",
                helper.testInfo.getTestName(), leg.signs(), throttle, String.format(Locale.ROOT, "%.3f", distance), measured,
                String.format(Locale.ROOT, "%.2f", measured / 20.0), model, withoutSigns);

            if (!leg.signs().isEmpty())
                helper.assertTrue(model > withoutSigns, "the signs should cost time in the model: " + model + " against " + withoutSigns);
            helper.assertTrue(Math.abs(measured - model) <= TOLERANCE_TICKS,
                "the train took " + measured + " ticks under the signs " + leg.signs() + " and the model says " + model);
            helper.succeed();
        }

        private void removeSigns() {
            try {
                for (Sign sign : signs)
                    level.setBlock(helper.absolutePos(signBlock(sign)), Blocks.AIR.defaultBlockState(), 3);
            } catch (RuntimeException e) {
                LOGGER.warn("[{}] could not take the signs down", helper.testInfo.getTestName(), e);
            }
        }
    }
}
