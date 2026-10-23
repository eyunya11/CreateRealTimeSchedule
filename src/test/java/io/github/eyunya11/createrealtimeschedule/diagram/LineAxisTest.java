package io.github.eyunya11.createrealtimeschedule.diagram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.diagram.LineAxis.Hop;
import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Line;

class LineAxisTest {

    private static Leg leg(String from, String to, double distance) {
        return new Leg(from, to, distance, List.of(), List.of());
    }

    @Test
    void theStationsAreAsFarApartAsTheTrackBetweenThem() {
        LineAxis axis = LineAxis.of(new Line("l", "Line", List.of("A", "M", "B"), false),
            List.of(leg("A", "M", 40), leg("M", "B", 60), leg("A", "B", 100)));
        assertEquals(List.of("A", "M", "B"), axis.stations());
        assertEquals(List.of(0.0, 40.0, 100.0), axis.positions());
        assertEquals(100, axis.length());
    }

    @Test
    void theShorterWayEitherWayRoundIsTaken() {
        // On a loop the way back from M to A is the rest of the loop.
        LineAxis axis = LineAxis.of(new Line("l", "Line", List.of("A", "M"), false), List.of(leg("A", "M", 40), leg("M", "A", 160)));
        assertEquals(List.of(0.0, 40.0), axis.positions());
        LineAxis back = LineAxis.of(new Line("l", "Line", List.of("A", "M"), false), List.of(leg("M", "A", 30)));
        assertEquals(List.of(0.0, 30.0), back.positions());
    }

    @Test
    void aGapTheTrackDoesNotJoinIsTheMeanOfTheOthers() {
        LineAxis axis = LineAxis.of(new Line("l", "Line", List.of("A", "M", "B", "C"), false),
            List.of(leg("A", "M", 40), leg("B", "C", 60)));
        assertEquals(List.of(0.0, 40.0, 90.0, 150.0), axis.positions());
    }

    @Test
    void withNoTrackAtAllTheStationsAreEvenlyApart() {
        LineAxis axis = LineAxis.of(new Line("l", "Line", List.of("A", "M", "B"), false), List.of());
        assertEquals(List.of(0.0, LineAxis.UNKNOWN_GAP, 2 * LineAxis.UNKNOWN_GAP), axis.positions());
    }

    @Test
    void aWayThroughAnotherStationOfTheLineIsNoGap() {
        // On a loop, the way from B back to the depot through A is not the gap from B to the depot.
        LineAxis axis = LineAxis.of(new Line("l", "Loop", List.of("Depot", "A", "B"), true), List.of(
            leg("Depot", "A", 30), leg("A", "B", 30), new Leg("Depot", "B", 60, List.of(), List.of("A")), leg("B", "Depot", 100)));
        assertEquals(List.of("Depot", "A", "B", "Depot"), axis.stations());
        assertEquals(List.of(0.0, 30.0, 60.0, 160.0), axis.positions());
    }

    /** The depot, A, B and C, 30 blocks apart round a loop of 120. */
    private static LineAxis loop() {
        return LineAxis.of(new Line("l", "Loop", List.of("Depot", "A", "B", "C"), true),
            List.of(leg("Depot", "A", 30), leg("A", "B", 30), leg("B", "C", 30), leg("C", "Depot", 30)));
    }

    @Test
    void onALoopLineTheFirstStationComesAgainAtTheBottom() {
        LineAxis axis = loop();
        assertTrue(axis.loop());
        assertEquals(List.of(0.0, 30.0, 60.0, 90.0, 120.0), axis.positions());
        assertEquals(0, axis.row("Depot"));
        assertEquals(-1, axis.row("X"));
        assertEquals(List.of(new Hop(0, 1, 1)), axis.hops("Depot", "A", true));
        assertEquals(List.of(new Hop(3, 4, 1)), axis.hops("C", "Depot", true));
        assertEquals(List.of(new Hop(4, 3, 1)), axis.hops("Depot", "C", false));
        assertEquals(List.of(new Hop(1, 0, 1)), axis.hops("A", "Depot", false));
        assertEquals(List.of(), axis.hops("A", "X", true));
    }

    @Test
    void aWayAcrossTheFirstStationOfALoopIsTwoHops() {
        LineAxis axis = loop();
        // Down from C past the depot to A: 30 blocks to the bottom, and 30 more from the top.
        assertEquals(List.of(new Hop(3, 4, 0.5), new Hop(0, 1, 0.5)), axis.hops("C", "A", true));
        // Up from A past the depot to B: 30 blocks to the top, and 60 more from the bottom.
        List<Hop> up = axis.hops("A", "B", false);
        assertEquals(2, up.size());
        assertEquals(new Hop(1, 0, 1.0 / 3), up.getFirst());
        assertEquals(4, up.getLast().fromRow());
        assertEquals(2, up.getLast().toRow());
    }

    @Test
    void aTrainRunsTheWayRoundThatPassesFewerStations() {
        LineAxis axis = loop();
        assertTrue(axis.down(List.of("A", "B", "C", "Depot")));
        assertFalse(axis.down(List.of("C", "B", "A")));
        assertTrue(axis.down(List.of("C", "A")), "two stations either way round goes down");
        LineAxis straight = LineAxis.of(new Line("l", "Line", List.of("A", "B"), false), List.of());
        assertTrue(straight.down(List.of("B", "A")));
        assertEquals(List.of(new Hop(1, 0, 1)), straight.hops("B", "A", true));
    }

    @Test
    void theTrackShowsWhichWayRoundATrainGoes() {
        LineAxis axis = LineAxis.of(new Line("l", "Loop", List.of("Depot", "A", "B", "C"), true), List.of(
            leg("Depot", "A", 30), leg("A", "B", 30), leg("B", "C", 30), leg("C", "Depot", 30),
            new Leg("Depot", "B", 60, List.of(), List.of("A")), new Leg("B", "A", 90, List.of(), List.of("C", "Depot")),
            new Leg("A", "C", 60, List.of(), List.of("Depot"))));
        assertEquals(Boolean.TRUE, axis.down("Depot", "A"));
        assertEquals(Boolean.TRUE, axis.down("Depot", "B"), "through A, down");
        assertEquals(Boolean.TRUE, axis.down("B", "A"), "through C and the depot, down round the loop");
        assertEquals(Boolean.FALSE, axis.down("A", "C"), "through the depot, up");
        assertNull(axis.down("C", "B"), "no track from C to B");
        LineAxis straight = LineAxis.of(new Line("l", "Line", List.of("A", "B"), false), List.of(leg("A", "B", 30)));
        assertEquals(List.of(), straight.ways());
    }
}
