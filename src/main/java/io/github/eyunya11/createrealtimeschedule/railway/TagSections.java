package io.github.eyunya11.createrealtimeschedule.railway;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Edge;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Marker;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Place;

/**
 * The stretches of track that track tag blocks give a tag ({@link RailNetwork#markers()}).
 *
 * <p>A block points at a place on the track and faces one way along it. Its section runs from there
 * the way it faces, to the next block that faces the same way or to the end of the track, and the tag
 * holds for trains running either way along it. A block with no tag gives no section; it only ends the
 * one of the block before it. A block facing the other way does not end a section.
 *
 * <p>Two things are wrong with a section, and are given as {@link Problem}s: it comes to a junction
 * (a node where the track goes on more than one way) before its end, where it could not be told which
 * way it goes on; or it overlaps the section of a block facing the other way that has another tag.
 * A section is kept as far as it could be followed, so that a problem with one block leaves the rest
 * of the network as it is.
 */
public final class TagSections {

    private static final double EPSILON = 1e-9;

    /**
     * A stretch of an edge with a tag.
     *
     * @param from   where it starts, from the start of the edge
     * @param to     where it ends
     * @param tag    the tag's id
     * @param marker the index of the block whose section it is part of
     */
    public record Span(double from, double to, int tag, int marker) {
    }

    public enum Kind {
        /** The section comes to a junction before the next block that faces the same way. */
        JUNCTION,
        /** The section overlaps one of another tag from a block facing the other way. */
        OVERLAP
    }

    /**
     * @param kind   what is wrong
     * @param marker the index of the block whose section it is
     * @param other  for an overlap, the index of the other block; otherwise -1
     * @param node   for a junction, where its node is, if that is known
     */
    public record Problem(Kind kind, int marker, int other, @Nullable Place node) {
    }

    /** For every edge, its tagged stretches by where they start. */
    private final List<List<Span>> spans;
    private final List<Problem> problems;

    private TagSections(List<List<Span>> spans, List<Problem> problems) {
        this.spans = spans;
        this.problems = List.copyOf(problems);
    }

    /** The tagged stretches of {@code edge}, in the order they start along it. */
    public List<Span> spans(int edge) {
        return spans.get(edge);
    }

    public List<Problem> problems() {
        return problems;
    }

    /** The problems with the section of block {@code marker}, or with another that overlaps it. */
    public List<Problem> problemsOf(int marker) {
        return problems.stream().filter(problem -> problem.marker() == marker || problem.other() == marker).toList();
    }

    /** The sections of the blocks on {@code network}. */
    public static TagSections of(RailNetwork network) {
        List<Edge> edges = network.edges();
        List<Marker> markers = network.markers();

        // The edge the other way between the same two nodes, where the nodes are known.
        Map<List<Integer>, Integer> byNodes = new HashMap<>();
        for (int i = 0; i < edges.size(); i++)
            if (edges.get(i).start() >= 0 && edges.get(i).end() >= 0)
                byNodes.put(List.of(edges.get(i).start(), edges.get(i).end()), i);
        int[] reverse = new int[edges.size()];
        for (int i = 0; i < edges.size(); i++) {
            Edge edge = edges.get(i);
            Integer back = edge.start() >= 0 && edge.end() >= 0 ? byNodes.get(List.of(edge.end(), edge.start())) : null;
            reverse[i] = back == null ? -1 : back;
        }

        List<List<Integer>> markersOn = new ArrayList<>();
        List<List<Span>> spans = new ArrayList<>();
        for (int i = 0; i < edges.size(); i++) {
            markersOn.add(new ArrayList<>());
            spans.add(new ArrayList<>());
        }
        for (int i = 0; i < markers.size(); i++)
            markersOn.get(markers.get(i).edge()).add(i);
        for (List<Integer> on : markersOn)
            on.sort(Comparator.comparingDouble(i -> markers.get(i).offset()));

        List<Problem> problems = new ArrayList<>();
        for (int m = 0; m < markers.size(); m++) {
            Marker marker = markers.get(m);
            if (marker.tag() == 0)
                continue;
            int edgeIndex = marker.edge();
            double from = marker.offset();
            // Every edge is passed once at most, unless it is the block's own, which the walk comes
            // back to round a loop and ends at; so this many steps is past any walk that is not stuck.
            for (int step = 0; step <= edges.size(); step++) {
                Edge edge = edges.get(edgeIndex);
                boolean first = step == 0;
                double to = edge.length();
                boolean ended = false;
                for (int other : markersOn.get(edgeIndex)) {
                    double at = markers.get(other).offset();
                    if (first ? at > from + EPSILON : at >= from) {
                        to = at;
                        ended = true;
                        break;
                    }
                }
                if (to - from > EPSILON) {
                    spans.get(edgeIndex).add(new Span(from, to, marker.tag(), m));
                    if (reverse[edgeIndex] >= 0)
                        spans.get(reverse[edgeIndex]).add(new Span(edge.length() - to, edge.length() - from, marker.tag(), m));
                }
                if (ended || edge.next().isEmpty())
                    break;
                if (edge.next().size() > 1) {
                    problems.add(new Problem(Kind.JUNCTION, m, -1, network.place(edge.end())));
                    break;
                }
                edgeIndex = edge.next().get(0);
                from = 0;
            }
        }

        Set<List<Integer>> overlapping = new LinkedHashSet<>();
        for (List<Span> onEdge : spans) {
            onEdge.sort(Comparator.comparingDouble(Span::from).thenComparingInt(Span::marker));
            for (int i = 0; i < onEdge.size(); i++)
                for (int j = i + 1; j < onEdge.size(); j++) {
                    Span a = onEdge.get(i);
                    Span b = onEdge.get(j);
                    if (a.tag() != b.tag() && Math.min(a.to(), b.to()) - Math.max(a.from(), b.from()) > EPSILON)
                        overlapping.add(List.of(Math.min(a.marker(), b.marker()), Math.max(a.marker(), b.marker())));
                }
        }
        for (List<Integer> pair : overlapping)
            problems.add(new Problem(Kind.OVERLAP, pair.get(0), pair.get(1), null));

        List<List<Span>> kept = new ArrayList<>();
        for (List<Span> onEdge : spans)
            kept.add(List.copyOf(onEdge));
        return new TagSections(List.copyOf(kept), problems);
    }
}
