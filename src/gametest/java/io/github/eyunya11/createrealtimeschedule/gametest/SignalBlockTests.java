package io.github.eyunya11.createrealtimeschedule.gametest;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.map.SignalLooks;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Spot;
import io.github.eyunya11.createrealtimeschedule.railway.CreateMap;
import io.github.eyunya11.createrealtimeschedule.railway.CreateNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.SignalBlocks;
import io.github.eyunya11.createrealtimeschedule.gametest.TrackLine.Signal;
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
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Holds the signal blocks that {@link CreateNetwork} reads to the track: three stations in a row, Alpha,
 * Beta and Gamma 20 blocks apart, with a signal for trains running east halfway between each two. The
 * leg from Alpha to Gamma passes both signals where they stand, Beta is in the block the first leads
 * into, and the first ends at the second. With Create Train Next, the second is one of its four-aspect
 * light signals, and the network says so, with the speeds of its config.
 *
 * <p>Each test has a batch of its own (see {@link TrackLine}).
 */
@GameTestHolder(Createrealtimeschedule.MODID)
@PrefixGameTestTemplate(false)
public class SignalBlockTests {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String TRACK_LINE = Createrealtimeschedule.MODID + ":track_line";
    private static final int TIMEOUT_TICKS = 400;
    private static final int[] STATION_X = {6, 26, 46};
    private static final String[] NAMES = {"Alpha", "Beta", "Gamma"};
    private static final int FIRST_X = 16;
    private static final int SECOND_X = 36;
    private static final ResourceLocation LIGHT_SIGNAL = ResourceLocation.fromNamespaceAndPath("createtrainnext", "multi_aspect_signal_4");

    @GameTestGenerator
    public static Collection<TestFunction> signalBlocks() {
        List<TestFunction> tests = new ArrayList<>();
        tests.add(new TestFunction("realtimesignalblocks_create", "signalblocktests.theblocksofalinearereadascreatehasthem", TRACK_LINE,
            TIMEOUT_TICKS, 0, true, helper -> check(helper, false)));
        if (ModList.get().isLoaded("createtrainnext")) {
            tests.add(new TestFunction("realtimesignalblocks_light", "signalblocktests.alightsignalisreadwithitsaspects", TRACK_LINE,
                TIMEOUT_TICKS, 0, true, helper -> check(helper, true)));
            tests.add(new TestFunction("realtimesignalblocks_unloaded", "signalblocktests.alightsignalwhosechunkisnotloadedisread",
                TRACK_LINE, TIMEOUT_TICKS, 0, true, SignalBlockTests::aLightSignalWhoseChunkIsNotLoadedIsRead));
        }
        return tests;
    }

    /** Outside the world border, so never loaded. */
    private static final BlockPos FAR = new BlockPos(30000000, 2, 29999984);

    /** Another, as {@link #FAR}. */
    private static final BlockPos FARTHER = new BlockPos(30000000, 2, 29999968);

