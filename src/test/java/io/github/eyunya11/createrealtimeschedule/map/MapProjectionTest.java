package io.github.eyunya11.createrealtimeschedule.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.map.MapProjection.Box;

class MapProjectionTest {

    @Test
    void eachBlockOfAScreenShowsItsPartNorthUp() {
        MapProjection projection = new MapProjection(100, 50, 10, 3, 2);
        assertEquals(new Box(85, 40, 115, 60), projection.whole());
        assertEquals(new Box(85, 40, 95, 50), projection.tile(0, 1), "the top left block shows the north west");
        assertEquals(new Box(105, 50, 115, 60), projection.tile(2, 0), "the bottom right block shows the south east");
    }

    @Test
    void aBoxFitsWithRoomToSpareAndAWholeScale() {
        double[] fit = MapProjection.fit(new Box(0, 0, 100, 20), 4, 2);
        // 100 blocks across 4 blocks with a tenth spare is 27.8 blocks each, rounded up.
        assertArrayEquals(new double[] {50, 10, 28}, fit, 1e-9);
        MapProjection projection = new MapProjection(fit[0], fit[1], fit[2], 4, 2);
        assertTrue(projection.whole().contains(0, 0) && projection.whole().contains(100, 20));
        assertNull(MapProjection.fit(null, 1, 1));
        assertEquals(0.25, MapProjection.fit(new Box(0, 0, 0, 0), 8, 8)[2], 1e-9, "a point is shown at the closest scale");
    }

    @Test
    void fittingTakesInOnlyTheTrackShown() {
        TrackMap map = new TrackMap(List.of("minecraft:overworld", "minecraft:the_nether"), List.of(
            new TrackMap.Track("a", 0, List.of(new TrackMap.Spot(0, 0), new TrackMap.Spot(10, 0))),
            new TrackMap.Track("b", 0, List.of(new TrackMap.Spot(10, 0), new TrackMap.Spot(10, 40))),
            new TrackMap.Track("c", 0, List.of(new TrackMap.Spot(-100, -100), new TrackMap.Spot(-90, -100))),
            new TrackMap.Track("d", 1, List.of(new TrackMap.Spot(500, 500), new TrackMap.Spot(600, 500)))),
            List.of(), List.of(), List.of(), List.of());
        // Track b is shown only from a quarter of the way along to halfway; c is not shown, and d is of another dimension.
        java.util.Map<Integer, List<TagView.Segment>> stretches = java.util.Map.of(1, List.of(
            new TagView.Segment(0, 0.25, true, false, List.of("T")),
            new TagView.Segment(0.25, 0.5, true, true, List.of("U")),
            new TagView.Segment(0.5, 1, false, false, List.of())));
        assertEquals(new Box(0, 0, 10, 20), MapProjection.bounds(map, 0, java.util.Set.of(0, 1, 3), stretches));
        assertEquals(new Box(10, 10, 10, 20), MapProjection.bounds(map, 0, java.util.Set.of(1), stretches));
        assertNull(MapProjection.bounds(map, 0, java.util.Set.of(2), java.util.Map.of(2, List.of(new TagView.Segment(0, 1, false, false, List.of())))));
        assertNull(MapProjection.bounds(map, 1, java.util.Set.of(0, 1, 2), java.util.Map.of()));
    }

    @Test
    void aScreenMovesByItsOwnBlocks() {
        MapProjection moved = new MapProjection(100, 50, 10, 3, 2).moved(1.5, -2);
        assertEquals(new MapProjection(115, 30, 10, 3, 2), moved, "a block and a half east, two blocks north");
    }

    @Test
    void zoomingKeepsThePlaceUnderTheMouse() {
        MapProjection projection = new MapProjection(100, 50, 10, 3, 2);
        // The place a block right and half a block down of the middle shows x 110, z 55.
        MapProjection closer = projection.zoomed(0.8, 1, 0.5);
        assertEquals(8, closer.scale(), 1e-9);
        assertEquals(110, closer.centreX() + 1 * closer.scale(), 1e-9);
        assertEquals(55, closer.centreZ() + 0.5 * closer.scale(), 1e-9);
        MapProjection further = projection.zoomed(1.25, -1.5, 1);
        assertEquals(12.5, further.scale(), 1e-9);
        assertEquals(85, further.centreX() - 1.5 * further.scale(), 1e-9);
        assertEquals(60, further.centreZ() + 1 * further.scale(), 1e-9);
        MapProjection middle = projection.zoomed(1.25, 0, 0);
        assertEquals(100, middle.centreX(), "zooming at the middle keeps the middle");
        assertEquals(50, middle.centreZ());
    }

    @Test
    void zoomingStopsAtWhatAScreenMayShow() {
        MapProjection close = new MapProjection(0, 0, MapProjection.MIN_SCALE, 4, 4).zoomed(0.8, 1, 1);
        assertEquals(new MapProjection(0, 0, MapProjection.MIN_SCALE, 4, 4), close, "nothing moves at the least scale");
        MapProjection far = new MapProjection(0, 0, 4000, 4, 4).zoomed(1.25, 1, 0);
        assertEquals(MapProjection.MAX_SCALE, far.scale(), 1e-9);
        assertEquals(4000, far.centreX() + far.scale(), 1e-9, "the place under the mouse stays at the most scale too");
    }

