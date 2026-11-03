package io.github.eyunya11.createrealtimeschedule.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.map.MarkLayout.Item;
import io.github.eyunya11.createrealtimeschedule.map.MarkLayout.Rect;

class MarkLayoutTest {

    private static final Rect A = Rect.around(0, 0, 1, 1);
    private static final Rect B = Rect.around(5, 0, 1, 1);
    private static final Rect C = Rect.around(10, 0, 1, 1);

    @Test
    void boxesThatOnlyTouchDoNotOverlap() {
        assertTrue(A.overlaps(Rect.around(1.5, 0.5, 1, 1)));
        assertFalse(A.overlaps(Rect.around(2, 0, 1, 1)));
        assertFalse(A.overlaps(Rect.around(0, 3, 1, 1)));
    }

    @Test
    void aThingTakesItsBestPlaceThatIsClear() {
        assertArrayEquals(new int[] {0}, MarkLayout.place(List.of(), List.of(new Item(List.of(A, B), false))));
        assertArrayEquals(new int[] {1}, MarkLayout.place(List.of(A), List.of(new Item(List.of(A, B, C), false))));
    }

    @Test
    void aThingPutKeepsLaterThingsOffItsPlace() {
        int[] chosen = MarkLayout.place(List.of(), List.of(new Item(List.of(A), false), new Item(List.of(A, B), false),
            new Item(List.of(A, B, C), true)));
        assertArrayEquals(new int[] {0, 1, 2}, chosen);
    }

    @Test
    void withNoPlaceClearANameIsLeftOutAndAnythingElseGoesInItsBestPlace() {
        int[] chosen = MarkLayout.place(List.of(A, B), List.of(new Item(List.of(A, B), false), new Item(List.of(B, A), true),
            new Item(List.of(C), true)));
        assertArrayEquals(new int[] {0, -1, 0}, chosen);
    }
}
