package io.github.eyunya11.createrealtimeschedule.railway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Edge;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Marker;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Place;
import io.github.eyunya11.createrealtimeschedule.railway.TagSections.Kind;
import io.github.eyunya11.createrealtimeschedule.railway.TagSections.Problem;
import io.github.eyunya11.createrealtimeschedule.railway.TagSections.Span;

/** The stretches of track the tag blocks tag, and what is wrong with them. */
class TagSectionsTest {

    private static final Place BLOCK = new Place(0, 0, 0);

    private static Edge edge(double length, int start, int end, Integer... next) {
        return new Edge(length, false, List.of(next)).withNodes(start, end);
    }

    private static Marker marker(int edge, double offset, int tag) {
        return new Marker(edge, offset, tag, BLOCK);
    }

    /** A line of three nodes 0, 1, 2, 50 blocks apart: edges 0 (0 to 1) and 2 (1 to 2) east, 3 (2 to 1) and 1 (1 to 0) west. */
    private static List<Edge> line() {
        return List.of(edge(50, 0, 1, 2), edge(50, 1, 0), edge(50, 1, 2), edge(50, 2, 1, 1));
    }

    @Test
    void aSectionRunsToTheNextBlockFacingTheSameWayAndHoldsBothWays() {
        // A block on edge 0, 20 in, with tag 1; the next facing east is on edge 2, 30 in, with tag 2.
        TagSections sections = TagSections.of(new RailNetwork(line(), List.of(), List.of(), List.of(marker(0, 20, 1), marker(2, 30, 2))));
        assertEquals(List.of(new Span(20, 50, 1, 0)), sections.spans(0));
        assertEquals(List.of(new Span(0, 30, 1, 0), new Span(30, 50, 2, 1)), sections.spans(2));
        // The same stretches on the edges the other way.
        assertEquals(List.of(new Span(0, 20, 2, 1), new Span(20, 50, 1, 0)), sections.spans(3));
        assertEquals(List.of(new Span(0, 30, 1, 0)), sections.spans(1));
        assertEquals(List.of(), sections.problems());
    }

    @Test
    void aBlockWithNoTagOnlyEndsTheSectionBeforeIt() {
        TagSections sections = TagSections.of(new RailNetwork(line(), List.of(), List.of(), List.of(marker(0, 20, 1), marker(0, 40, 0))));
        assertEquals(List.of(new Span(20, 40, 1, 0)), sections.spans(0));
        assertEquals(List.of(), sections.spans(2));
    }

    @Test
    void aSectionWithNoBlockAfterItRunsToTheEndOfTheTrack() {
        TagSections sections = TagSections.of(new RailNetwork(line(), List.of(), List.of(), List.of(marker(0, 20, 1))));
        assertEquals(List.of(new Span(20, 50, 1, 0)), sections.spans(0));
        assertEquals(List.of(new Span(0, 50, 1, 0)), sections.spans(2));
    }

    @Test
    void aBlockFacingTheOtherWayDoesNotEndASectionAndMayOverlapItWithTheSameTag() {
        // A block facing west on edge 3, 10 in (40 along the line from the west end), with the same tag.
        TagSections same = TagSections.of(new RailNetwork(line(), List.of(), List.of(), List.of(marker(0, 20, 1), marker(3, 10, 1))));
        assertEquals(List.of(new Span(0, 50, 1, 0), new Span(0, 40, 1, 1)), same.spans(2),
            "the westward block does not end the eastward section, and its own runs west to the end of the line");
        assertEquals(List.of(), same.problems());
        TagSections other = TagSections.of(new RailNetwork(line(), List.of(), List.of(), List.of(marker(0, 20, 1), marker(3, 10, 2))));
        assertEquals(List.of(new Problem(Kind.OVERLAP, 0, 1, null)), other.problems());
        assertEquals(other.problems(), other.problemsOf(1));
    }

    @Test
    void aSectionThatComesToAJunctionIsAProblemAndEndsThere() {
        // Edge 0 ends at node 1, where the track goes on two ways: edges 1 and 2.
        List<Edge> edges = List.of(edge(50, 0, 1, 1, 2), edge(50, 1, 2), edge(50, 1, 3));
        List<Place> places = List.of(new Place(0, 0, 0), new Place(50, 0, 0), new Place(100, 0, 0), new Place(100, 0, 10));
        TagSections sections = TagSections.of(new RailNetwork(edges, List.of(), places, List.of(marker(0, 20, 1), marker(1, 10, 2))));
        assertEquals(List.of(new Span(20, 50, 1, 0)), sections.spans(0));
        assertEquals(List.of(new Problem(Kind.JUNCTION, 0, -1, new Place(50, 0, 0))), sections.problems());
        // A block just past the junction starts a section of its own, which has no junction.
        assertEquals(List.of(new Span(10, 50, 2, 1)), sections.spans(1));
        assertEquals(List.of(), sections.problemsOf(1));
    }

    @Test
    void aSectionMayRunThroughAJunctionWhereTwoWaysMeet() {
        // Edge 0 and edge 1 both run on to edge 2: going along either, the track goes on one way.
        List<Edge> edges = List.of(edge(50, 0, 2, 2), edge(50, 1, 2, 2), edge(50, 2, 3));
        TagSections sections = TagSections.of(new RailNetwork(edges, List.of(), List.of(), List.of(marker(0, 20, 1))));
        assertEquals(List.of(new Span(0, 50, 1, 0)), sections.spans(2));
        assertEquals(List.of(), sections.problems());
    }

    @Test
    void aSectionRoundALoopWithNoOtherBlockEndsWhereItStarted() {
        // A loop of three nodes: edges 0 (node 0 to 1), 1 (1 to 2) and 2 (2 to 0) one way, 3, 4 and 5 back.
        List<Edge> edges = List.of(edge(50, 0, 1, 1), edge(50, 1, 2, 2), edge(50, 2, 0, 0),
            edge(50, 1, 0, 5), edge(50, 2, 1, 3), edge(50, 0, 2, 4));
        TagSections sections = TagSections.of(new RailNetwork(edges, List.of(), List.of(), List.of(marker(0, 20, 1))));
        assertEquals(List.of(new Span(0, 20, 1, 0), new Span(20, 50, 1, 0)), sections.spans(0));
        assertEquals(List.of(new Span(0, 50, 1, 0)), sections.spans(2));
        assertEquals(List.of(new Span(0, 30, 1, 0), new Span(30, 50, 1, 0)), sections.spans(3));
        assertEquals(List.of(), sections.problems());
    }
}