    /**
     * A colour light signal of Create Train Next whose chunk is not loaded still holds trains to its
     * aspects, as Create Train Next keeps a table of which head stands where ({@code SignalHeads}); the
     * network reads it from there, and the map shows it, with the aspect Create Train Next works out.
     *
     * <p>A chunk cannot be unloaded from inside a test, so, as Create Train Next's own tests do, the head is
     * a position far outside the world put into the first signal's records, with its type written into the
     * table the way a head once loaded there writes it. Until it is written in, the head is not known, and
     * the signal is read as Create's own. Writing it in, and taking it out, changes the fingerprint.
     */
    private static void aLightSignalWhoseChunkIsNotLoadedIsRead(GameTestHelper helper) {
        TrackLine line = new TrackLine(helper, 50, STATION_X, NAMES, List.of(new Signal(FIRST_X, true), new Signal(SECOND_X, true)));
        line.cleanUpWhenTheTestEnds();
        ServerLevel level = helper.getLevel();
        SignalBoundary[] signal = {null};
        boolean[] side = {false};
        helper.onEachTick(() -> {
            if (signal[0] != null || !line.setUp())
                return;
            BlockEntity entity = level.getBlockEntity(helper.absolutePos(line.track(FIRST_X).north()));
            TrackTargetingBehaviour<?> target = entity == null ? null : BlockEntityBehaviour.get(entity, TrackTargetingBehaviour.TYPE);
            helper.assertTrue(target != null && target.getEdgePoint() instanceof SignalBoundary, "the first signal has no place in the graph");
            signal[0] = (SignalBoundary) target.getEdgePoint();
            side[0] = !signal[0].blockEntities.get(true).isEmpty();
            helper.assertFalse(level.isLoaded(FAR), "setup: the far head should not be loaded");
            try {
                Collection<TrackGraph> graphs = Create.RAILWAYS.trackNetworks.values();
                long before = CreateNetwork.fingerprint(graphs);
                helper.assertTrue(firstSignal(helper).lights().isEmpty(), "Create's own signal is read as " + firstSignal(helper));

                signal[0].blockEntities.get(side[0]).put(FAR, false);
                helper.assertTrue(firstSignal(helper).lights().isEmpty(), "a head never seen loaded is read as " + firstSignal(helper));
                helper.assertValueEqual(before, CreateNetwork.fingerprint(graphs), "the fingerprint with a head never seen loaded");

                Spot at = new Spot(helper.absolutePos(line.track(FIRST_X)).getX() + 0.5, helper.absolutePos(line.track(FIRST_X)).getZ() + 0.5);
                TrackMap.SignalSide create = onTheMap(helper, graphs, at);
                helper.assertValueEqual(SignalLooks.CREATE, create.kind(), "the kind on the map of a head never seen loaded");

                heads("record", level, FAR, signalType("FIVE"));
                helper.assertValueEqual(List.of(5), firstSignal(helper).lights().stream().toList(), "the aspects of the head that is not loaded");
                helper.assertTrue(CreateNetwork.fingerprint(graphs) != before, "a head written into the table is a change of the track");
                TrackMap.SignalSide five = onTheMap(helper, graphs, at);
                helper.assertTrue(five.kind().equals("light5") && !five.lamps().isEmpty(), "the map does not show the head that is not loaded: " + five);

                // A second head on the same side, of six aspects, which on the clear line shows a less strict aspect than the
                // first: the map shows the stricter, whichever of the two positions it is at.
                Object graph = heads("graphOf", signal[0]);
                Enum<?> fiveShows = (Enum<?>) heads("resolve", graph, signal[0], side[0], signalType("FIVE"));
                Enum<?> sixShows = (Enum<?>) heads("resolve", graph, signal[0], side[0], signalType("SIX"));
                LOGGER.info("[{}] the head of five aspects shows {}, the one of six {}", helper.testInfo.getTestName(), fiveShows, sixShows);
                helper.assertTrue(fiveShows.ordinal() < sixShows.ordinal(), "setup: the head of five aspects should show a stricter aspect");
                signal[0].blockEntities.get(side[0]).put(FARTHER, false);
                TrackMap.SignalSide both = null;
                for (boolean fiveFar : new boolean[] {true, false}) {
                    heads("record", level, FAR, signalType(fiveFar ? "FIVE" : "SIX"));
                    heads("record", level, FARTHER, signalType(fiveFar ? "SIX" : "FIVE"));
                    helper.assertValueEqual(List.of(5, 6), firstSignal(helper).lights().stream().toList(), "the aspects of the two heads");
                    both = onTheMap(helper, graphs, at);
                    helper.assertValueEqual("light5", both.kind(), "the kind on the map of two heads");
                    helper.assertValueEqual(SignalLooks.aspect(fiveShows.name()), both.lamps(), "the lamps on the map of two heads");
                }

                // Heads on the two sides that swap their kinds are a change of the track.
                signal[0].blockEntities.get(side[0]).remove(FARTHER);
                signal[0].blockEntities.get(!side[0]).put(FARTHER, false);
                long swapped = CreateNetwork.fingerprint(graphs);
                heads("record", level, FAR, signalType("FIVE"));
                heads("record", level, FARTHER, signalType("SIX"));
                helper.assertValueEqual(List.of(5), firstSignal(helper).lights().stream().toList(), "the aspects of the head swapped");
                helper.assertTrue(CreateNetwork.fingerprint(graphs) != swapped, "heads that swap their kinds are a change of the track");

                heads("forget", level, FAR);
                heads("forget", level, FARTHER);
                signal[0].blockEntities.get(!side[0]).remove(FARTHER);
                helper.assertTrue(firstSignal(helper).lights().isEmpty(), "a head taken out of the table is read as " + firstSignal(helper));
                helper.assertValueEqual(before, CreateNetwork.fingerprint(graphs), "the fingerprint once the head is taken out");
                // What cannot be had now is what was last read.
                helper.assertValueEqual(both, onTheMap(helper, graphs, at), "the map once the head is taken out");
            } finally {
                for (boolean each : new boolean[] {true, false}) {
                    signal[0].blockEntities.get(each).remove(FAR);
                    signal[0].blockEntities.get(each).remove(FARTHER);
                }
                heads("forget", level, FAR);
                heads("forget", level, FARTHER);
            }
            helper.succeed();
        });
    }

