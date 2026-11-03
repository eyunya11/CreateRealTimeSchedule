package io.github.eyunya11.createrealtimeschedule.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.map.MapProjection.Box;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Spot;

class PolylineTest {

    private static final double HALF = 0.1;
    private static final double CLOSE = 1e-9;

    @Test
    void aStraightLineEndsSquareAtItsPoints() {
        List<double[]> corners = Polyline.outline(List.of(new Spot(0, 0), new Spot(2, 0)), HALF);
        assertEquals(2, corners.size());
        assertArrayEquals(new double[] {0, HALF, 0, -HALF}, corners.get(0), CLOSE);
        assertArrayEquals(new double[] {2, HALF, 2, -HALF}, corners.get(1), CLOSE);
    }

    @Test
    void aBendIsMitredSoThatItsEdgesMeet() {
        // Along x, then a left turn up z: the outer edge reaches out to the corner of both edges.
        List<double[]> corners = Polyline.outline(List.of(new Spot(0, 0), new Spot(1, 0), new Spot(1, 1)), HALF);
        assertArrayEquals(new double[] {1 - HALF, HALF, 1 + HALF, -HALF}, corners.get(1), CLOSE);
        assertArrayEquals(new double[] {1 - HALF, 1, 1 + HALF, 1}, corners.get(2), CLOSE);
    }

    @Test
    void aSharpBendIsCutBack() {
        List<double[]> corners = Polyline.outline(List.of(new Spot(0, 0), new Spot(1, 0), new Spot(0, 0.01)), HALF);
        double[] bend = corners.get(1);
        assertTrue(Math.hypot(bend[0] - 1, bend[1]) <= Polyline.MITRE_LIMIT * HALF + CLOSE, "the corner reaches " + bend[0] + ", " + bend[1]);
    }

    @Test
    void twoLinesThatMeetEndToEndOnlyTouch() {
        // As two dashes of a stretch: the first ends where the second starts, and neither goes past.
        List<double[]> first = Polyline.outline(List.of(new Spot(0, 0), new Spot(1, 0)), HALF);
        List<double[]> second = Polyline.outline(List.of(new Spot(1, 0), new Spot(2, 0), new Spot(2, 1)), HALF);
        assertArrayEquals(first.getLast(), second.getFirst(), CLOSE);
    }

    @Test
    void pointsOnTopOfOneAnotherAreLeftOut() {
        List<double[]> corners = Polyline.outline(List.of(new Spot(0, 0), new Spot(0, 0), new Spot(1, 0)), HALF);
        assertEquals(2, corners.size());
        assertEquals(0, Polyline.outline(List.of(new Spot(3, 3), new Spot(3, 3)), HALF).size());
    }

    @Test
    void aLineIsCutIntoTheRunsInTheBox() {
        Box box = new Box(0, 0, 10, 10);
        // In, out over the top, and in again: two runs, the bends inside kept in one.
        List<List<Spot>> runs = Polyline.runs(List.of(new Spot(2, 2), new Spot(5, 2), new Spot(5, 12), new Spot(7, 12), new Spot(7, 8)), box);
        assertEquals(2, runs.size());
        assertEquals(List.of(new Spot(2, 2), new Spot(5, 2), new Spot(5, 10)), runs.get(0));
        assertEquals(List.of(new Spot(7, 10), new Spot(7, 8)), runs.get(1));
        assertEquals(List.of(), Polyline.runs(List.of(new Spot(20, 20), new Spot(30, 20)), box));
    }
}
