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
}
