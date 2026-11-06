package io.github.eyunya11.createrealtimeschedule.gametest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.railway.CreateNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.SignalBlocks;
import io.github.eyunya11.createrealtimeschedule.traffic.Traffic;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpecs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestAssertException;
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
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Holds {@link Traffic} to two trains that really run one behind the other past light signals of Create
 * Train Next, run with {@code ./gradlew runGameTestServer} and a jar of it in {@code libs} (without it,
 * these are not registered).
 *
 * <p>Five five-aspect light signals stand 40 blocks apart on a line. One train stands at Beta, 40 blocks
 * ahead of the other at Alpha, and they leave together, the one ahead for Delta past the last signal, the
 * one behind for Gamma before it. Both reach for the block after the first signal while they speed up,
 * and Create's reservations hold up first the one and then the other; the one behind passes its signals
 * at 警戒, 減速 and 注意, and the last at 警戒 again, the one ahead standing at Delta by then. The network
 * is read as the operation reads it, the two trains are run in the simulation from where and when they
 * really started, in the order of Create's list of trains on the move, the one ahead staying at Delta,
 * and the ticks the one behind takes are compared with what the simulation says, which has to be well
 * over what the line takes on its own for the test to mean anything.
 *
 * <p>Each test has a batch of its own (see {@link TrackLine}).
 */
@GameTestHolder(Createrealtimeschedule.MODID)
@PrefixGameTestTemplate(false)
public class SignalTrafficTests {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String LONG_TRACK_LINE = Createrealtimeschedule.MODID + ":track_line_long";
    private static final ResourceLocation LIGHT_SIGNAL = ResourceLocation.fromNamespaceAndPath("createtrainnext", "multi_aspect_signal");
    private static final int TIMEOUT_TICKS = 4000;
    private static final int[] STATION_X = {6, 46, 236, 300};
    private static final String[] NAMES = {"Alpha", "Beta", "Gamma", "Delta"};
    private static final int[] LIGHT_X = {86, 126, 166, 206, 246};

    /** How far the simulation may be from the train, in ticks. */
    private static final int TOLERANCE_TICKS = 10;
    /** How much longer than on an empty line the run behind has to take in the simulation, in ticks. */
    private static final int SLOWED_AT_LEAST = 100;

    @GameTestGenerator
    public static Collection<TestFunction> signalTraffic() {
        if (!ModList.get().isLoaded("createtrainnext"))
            return List.of();
        return List.of(new TestFunction("realtimesignaltraffic_behind", "signaltraffictests.atrainbehindanotherasthesimulationhasit",
            LONG_TRACK_LINE, TIMEOUT_TICKS, 0, true, helper -> new Run(helper).begin()));
    }

    private enum Phase {
        SET_UP, WAIT_FOR_LIGHTS, RUN
    }

    /** What is known of a train once it moves: when it started and where its front was then. */
    private static final class Started {
        final Train train;
        final GlobalStation end;
        Leg leg;
        long tick = -1;
        double distance;
        long arrived = -1;

        Started(Train train, GlobalStation end) {
            this.train = train;
            this.end = end;
        }
    }

    private static final class Run {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final TrackLine line;
        private Phase phase = Phase.SET_UP;
        private int phaseTicks;
        private Started behind;
        private Started ahead;
        private SignalBlocks blocks;
        private final List<Started> order = new ArrayList<>();

        Run(GameTestHelper helper) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.line = new TrackLine(helper, 310, STATION_X, NAMES).parkATrainAt(1);
        }

        void begin() {
            line.cleanUpWhenTheTestEnds();
            helper.onEachTick(this::tick);
        }

        private void tick() {
            phaseTicks++;
            switch (phase) {
                case SET_UP -> {
                    if (line.setUp()) {
                        for (int x : LIGHT_X)
                            placeLight(x);
                        enter(Phase.WAIT_FOR_LIGHTS);
                    }
                }
                case WAIT_FOR_LIGHTS -> waitForLights();
                case RUN -> run();
            }
        }

        private void enter(Phase next) {
            phase = next;
            phaseTicks = 0;
        }

        private BlockPos lightAt(int x) {
            return line.track(x).north();
        }

        /** Does what placing the item does: a five-aspect light signal of Create Train Next for trains running east. */
        private void placeLight(int x) {
            Block block = BuiltInRegistries.BLOCK.get(LIGHT_SIGNAL);
            helper.assertTrue(block != Blocks.AIR, "Create Train Next has no block " + LIGHT_SIGNAL);
            BlockPos absolute = helper.absolutePos(lightAt(x));
            level.setBlock(absolute, block.defaultBlockState(), 3);
            CompoundTag data = new CompoundTag();
            data.put("TargetTrack", NbtUtils.writeBlockPos(helper.absolutePos(line.track(x)).subtract(absolute)));
            data.putBoolean("TargetDirection", false);
            level.getBlockEntity(absolute).loadCustomOnly(data, level.registryAccess());
        }

