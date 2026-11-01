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
 * The stretches of track that track tag blocks give tags ({@link RailNetwork#markers()}).
 *
 * <p>A block points at a place on the track and faces one way along it. Its section runs from there
 * the way it faces, to the next block of its kind that faces the same way or to the end of the track.
 * There are two kinds of block: a block for both ways gives its tags to trains running either way
 * along its section, and a one-way block only to trains running the way it faces. A block may have
 * more than one tag, and where sections overlap the track has the tags of them all. A block with no
 * tag gives no section; it only ends the one of the block before it. A block facing the other way, or
 * of the other kind, does not end a section.
 *
 * <p>Two things are wrong with a section, and are given as {@link Problem}s: it comes to a junction
 * (a node where the track goes on more than one way) before its end, where it could not be told which
 * way it goes on; or the two kinds of block are mixed, a section overlapping one of the other kind or
 * having a block of the other kind in it. A section is kept as far as it could be followed, so that a
 * problem with one block leaves the rest of the network as it is.
 */
public final class TagSections {

    private static final double EPSILON = 1e-9;

    /**
     * A stretch of an edge with tags.
     *
     * @param from   where it starts, from the start of the edge
     * @param to     where it ends
     * @param tags   the ids of the tags of its block
     * @param oneWay whether its block is a one-way one, whose tags hold only for trains running the way
     *               of this edge
     * @param marker the index of the block whose section it is part of
     */
    public record Span(double from, double to, List<Integer> tags, boolean oneWay, int marker) {

        public Span {
            tags = List.copyOf(tags);
        }
    }

    public enum Kind {
        /** The section comes to a junction before the next block of its kind that faces the same way. */
        JUNCTION,
        /** The section overlaps one from a block of the other kind, or has a block of the other kind in it. */
        MIXED
    }

    /**
     * @param kind   what is wrong
     * @param marker the index of the block whose section it is; for blocks of two kinds mixed, the lower
     *               of the two
     * @param other  for blocks of two kinds mixed, the index of the other block; otherwise -1
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

    /**
     * The tagged stretches of {@code edge}, in the order they start along it: those of both-ways blocks
     * and those of one-way blocks facing the way the edge runs, which are the tags a train running along
     * it meets.
     */
    public List<Span> spans(int edge) {
        return spans.get(edge);
    }

    public List<Problem> problems() {
        return problems;
    }

    /** The blocks whose sections cover the place {@code at} on {@code edge}, from their start up to but not at their end. */
    public Set<Integer> markersAt(int edge, double at) {
        Set<Integer> found = new LinkedHashSet<>();
        for (Span span : spans.get(edge))
            if (within(span, at))
                found.add(span.marker());
        return found;
    }

    /**
     * Whether a train held to {@code tag} may be at the place {@code at} on {@code edge}: where no
     * section covers it, or one that covers it has the tag.
     */
    public boolean allows(int edge, double at, int tag) {
        boolean covered = false;
        for (Span span : spans.get(edge))
            if (within(span, at)) {
                if (span.tags().contains(tag))
                    return true;
                covered = true;
            }
        return !covered;
    }

    /** Whether a section with {@code tag} lies on {@code edge}, anywhere along it. */
    public boolean hasTag(int edge, int tag) {
        for (Span span : spans.get(edge))
            if (span.tags().contains(tag))
                return true;
        return false;
    }

    /**
     * Where on {@code edge} a train held to {@code tag} may not run on from: the start of each stretch
     * that sections cover, none of them with the tag, in order. The sections of the blocks
     * {@code allowed} count as having it, as those of the place the train starts from do.
     */
    public List<Double> closedTo(int edge, int tag, Set<Integer> allowed) {
        List<Span> onEdge = spans.get(edge);
        if (onEdge.isEmpty())
            return List.of();
        List<Double> cuts = new ArrayList<>();
        for (Span span : onEdge) {
            cuts.add(span.from());
            cuts.add(span.to());
        }
        cuts.sort(null);
        List<Double> starts = new ArrayList<>();
        boolean closed = false;
        for (int i = 0; i + 1 < cuts.size(); i++) {
            double from = cuts.get(i);
            double to = cuts.get(i + 1);
            if (to - from <= EPSILON)
                continue;
            double middle = (from + to) / 2;
            boolean covered = false;
            boolean open = false;
            for (Span span : onEdge)
                if (span.from() <= middle && middle < span.to()) {
                    covered = true;
                    open |= span.tags().contains(tag) || allowed.contains(span.marker());
                }
            boolean now = covered && !open;
            if (now && !closed)
                starts.add(from);
            closed = now;
        }
        return starts;
    }

    /** The problems with the section of block {@code marker}, or with another that it is mixed with. */
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
            if (marker.tags().isEmpty())
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
                    if (markers.get(other).oneWay() != marker.oneWay())
                        continue;
                    double at = markers.get(other).offset();
                    if (first ? at > from + EPSILON : at >= from) {
                        to = at;
                        ended = true;
                        break;
                    }
                }
                if (to - from > EPSILON) {
                    spans.get(edgeIndex).add(new Span(from, to, marker.tags(), marker.oneWay(), m));
                    if (!marker.oneWay() && reverse[edgeIndex] >= 0)
                        spans.get(reverse[edgeIndex]).add(new Span(edge.length() - to, edge.length() - from, marker.tags(), false, m));
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

        // Both kinds of block mixed: sections of the two kinds that overlap (a both-ways section is on
        // the edges both ways, so it meets a one-way one on the one-way one's edge), and a block of one
        // kind in a section of the other, with tags or without.
        Set<List<Integer>> mixed = new LinkedHashSet<>();
        for (List<Span> onEdge : spans) {
            onEdge.sort(Comparator.comparingDouble(Span::from).thenComparingInt(Span::marker));
            for (int i = 0; i < onEdge.size(); i++)
                for (int j = i + 1; j < onEdge.size(); j++) {
                    Span a = onEdge.get(i);
                    Span b = onEdge.get(j);
                    if (a.oneWay() != b.oneWay() && Math.min(a.to(), b.to()) - Math.max(a.from(), b.from()) > EPSILON)
                        mixed.add(pair(a.marker(), b.marker()));
                }
        }
        for (int m = 0; m < markers.size(); m++) {
            Marker marker = markers.get(m);
            int edge = marker.edge();
            for (Span span : spans.get(edge))
                if (span.oneWay() != marker.oneWay() && within(span, marker.offset()))
                    mixed.add(pair(m, span.marker()));
            if (reverse[edge] >= 0)
                for (Span span : spans.get(reverse[edge]))
                    if (span.oneWay() != marker.oneWay() && within(span, edges.get(edge).length() - marker.offset()))
                        mixed.add(pair(m, span.marker()));
        }
        for (List<Integer> pair : mixed)
            problems.add(new Problem(Kind.MIXED, pair.get(0), pair.get(1), null));

        List<List<Span>> kept = new ArrayList<>();
        for (List<Span> onEdge : spans)
            kept.add(List.copyOf(onEdge));
        return new TagSections(List.copyOf(kept), problems);
    }

    private static List<Integer> pair(int a, int b) {
        return List.of(Math.min(a, b), Math.max(a, b));
    }

    /** Whether {@code at} is in {@code span}, from its start up to but not at its end. */
    private static boolean within(Span span, double at) {
        return at > span.from() - EPSILON && at < span.to() - EPSILON;
    }
}