    /** The first signal of the leg from Alpha to Gamma, as the network reads it now. */
    private static SignalBlocks.Signal firstSignal(GameTestHelper helper) {
        RailNetwork network = CreateNetwork.read(Create.RAILWAYS.trackNetworks.values());
        Leg alphaGamma = legOf(helper, network, "Alpha", "Gamma");
        return network.blocks().signals().get(alphaGamma.signals().get(0).signal());
    }

    /** The one side the map shows of the signal at {@code at}. */
    private static TrackMap.SignalSide onTheMap(GameTestHelper helper, Collection<TrackGraph> graphs, Spot at) {
        List<TrackMap.SignalSide> sides = CreateMap.read(graphs, List.of(), helper.getLevel().getServer()::getLevel).map().signals().stream()
            .filter(mark -> Math.hypot(mark.at().x() - at.x(), mark.at().z() - at.z()) <= 0.6)
            .flatMap(mark -> mark.sides().stream()).toList();
        LOGGER.info("[{}] the first signal on the map: {}", helper.testInfo.getTestName(), sides);
        helper.assertValueEqual(1, sides.size(), "the sides of the first signal on the map: " + sides);
        return sides.get(0);
    }

    /** Calls a static method of Create Train Next's {@code SignalHeads}, which this does not compile against. */
    private static Object heads(String method, Object... arguments) {
        try {
            for (Method each : Class.forName("com.eyunya.createtrainnext.signal.SignalHeads").getMethods())
                if (each.getName().equals(method) && each.getParameterCount() == arguments.length)
                    return each.invoke(null, arguments);
            throw new AssertionError("Create Train Next's SignalHeads has no " + method);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not call SignalHeads." + method, e);
        }
    }

