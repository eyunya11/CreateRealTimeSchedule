package io.github.eyunya11.createrealtimeschedule.diagram;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.diagram.DiagramModel.Segment;

class DiagramViewTest {

    /** An hour from 08:00 across a plot 360 wide and 100 high, at (10, 20): ten seconds a unit. */
    private static DiagramView view() {
        DiagramView view = new DiagramView(8 * 3600, 3600);
        view.place(10, 20, 360, 100);
        return view;
    }

    @Test
    void timeRunsAcrossAndTheAxisDown() {
        DiagramView view = view();
        assertEquals(10, view.x(8 * 3600));
        assertEquals(370, view.x(9 * 3600));
        assertEquals(8 * 3600 + 100, view.second(20));
        assertEquals(20, view.y(0, 200));
        assertEquals(70, view.y(100, 200));
        assertEquals(120, view.y(200, 200));
    }

    @Test
    void zoomingKeepsTheTimeUnderThePointerWhereItIs() {
        DiagramView view = view();
        double under = view.second(100);
        view.zoom(0.5, 100);
        assertEquals(1800, view.span());
        assertEquals(under, view.second(100), 1e-9);
        view.zoom(0.001, 100);
        assertEquals(DiagramView.MIN_SPAN, view.span());
        view.zoom(1000, 100);
        assertEquals(DiagramView.MAX_SPAN, view.span());
    }

    @Test
    void panningRightBringsEarlierTimesIn() {
        DiagramView view = view();
        view.pan(36);
        assertEquals(8 * 3600 - 360, view.start());
        view.centreOn(12 * 3600);
        assertEquals(12 * 3600 - 1800, view.start());
        assertEquals(12 * 3600 + 1800, view.end());
    }

    @Test
    void theTimeScaleIsMarkedInTheFinestStepThatLeavesRoom() {
        DiagramView view = view();
        // 60 seconds is 6 units, 300 is 30, 600 is 60.
        assertEquals(300, view.step(30));
        assertEquals(600, view.step(40));
        assertEquals(6 * 3600, view.step(10_000));
    }

    @Test
    void aSegmentIsCutToTheTimeShown() {
        DiagramView view = view();
        // From 07:50 at 0 to 08:10 at 200: half of it is before 08:00.
        assertArrayEquals(new double[] {10, 70, 70, 120}, view.clip(new Segment(7 * 3600 + 3000, 0, 8 * 3600 + 600, 200), 200), 1e-9);
        assertArrayEquals(new double[] {370, 70, 370, 70}, view.clip(new Segment(9 * 3600, 100, 9 * 3600, 100), 200), 1e-9);
        assertNull(view.clip(new Segment(6 * 3600, 0, 7 * 3600, 200), 200));
        assertNull(view.clip(new Segment(10 * 3600, 0, 11 * 3600, 200), 200));
    }

    @Test
    void aSegmentBackInTimeIsCutTheSameWayAndKeepsItsEnds() {
        DiagramView view = view();
        // From 08:10 at 200 back to 07:50 at 0, as a wait's arrow can go: its to end is cut at 08:00.
        assertArrayEquals(new double[] {70, 120, 10, 70}, view.clip(new Segment(8 * 3600 + 600, 200, 7 * 3600 + 3000, 0), 200), 1e-9);
        assertNull(view.clip(new Segment(7 * 3600, 200, 6 * 3600, 0), 200));
        assertNull(view.clip(new Segment(11 * 3600, 200, 10 * 3600, 0), 200));
    }
}
