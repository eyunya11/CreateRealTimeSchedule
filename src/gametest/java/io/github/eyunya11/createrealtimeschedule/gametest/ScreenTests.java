package io.github.eyunya11.createrealtimeschedule.gametest;

import java.util.List;
import java.util.Set;

import com.simibubi.create.Create;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.ModRegistry;
import io.github.eyunya11.createrealtimeschedule.gametest.TrackLine.Signal;
import io.github.eyunya11.createrealtimeschedule.map.LiveMap;
import io.github.eyunya11.createrealtimeschedule.map.ScreenLayout.Place;
import io.github.eyunya11.createrealtimeschedule.map.ScreenSettings;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap;
import io.github.eyunya11.createrealtimeschedule.railway.CreateMap;
import io.github.eyunya11.createrealtimeschedule.screen.MapScreenBlockEntity;
import io.github.eyunya11.createrealtimeschedule.screen.ScreenGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks the map screens: that blocks side by side make one screen and share what it shows, and that
 * the live part of the map names the tracks as the map does. Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(Createrealtimeschedule.MODID)
@PrefixGameTestTemplate(false)
public class ScreenTests {

    private static final String TRACK_LINE = "track_line";
    private static final int TIMEOUT_TICKS = 400;

    /** Screen blocks facing north, in a wall above the empty ground of the structure, from the east (their left as one looks at them). */
    private static BlockPos at(int fromEast, int up) {
        return new BlockPos(20 - fromEast, 3 + up, 8);
    }

    private static void place(GameTestHelper helper, BlockPos pos, Direction facing) {
        helper.setBlock(pos, ModRegistry.MAP_SCREEN.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing));
    }

    private static MapScreenBlockEntity entity(GameTestHelper helper, BlockPos pos) {
        if (helper.getBlockEntity(pos) instanceof MapScreenBlockEntity entity)
            return entity;
        throw new net.minecraft.gametest.framework.GameTestAssertException("no map screen at " + pos);
    }

    @GameTest(template = TRACK_LINE, batch = "realtimescreen_group", timeoutTicks = TIMEOUT_TICKS)
    public static void blocksSideBySideMakeOneScreenAndShareWhatItShows(GameTestHelper helper) {
        for (int u = 0; u < 2; u++)
            for (int v = 0; v < 2; v++)
                place(helper, at(u, v), Direction.NORTH);
        // One that faces another way, beside them, is a screen of its own.
        place(helper, at(2, 0), Direction.EAST);
        helper.runAfterDelay(3, () -> {
            for (int u = 0; u < 2; u++)
                for (int v = 0; v < 2; v++)
                    helper.assertValueEqual(new Place(u, v, 2, 2), entity(helper, at(u, v)).place(), "the place of the block " + u + ", " + v);
            helper.assertValueEqual(Place.ALONE, entity(helper, at(2, 0)).place(), "the place of the block facing east");

            ScreenGroup.change(helper.getLevel(), helper.absolutePos(at(1, 1)),
                settings -> new ScreenSettings(120, -40, 8, List.of("main"), Set.of("trains"), 0));
            for (int u = 0; u < 2; u++)
                for (int v = 0; v < 2; v++) {
                    ScreenSettings settings = entity(helper, at(u, v)).settings();
                    helper.assertTrue(settings.centreX() == 120 && settings.centreZ() == -40 && settings.scale() == 8
                        && settings.lines().equals(List.of("main")) && !settings.shows("trains"), "the block " + u + ", " + v + " shows " + settings);
                }
            helper.assertValueEqual(ScreenSettings.DEFAULT, entity(helper, at(2, 0)).settings(), "the settings of the block facing east");

            // With one block gone the rest is no rectangle: a screen of one block each, still showing what the screen did.
            helper.setBlock(at(1, 1), Blocks.AIR);
            helper.runAfterDelay(2, () -> {
                for (BlockPos pos : List.of(at(0, 0), at(1, 0), at(0, 1))) {
                    helper.assertValueEqual(Place.ALONE, entity(helper, pos).place(), "the place of a block left");
                    helper.assertValueEqual(120.0, entity(helper, pos).settings().centreX(), "the middle a block left shows");
                }
                // Put back, it takes on what the others show, though it was new.
                place(helper, at(1, 1), Direction.NORTH);
                helper.runAfterDelay(3, () -> {
                    helper.assertValueEqual(new Place(1, 1, 2, 2), entity(helper, at(1, 1)).place(), "the place of the block put back");
                    helper.assertValueEqual(120.0, entity(helper, at(1, 1)).settings().centreX(), "the middle the block put back shows");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = TRACK_LINE, batch = "realtimescreen_live", timeoutTicks = 1500)
    public static void theLivePartOfTheMapNamesTheTracksAsTheMapDoes(GameTestHelper helper) {
        TrackLine line = new TrackLine(helper, 50, new int[] {6, 26, 46}, new String[] {"Alpha", "Beta", "Gamma"},
            List.of(new Signal(16, false), new Signal(36, true)));
        line.cleanUpWhenTheTestEnds();
        boolean[] done = {false};
        helper.onEachTick(() -> {
            if (done[0] || !line.setUp())
                return;
            done[0] = true;
            var server = helper.getLevel().getServer();
            String dimension = helper.getLevel().dimension().location().toString();
            TrackMap map = CreateMap.read(Create.RAILWAYS.trackNetworks.values(), Create.RAILWAYS.trains.values(), server::getLevel).map();
            LiveMap live = CreateMap.live(Create.RAILWAYS.trackNetworks.values(), Create.RAILWAYS.trains.values(), server::getLevel, dimension);
            Set<String> keys = Set.copyOf(map.tracks().stream().map(TrackMap.Track::key).toList());
            helper.assertTrue(live.signals().size() >= 2, "the live map has the signals: " + live.signals());
            for (LiveMap.Signal signal : live.signals())
                helper.assertTrue(keys.contains(signal.track()), "a signal is on a track the map does not have: " + signal);
            helper.assertTrue(!live.trains().isEmpty(), "the live map has no train");
            for (LiveMap.Train train : live.trains())
                for (LiveMap.Carriage carriage : train.carriages())
                    helper.assertTrue(keys.contains(carriage.frontTrack()) && keys.contains(carriage.backTrack()),
                        "a carriage is on a track the map does not have: " + carriage);
            LiveMap elsewhere = CreateMap.live(Create.RAILWAYS.trackNetworks.values(), Create.RAILWAYS.trains.values(), server::getLevel,
                "minecraft:the_end");
            helper.assertTrue(elsewhere.signals().isEmpty() && elsewhere.trains().isEmpty(), "the end has the overworld's signals or trains");
            helper.succeed();
        });
    }
}