    private static Object signalType(String name) {
        try {
            for (Object constant : Class.forName("com.eyunya.createtrainnext.signal.SignalType").getEnumConstants())
                if (((Enum<?>) constant).name().equals(name))
                    return constant;
            throw new AssertionError("Create Train Next has no signal type " + name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Create Train Next has no SignalType", e);
        }
    }

    private static void check(GameTestHelper helper, boolean light) {
        List<Signal> signals = light ? List.of(new Signal(FIRST_X, true)) : List.of(new Signal(FIRST_X, true), new Signal(SECOND_X, true));
        TrackLine line = new TrackLine(helper, 50, STATION_X, NAMES, signals);
        line.cleanUpWhenTheTestEnds();
        BlockPos lightAt = line.track(SECOND_X).north();
        int[] phase = {0};
        int[] waited = {0};
        helper.onEachTick(() -> {
            if (phase[0] == 0) {
                if (!line.setUp())
                    return;
                if (light)
                    placeLight(helper, lightAt, line.track(SECOND_X));
                phase[0] = 1;
                return;
            }
            if (phase[0] != 1)
                return;
            if (light && !inTheGraph(helper, lightAt)) {
                if (++waited[0] > 40)
                    helper.fail("the light signal has no place in the graph after 40 ticks");
                return;
            }
            phase[0] = 2;
            RailNetwork network = CreateNetwork.read(Create.RAILWAYS.trackNetworks.values());
            SignalBlocks blocks = network.blocks();
            Leg alphaGamma = legOf(helper, network, "Alpha", "Gamma");
            Leg betaGamma = legOf(helper, network, "Beta", "Gamma");
            LOGGER.info("[{}] Alpha to Gamma: block {}, signals {}; Beta to Gamma: block {}, signals {}; signals {}, speeds {}",
                helper.testInfo.getTestName(), alphaGamma.block(), alphaGamma.signals(), betaGamma.block(), betaGamma.signals(),
                blocks.signals(), blocks.speeds());

            helper.assertValueEqual(2, alphaGamma.signals().size(), "signals from Alpha to Gamma");
            Leg.PassedSignal first = alphaGamma.signals().get(0);
            Leg.PassedSignal second = alphaGamma.signals().get(1);
            helper.assertTrue(Math.abs(first.at() - (FIRST_X - STATION_X[0])) <= 1 && Math.abs(second.at() - (SECOND_X - STATION_X[0])) <= 1,
                "the signals are at " + first.at() + " and " + second.at() + " from Alpha, not where they stand");
            SignalBlocks.Signal into = blocks.signals().get(first.signal());
            SignalBlocks.Signal last = blocks.signals().get(second.signal());
            helper.assertTrue(alphaGamma.block() >= 0 && into.block() != alphaGamma.block() && last.block() != into.block(),
                "the three stretches are not three blocks: " + alphaGamma.block() + ", " + into.block() + ", " + last.block());
            helper.assertValueEqual(into.block(), betaGamma.block(), "the block Beta is in");
            helper.assertValueEqual(List.of(second.signal()), betaGamma.signals().stream().map(Leg.PassedSignal::signal).toList(),
                "signals from Beta to Gamma");
            helper.assertTrue(into.next().contains(second.signal()), "the block after the first signal does not end at the second: " + into);
            helper.assertTrue(into.lights().isEmpty() && !into.cross(), "Create's own signal is read as " + into);
            if (light) {
                helper.assertValueEqual(List.of(4), last.lights(), "the aspects of the light signal");
                helper.assertTrue(blocks.speeds() != null, "the speeds of the light signals are not read");
            }
            helper.succeed();
        });
    }

    private static Leg legOf(GameTestHelper helper, RailNetwork network, String from, String to) {
        return network.legs().stream().filter(leg -> leg.from().equals(from) && leg.to().equals(to)).findFirst()
            .orElseThrow(() -> new AssertionError("no leg from " + from + " to " + to));
    }

    /** Does what placing the item does: a light signal of Create Train Next for trains running east. */
    private static void placeLight(GameTestHelper helper, BlockPos at, BlockPos track) {
        ServerLevel level = helper.getLevel();
        Block block = BuiltInRegistries.BLOCK.get(LIGHT_SIGNAL);
        helper.assertTrue(block != Blocks.AIR, "Create Train Next has no block " + LIGHT_SIGNAL);
        BlockPos absolute = helper.absolutePos(at);
        level.setBlock(absolute, block.defaultBlockState(), 3);
        CompoundTag data = new CompoundTag();
        data.put("TargetTrack", NbtUtils.writeBlockPos(helper.absolutePos(track).subtract(absolute)));
        data.putBoolean("TargetDirection", false);
        level.getBlockEntity(absolute).loadCustomOnly(data, level.registryAccess());
    }

    private static boolean inTheGraph(GameTestHelper helper, BlockPos at) {
        BlockEntity entity = helper.getLevel().getBlockEntity(helper.absolutePos(at));
        TrackTargetingBehaviour<?> target = entity == null ? null : BlockEntityBehaviour.get(entity, TrackTargetingBehaviour.TYPE);
        return target != null && target.getEdgePoint() != null;
    }
}
