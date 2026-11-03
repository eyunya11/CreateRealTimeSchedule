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
    void aSlantingBoxBesideSlantingTrackIsClearOfIt() {
        // Track from the bottom left to the top right, and a head along it a little to its right:
        // their upright bounds would overlap, but they do not.
        Rect track = Rect.line(0, 0, 10, 10, 0.05);
        double s = Math.sqrt(0.5);
        Rect head = Rect.along(5 + 0.3 * s, 5 - 0.3 * s, 1, 1, 0.5, 0.2);
        assertFalse(head.overlaps(track));
        assertFalse(track.overlaps(head));
        assertTrue(Rect.along(5 + 0.1 * s, 5 - 0.1 * s, 1, 1, 0.5, 0.2).overlaps(track), "a head nearer than its half width lies over the track");
        assertTrue(Rect.around(5, 5, 0.1, 0.1).overlaps(track), "a box on the track");
        assertFalse(Rect.around(5, 0, 0.5, 0.5).overlaps(track), "a box well off the track");
    }

    @Test
    void aThingIsKeptOffTheTrack() {
        Rect track = Rect.line(-10, 0, 10, 0, 0.05);
        int[] chosen = MarkLayout.place(List.of(track), List.of(new Item(List.of(Rect.around(0, 0.1, 0.2, 0.2), Rect.around(0, 0.4, 0.2, 0.2)),
            false)));
        assertArrayEquals(new int[] {1}, chosen);
    }

    @Test
    void aThingOnAPostDoesNotGoWhereThePostCrossesOtherTrack() {
        // A head on a post from track along v = 0 up past track along v = 1, or the same way down.
        Rect other = Rect.line(-10, 1, 10, 1, 0.05);
        List<Rect> places = List.of(Rect.around(0, 1.5, 0.2, 0.2), Rect.around(0, -0.5, 0.2, 0.2));
        List<Rect> posts = List.of(Rect.line(0, 0.1, 0, 1.3, 0.01), Rect.line(0, -0.1, 0, -0.3, 0.01));
        assertArrayEquals(new int[] {1}, MarkLayout.place(List.of(other), List.of(new Item(places, posts, false))));
        assertArrayEquals(new int[] {0}, MarkLayout.place(List.of(other), List.of(new Item(places, false))), "with no posts the head may go over");
        // A post put keeps later things off it.
        int[] chosen = MarkLayout.place(List.of(), List.of(new Item(places.subList(0, 1), posts.subList(0, 1), false),
            new Item(List.of(Rect.around(0, 0.7, 0.1, 0.1), Rect.around(3, 0.7, 0.1, 0.1)), false)));
        assertArrayEquals(new int[] {0, 1}, chosen);
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
