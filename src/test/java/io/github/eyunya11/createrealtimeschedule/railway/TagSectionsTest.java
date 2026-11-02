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

    /** A block for both ways with the tags {@code tags}, none for one that only ends a section. */
    private static Marker marker(int edge, double offset, Integer... tags) {
        return new Marker(edge, offset, List.of(tags), false, BLOCK);
    }

    private static Marker oneWay(int edge, double offset, Integer... tags) {
        return new Marker(edge, offset, List.of(tags), true, BLOCK);
    }

    /** A stretch of a both-ways block's section. */
    private static Span span(double from, double to, int marker, Integer... tags) {
        return new Span(from, to, List.of(tags), false, marker);
    }

    private static Span oneWaySpan(double from, double to, int marker, Integer... tags) {
        return new Span(from, to, List.of(tags), true, marker);
    }

    /** A line of three nodes 0, 1, 2, 50 blocks apart: edges 0 (0 to 1) and 2 (1 to 2) east, 3 (2 to 1) and 1 (1 to 0) west. */
    private static List<Edge> line() {
        return List.of(edge(50, 0, 1, 2), edge(50, 1, 0), edge(50, 1, 2), edge(50, 2, 1, 1));
    }

    @Test
    void aSectionRunsToTheNextBlockFacingTheSameWayAndHoldsBothWays() {
        // A block on edge 0, 20 in, with tag 1; the next facing east is on edge 2, 30 in, with tag 2.
        TagSections sections = TagSections.of(new RailNetwork(line(), List.of(), List.of(), List.of(marker(0, 20, 1), marker(2, 30, 2))));
        assertEquals(List.of(span(20, 50, 0, 1)), sections.spans(0));
        assertEquals(List.of(span(0, 30, 0, 1), span(30, 50, 1, 2)), sections.spans(2));
        // The same stretches on the edges the other way.
        assertEquals(List.of(span(0, 20, 1, 2), span(20, 50, 0, 1)), sections.spans(3));
        assertEquals(List.of(span(0, 30, 0, 1)), sections.spans(1));
        assertEquals(List.of(), sections.problems());
    }

    @Test
    void aBlockWithNoTagOnlyEndsTheSectionBeforeIt() {
        TagSections sections = TagSections.of(new RailNetwork(line(), List.of(), List.of(), List.of(marker(0, 20, 1), marker(0, 40))));
        assertEquals(List.of(span(20, 40, 0, 1)), sections.spans(0));
        assertEquals(List.of(), sections.spans(2));
    }

    @Test
    void aSectionWithNoBlockAfterItRunsToTheEndOfTheTrack() {
        TagSections sections = TagSections.of(new RailNetwork(line(), List.of(), List.of(), List.of(marker(0, 20, 1))));
        assertEquals(List.of(span(20, 50, 0, 1)), sections.spans(0));
        assertEquals(List.of(span(0, 50, 0, 1)), sections.spans(2));
    }

    @Test
    void aBlockFacingTheOtherWayDoesNotEndASectionAndWhereTheyOverlapTheTrackHasTheTagsOfBoth() {
        // A block facing west on edge 3, 10 in (40 along the line from the west end), with another tag.
        TagSections sections = TagSections.of(new RailNetwork(line(), List.of(), List.of(), List.of(marker(0, 20, 1), marker(3, 10, 2))));
        assertEquals(List.of(span(0, 50, 0, 1), span(0, 40, 1, 2)), sections.spans(2),
            "the westward block does not end the eastward section, and its own runs west to the end of the line");
        assertEquals(List.of(span(0, 50, 1, 2), span(20, 50, 0, 1)), sections.spans(0));
        assertEquals(List.of(), sections.problems());
    }

    @Test
    void aBlockMayHaveMoreThanOneTag() {
        TagSections sections = TagSections.of(new RailNetwork(line(), List.of(), List.of(), List.of(marker(0, 20, 1, 2))));
        assertEquals(List.of(span(20, 50, 0, 1, 2)), sections.spans(0));
        assertEquals(List.of(span(0, 30, 0, 1, 2)), sections.spans(1));
    }

    @Test
    void aOneWaySectionIsOnlyOnTheEdgesTheWayItsBlockFaces() {
        // Up on the line eastward from 20, down westward from 40 along it: on one track, a tag for each way.
        TagSections sections = TagSections.of(new RailNetwork(line(), List.of(), List.of(), List.of(oneWay(0, 20, 1), oneWay(3, 10, 2))));
        assertEquals(List.of(oneWaySpan(20, 50, 0, 1)), sections.spans(0));
        assertEquals(List.of(oneWaySpan(0, 50, 0, 1)), sections.spans(2));
        assertEquals(List.of(oneWaySpan(10, 50, 1, 2)), sections.spans(3));
        assertEquals(List.of(oneWaySpan(0, 50, 1, 2)), sections.spans(1));
        assertEquals(List.of(), sections.problems());
    }

    @Test
    void aSectionEndsAtTheNextBlockOfItsKindAndNotAtOneOfTheOtherKind() {
        // One-way blocks facing east at 20 and 40 on edge 0; a both-ways one facing east at 30 between them.
        TagSections sections = TagSections.of(new RailNetwork(line(), List.of(), List.of(), List.of(oneWay(0, 20, 1), marker(0, 30, 2), oneWay(0, 40))));
        assertEquals(List.of(oneWaySpan(20, 40, 0, 1), span(30, 50, 1, 2)), sections.spans(0),
            "the one-way section runs past the both-ways block to the next one-way one, and the both-ways one past that");
        assertEquals(List.of(new Problem(Kind.MIXED, 0, 1, null), new Problem(Kind.MIXED, 1, 2, null)), sections.problems(),
            "the two sections overlap, and the one-way block with no tag stands in the both-ways section");
    }

    @Test
    void aBlockOfTheOtherKindInASectionMixesTheKindsEvenWithNoTag() {
        // A both-ways section east from 20 on edge 0, and a one-way block with no tag facing west at 10 on edge 3 (40 along).
        TagSections sections = TagSections.of(new RailNetwork(line(), List.of(), List.of(), List.of(marker(0, 20, 1), oneWay(3, 10))));
        assertEquals(List.of(new Problem(Kind.MIXED, 0, 1, null)), sections.problems());
        assertEquals(sections.problems(), sections.problemsOf(1));
    }

    @Test
    void aSectionThatComesToAJunctionIsAProblemAndEndsThere() {
        // Edge 0 ends at node 1, where the track goes on two ways: edges 1 and 2.
        List<Edge> edges = List.of(edge(50, 0, 1, 1, 2), edge(50, 1, 2), edge(50, 1, 3));
        List<Place> places = List.of(new Place(0, 0, 0), new Place(50, 0, 0), new Place(100, 0, 0), new Place(100, 0, 10));
        TagSections sections = TagSections.of(new RailNetwork(edges, List.of(), places, List.of(marker(0, 20, 1), marker(1, 10, 2))));
        assertEquals(List.of(span(20, 50, 0, 1)), sections.spans(0));
        assertEquals(List.of(new Problem(Kind.JUNCTION, 0, -1, new Place(50, 0, 0))), sections.problems());
        // A block just past the junction starts a section of its own, which has no junction.
        assertEquals(List.of(span(10, 50, 1, 2)), sections.spans(1));
        assertEquals(List.of(), sections.problemsOf(1));
    }

    @Test
    void aSectionMayRunThroughAJunctionWhereTwoWaysMeet() {
        // Edge 0 and edge 1 both run on to edge 2: going along either, the track goes on one way.
        List<Edge> edges = List.of(edge(50, 0, 2, 2), edge(50, 1, 2, 2), edge(50, 2, 3));
        TagSections sections = TagSections.of(new RailNetwork(edges, List.of(), List.of(), List.of(marker(0, 20, 1))));
        assertEquals(List.of(span(0, 50, 0, 1)), sections.spans(2));
        assertEquals(List.of(), sections.problems());
    }

    @Test
    void aSectionRoundALoopWithNoOtherBlockEndsWhereItStarted() {
        // A loop of three nodes: edges 0 (node 0 to 1), 1 (1 to 2) and 2 (2 to 0) one way, 3, 4 and 5 back.
        List<Edge> edges = List.of(edge(50, 0, 1, 1), edge(50, 1, 2, 2), edge(50, 2, 0, 0),
            edge(50, 1, 0, 5), edge(50, 2, 1, 3), edge(50, 0, 2, 4));
        TagSections sections = TagSections.of(new RailNetwork(edges, List.of(), List.of(), List.of(marker(0, 20, 1))));
        assertEquals(List.of(span(0, 20, 0, 1), span(20, 50, 0, 1)), sections.spans(0));
        assertEquals(List.of(span(0, 50, 0, 1)), sections.spans(2));
        assertEquals(List.of(span(0, 30, 0, 1), span(30, 50, 0, 1)), sections.spans(3));
        assertEquals(List.of(), sections.problems());
    }
}