        /** The lights make their places in the graph on their first tick, and the parked train needs a driver; then both are sent. */
        private void waitForLights() {
            for (int x : LIGHT_X) {
                BlockEntity entity = level.getBlockEntity(helper.absolutePos(lightAt(x)));
                TrackTargetingBehaviour<?> target = entity == null ? null : BlockEntityBehaviour.get(entity, TrackTargetingBehaviour.TYPE);
                if (target == null || target.getEdgePoint() == null) {
                    if (phaseTicks > 40)
                        helper.fail("the light signal at x " + x + " has no place in the graph after " + phaseTicks + " ticks");
                    return;
                }
            }
            Train parked = line.parked(0);
            if (!parked.hasForwardConductor()) {
                if (phaseTicks > 40)
                    helper.fail("the parked train has no driver after " + phaseTicks + " ticks");
                return;
            }

            RailNetwork network = CreateNetwork.read(Create.RAILWAYS.trackNetworks.values());
            blocks = network.blocks();
            behind = new Started(line.train(), line.station(2));
            ahead = new Started(parked, line.station(3));
            behind.leg = legOf(network, "Alpha", "Gamma");
            ahead.leg = legOf(network, "Beta", "Delta");
            helper.assertValueEqual(LIGHT_X.length, ahead.leg.signals().size(), "light signals from Beta to Delta");
            for (Leg.PassedSignal signal : ahead.leg.signals())
                helper.assertValueEqual(List.of(5), blocks.signals().get(signal.signal()).lights(), "the aspects of signal " + signal);
            helper.assertTrue(blocks.speeds() != null, "the speeds of the light signals are not read");

            // Create takes its trains in the order of its list of those on the move, both of these as they set off.
            for (Train train : movingTrains())
                for (Started each : List.of(ahead, behind))
                    if (each.train == train)
                        order.add(each);
            helper.assertValueEqual(2, order.size(), "trains of the test on the move");
            behind.train.runtime.setSchedule(ScheduleTests.schedule(ScheduleTests.stop("Gamma")), false);
            ahead.train.runtime.setSchedule(ScheduleTests.schedule(ScheduleTests.stop("Delta")), false);
            enter(Phase.RUN);
        }

        private static Leg legOf(RailNetwork network, String from, String to) {
            return network.legs().stream().filter(l -> l.from().equals(from) && l.to().equals(to)).findFirst()
                .orElseThrow(() -> new GameTestAssertException("the network has no leg from " + from + " to " + to));
        }

        private void run() {
            long now = level.getServer().overworld().getGameTime();
            for (Started each : List.of(ahead, behind)) {
                // The first tick in which the train moves is the first tick of the simulation's trip.
                if (each.tick < 0 && Math.abs(each.train.speed) > 0) {
                    each.tick = now;
                    each.distance = each.train.navigation.distanceToDestination + Math.abs(each.train.speed);
                }
                if (each.tick >= 0 && each.arrived < 0 && each.train.getCurrentStation() == each.end
                    && each.train.runtime.state == ScheduleRuntime.State.POST_TRANSIT)
                    each.arrived = now;
            }
            if (behind.arrived >= 0 && ahead.tick >= 0) {
                finish();
                return;
            }
            if (phaseTicks > TIMEOUT_TICKS - 400)
                helper.fail("the train behind has not come to Gamma after " + phaseTicks + " ticks (" + line.describe() + ")");
        }

        private void finish() {
            // Each from where its front stood when it started; both stand where they stop.
            List<Traffic.Run> runs = new ArrayList<>();
            for (Started each : order)
                runs.add(new Traffic.Run(each.train.name.getString(), TrainSpecs.of(each.train), 1,
                    List.of(new Traffic.Stint(fromTheFront(each.leg, each.distance), each.tick - 1, 0, true, length(each.train))), true));
            Traffic.Result result = Traffic.run(blocks, runs);
            Traffic.Trip trip = result.trips().get(order.indexOf(behind)).getFirst();
            long measured = behind.arrived - behind.tick + 1;
            LOGGER.info("[{}] ahead left at {} ({} blocks from Delta), behind at {} ({} blocks from Gamma); behind measured {} ticks ({}s), "
                    + "simulated {} with the waits {}, {} on an empty line",
                helper.testInfo.getTestName(), ahead.tick, String.format(Locale.ROOT, "%.3f", ahead.distance), behind.tick,
                String.format(Locale.ROOT, "%.3f", behind.distance), measured, String.format(Locale.ROOT, "%.2f", measured / 20.0),
                trip.hasArrived() ? trip.ticks() : -1, trip.waits(), trip.free());
            helper.assertTrue(trip.hasArrived(), "the train behind never arrives in the simulation");
            helper.assertTrue(trip.ticks() - trip.free() >= SLOWED_AT_LEAST,
                "the train behind should be held up by the signals in the simulation: " + trip.ticks() + " ticks against " + trip.free());
            helper.assertTrue(Math.abs(measured - trip.ticks()) <= TOLERANCE_TICKS,
                "the train behind took " + measured + " ticks and the simulation says " + trip.ticks());
            helper.succeed();
        }

        /** Create's {@code GlobalRailwayManager.movingTrains}, which it keeps to itself. */
        @SuppressWarnings("unchecked")
        private static List<Train> movingTrains() {
            try {
                java.lang.reflect.Field field = GlobalRailwayManager.class.getDeclaredField("movingTrains");
                field.setAccessible(true);
                return List.copyOf((List<Train>) field.get(Create.RAILWAYS));
            } catch (ReflectiveOperationException e) {
                throw new GameTestAssertException("Create's list of trains on the move cannot be read: " + e);
            }
        }

        /** The leg as the train ran it: from where its front stood, {@code distance} blocks from the end. */
        private static Leg fromTheFront(Leg leg, double distance) {
            double ahead = leg.distance() - distance;
            List<Leg.PassedSignal> signals = leg.signals().stream().map(s -> new Leg.PassedSignal(s.at() - ahead, s.signal()))
                .filter(s -> s.at() > 0).toList();
            return new Leg(leg.from(), leg.to(), distance, leg.curves(), leg.via(), leg.signs(), leg.edges(), signals, leg.block());
        }

        /** From the front of the train to its back, as Create's points on the track have it. */
        private static double length(Train train) {
            TravellingPoint front = train.carriages.getFirst().getLeadingPoint();
            TravellingPoint back = train.carriages.getLast().getTrailingPoint();
            return front.getPosition(train.graph).distanceTo(back.getPosition(train.graph));
        }
    }
}
