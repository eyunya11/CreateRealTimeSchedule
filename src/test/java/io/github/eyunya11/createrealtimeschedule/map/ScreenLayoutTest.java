package io.github.eyunya11.createrealtimeschedule.map;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.map.ScreenLayout.Cell;
import io.github.eyunya11.createrealtimeschedule.map.ScreenLayout.Place;

class ScreenLayoutTest {

    private static Set<Cell> rectangle(int fromU, int fromV, int width, int height) {
        Set<Cell> cells = new HashSet<>();
        for (int u = 0; u < width; u++)
            for (int v = 0; v < height; v++)
                cells.add(new Cell(fromU + u, fromV + v));
        return cells;
    }

    @Test
    void aFullRectangleIsOneScreenCountedFromItsBottomLeft() {
        Map<Cell, Place> places = ScreenLayout.of(rectangle(5, -2, 3, 2));
        assertEquals(6, places.size());
        assertEquals(new Place(0, 0, 3, 2), places.get(new Cell(5, -2)));
        assertEquals(new Place(2, 1, 3, 2), places.get(new Cell(7, -1)));
    }

    @Test
    void aGroupThatIsNoRectangleIsAScreenForEachBlock() {
        Set<Cell> shape = rectangle(0, 0, 2, 2);
        shape.remove(new Cell(1, 1));
        Map<Cell, Place> places = ScreenLayout.of(shape);
        assertEquals(3, places.size());
        places.values().forEach(place -> assertEquals(Place.ALONE, place));
    }

    @Test
    void aRectangleLargerThanTheMostIsAScreenForEachBlock() {
        assertEquals(new Place(0, 0, 12, 1), ScreenLayout.of(rectangle(0, 0, 12, 1)).get(new Cell(0, 0)));
        assertEquals(Place.ALONE, ScreenLayout.of(rectangle(0, 0, 13, 1)).get(new Cell(0, 0)));
    }

    @Test
    void oneBlockIsAScreenOfOne() {
        assertEquals(Map.of(new Cell(4, 4), Place.ALONE), ScreenLayout.of(Set.of(new Cell(4, 4))));
    }
}