    @Test
    void theStepsAreRoundScalesFromTheLeastToTheMost() {
        assertEquals(List.of(0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0, 12.0, 16.0), MapProjection.STEPS.subList(0, 12));
        assertEquals(MapProjection.MIN_SCALE, MapProjection.STEPS.getFirst());
        assertEquals(List.of(2048.0, 3072.0, MapProjection.MAX_SCALE), MapProjection.STEPS.subList(MapProjection.STEPS.size() - 3,
            MapProjection.STEPS.size()));
        for (int i = 1; i < MapProjection.STEPS.size(); i++) {
            double ratio = MapProjection.STEPS.get(i) / MapProjection.STEPS.get(i - 1);
            assertTrue(ratio > 1.3 && ratio <= 2, "from " + MapProjection.STEPS.get(i - 1) + " to " + MapProjection.STEPS.get(i));
        }
    }

    @Test
    void zoomingAStepGoesToTheNextStepPastTheScale() {
        assertEquals(24, MapProjection.nextStep(16, true));
        assertEquals(12, MapProjection.nextStep(16, false));
        assertEquals(24, MapProjection.nextStep(20, true), "a scale typed between the steps goes to the step past it");
        assertEquals(16, MapProjection.nextStep(20, false));
        assertEquals(1, MapProjection.nextStep(1.5, false));
        assertEquals(0.5, MapProjection.nextStep(0.75, false));
        assertEquals(MapProjection.MAX_SCALE, MapProjection.nextStep(MapProjection.MAX_SCALE, true), "nothing is past the most");
        assertEquals(MapProjection.MIN_SCALE, MapProjection.nextStep(MapProjection.MIN_SCALE, false), "nothing is past the least");
    }

    @Test
    void zoomingToAScaleKeepsThePlaceUnderTheMouse() {
        MapProjection zoomed = new MapProjection(100, 50, 10, 3, 2).zoomedTo(12, 1, 0.5);
        assertEquals(12, zoomed.scale());
        assertEquals(110, zoomed.centreX() + 1 * zoomed.scale(), 1e-9);
        assertEquals(55, zoomed.centreZ() + 0.5 * zoomed.scale(), 1e-9);
        assertEquals(MapProjection.MAX_SCALE, new MapProjection(0, 0, 10, 3, 2).zoomedTo(1e6, 0, 0).scale());
    }

    @Test
    void aScreenRoundedHasItsMiddleOnWholeBlocks() {
        assertEquals(new MapProjection(12, -4, 1.5, 3, 2), new MapProjection(12.4, -3.6, 1.5, 3, 2).rounded());
        assertEquals(new MapProjection(-29999984, 13, 16, 3, 2), new MapProjection(-29999984.3, 12.5, 16, 3, 2).rounded());
    }

    @Test
    void theScaleIsKeptToWhatAScreenMayShow() {
        assertEquals(MapProjection.MIN_SCALE, MapProjection.clampScale(0.001));
        assertEquals(MapProjection.MAX_SCALE, MapProjection.clampScale(1e9));
        assertEquals(1, MapProjection.clampScale(Double.NaN));
        assertEquals(MapProjection.MIN_SCALE, new ScreenSettings(0, 0, -5, List.of(), java.util.Set.of(), 0).scale());
    }

    @Test
    void aSegmentIsCutToTheBox() {
        Box box = new Box(0, 0, 10, 10);
        assertArrayEquals(new double[] {0, 5, 10, 5}, MapProjection.clip(-5, 5, 15, 5, box), 1e-9);
        assertArrayEquals(new double[] {2, 2, 3, 3}, MapProjection.clip(2, 2, 3, 3, box), 1e-9, "inside, as it is");
        assertArrayEquals(new double[] {10, 0, 0, 10}, MapProjection.clip(12, -2, -2, 12, box), 1e-9, "across a corner both ways");
        assertNull(MapProjection.clip(-5, -5, -1, 20, box), "outside");
        assertNull(MapProjection.clip(11, 0, 11, 10, box), "along the outside of an edge");
        assertArrayEquals(new double[] {10, 2, 10, 8}, MapProjection.clip(10, 2, 10, 8, box), 1e-9, "along the edge itself");
    }

    /** The names of the kinds of things on a map are the editor's (map.js), so that the two say the same. */
    @Test
    void theKindsOfThingsAreTheEditors() throws IOException {
        String script = Files.readString(Path.of("src", "main", "resources", "createrealtimeschedule", "editor", "js", "map.js"),
            StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("const LAYERS = \\[([^\\]]*)\\];").matcher(script);
        assertTrue(matcher.find(), "map.js has no LAYERS");
        List<String> names = Arrays.stream(matcher.group(1).split(",")).map(name -> name.trim().replace("'", "")).toList();
        assertEquals(ScreenSettings.LAYERS, names);
    }

    /** A screen has the editor's kinds and the signals' heads, which only it draws, and the settings may hide them. */
    @Test
    void aScreenMayHideTheSignalsHeadsToo() {
        List<String> others = ScreenSettings.SCREEN_LAYERS.stream().filter(layer -> !ScreenSettings.LAYERS.contains(layer)).toList();
        assertEquals(List.of("signalHeads"), others);
        assertTrue(ScreenSettings.SCREEN_LAYERS.containsAll(ScreenSettings.LAYERS));
        ScreenSettings settings = new ScreenSettings(0, 0, 1, List.of(), java.util.Set.of("signalHeads"), 0);
        assertTrue(settings.shows("signals") && !settings.shows("signalHeads"));
    }
}
