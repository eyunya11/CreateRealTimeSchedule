package io.github.eyunya11.createrealtimeschedule.diagram;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

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
    void onALoopLineTheFirstStationComesAgainAtTheBottom() {
        LineAxis axis = LineAxis.of(new Line("l", "Loop", List.of("Depot", "A", "B"), true),
            List.of(leg("Depot", "A", 30), leg("A", "B", 30), leg("B", "Depot", 100), leg("Depot", "B", 60)));
        assertEquals(List.of("Depot", "A", "B", "Depot"), axis.stations());
        // B to the depot is 60 blocks the other way round, which is shorter.
        assertEquals(List.of(0.0, 30.0, 60.0, 120.0), axis.positions());
        assertArrayEquals(new int[] {0, 1}, axis.rows("Depot", "A"));
        assertArrayEquals(new int[] {2, 3}, axis.rows("B", "Depot"));
        assertArrayEquals(new int[0], axis.rows("B", "X"));
        assertEquals(0, axis.row("Depot"));
        assertEquals(-1, axis.row("X"));
    }
}
