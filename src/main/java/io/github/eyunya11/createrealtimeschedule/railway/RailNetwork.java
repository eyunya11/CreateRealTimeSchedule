package io.github.eyunya11.createrealtimeschedule.railway;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import io.github.eyunya11.createrealtimeschedule.travel.SpeedSign;
import io.github.eyunya11.createrealtimeschedule.travel.SpeedSigns;

/**
 * The part of a track network that matters for running times, with no Minecraft or Create types in
 * it so that the search can be tested on small networks written out by hand. {@link CreateNetwork}
 * makes one from the game's track graph.
 *
 * <p>The network is a set of <em>directed edges</em>: a stretch of track as a train runs along it in
 * one direction, with a length, whether it is a curve, and the edges a train can go on to at its far
 * end. A track that runs both ways is two edges. A <em>station</em> is a stop marker on one edge, a
 * distance from its start, and a train can stop there only when it runs along that edge. The route
 * from one station to another is the shortest way a train can run from the one marker to the other.
 */
public final class RailNetwork {

    /**
     * @param length   the length in blocks
     * @param curved   whether Create holds a train to its turn speed here
     * @param next     the indexes of the edges a train can go on to from the end of this one
     * @param signs    the speed-limit signs of Create Train Next that face the way this edge runs, each
     *                 at its distance from the start of the edge (kept sorted by it)
     * @param barriers where on the edge, from its start, there is a point that lets no train through
     *                 the way the edge runs, as a signal for trains running the other way is (kept
     *                 sorted). Create's search reaches the stations on the edge before the first such
     *                 point, and nothing after it, on the edge or beyond it.
     * @param onward   the edge a train runs on to from the end of this one when nothing steers it, or
     *                 -1 where the track ends. A train looking ahead past the end of its path, for
     *                 signs, goes this way: Create takes the first of the ways the track goes on, in the
     *                 order of its graph, whatever the signals say.
     */
    public record Edge(double length, boolean curved, List<Integer> next, List<SpeedSign> signs, List<Double> barriers,
        int onward) {
        public Edge {
            next = List.copyOf(next);
            signs = signs.stream().sorted(Comparator.comparingDouble(SpeedSign::position)).toList();
            barriers = barriers.stream().sorted().toList();
        }

        /**
         * An edge with no signs on it and nothing to stop a train, that goes on to the first of
         * {@code next} when nothing steers the train. The {@code with} methods give it the rest.
         */
        public Edge(double length, boolean curved, List<Integer> next) {
            this(length, curved, next, List.of(), List.of(), next.isEmpty() ? -1 : next.get(0));
        }

        public Edge withSigns(List<SpeedSign> signs) {
            return new Edge(length, curved, next, signs, barriers, onward);
        }

        public Edge withBarriers(List<Double> barriers) {
            return new Edge(length, curved, next, signs, barriers, onward);
        }

        public Edge withOnward(int onward) {
            return new Edge(length, curved, next, signs, barriers, onward);
        }
    }

    /**
     * @param name   what a schedule calls it; stations may share a name (platforms of one stop)
     * @param edge   the index of the edge it is on
     * @param offset the distance of its marker from the start of that edge
     */
    public record Station(String name, int edge, double offset) {}

    private static final double EPSILON = 1e-9;

    /**
     * How far past the end of a leg its signs are looked for. A train brakes for the signs ahead of it
     * on the way into a stop too (see {@link SpeedSigns}), as far as it could need to brake from its
     * top speed and 30 blocks more: under 200 blocks for Create's default config, and under 400 with
     * fuel.
     */
    private static final double SIGNS_PAST_THE_END = 512;
    /** A bound on the edges followed past the end, for a loop of edges with no length. */
    private static final int MAX_EDGES_PAST_THE_END = 64;

    private final List<Edge> edges;
    private final List<Station> stations;
    /** For every edge, the indexes of its stations by offset. */
    private final List<List<Integer>> stationsOn = new ArrayList<>();

    /**
     * @throws IllegalArgumentException if an index is out of range, a length is negative, or a station
     *                                  or a sign is not on its edge
     */
    public RailNetwork(List<Edge> edges, List<Station> stations) {
        this.edges = List.copyOf(edges);
        this.stations = List.copyOf(stations);
        for (int i = 0; i < this.edges.size(); i++) {
            Edge edge = this.edges.get(i);
            if (!(edge.length() >= 0))
                throw new IllegalArgumentException("edge " + i + " has the length " + edge.length());
            for (int next : edge.next())
                if (next < 0 || next >= this.edges.size())
                    throw new IllegalArgumentException("edge " + i + " goes on to edge " + next + ", which is not there");
            if (edge.onward() < -1 || edge.onward() >= this.edges.size())
                throw new IllegalArgumentException("edge " + i + " runs on to edge " + edge.onward() + ", which is not there");
            for (SpeedSign sign : edge.signs())
                if (!(sign.position() >= 0 && sign.position() <= edge.length()))
                    throw new IllegalArgumentException("edge " + i + " has a sign at " + sign.position() + ", outside it");
            for (double barrier : edge.barriers())
                if (!(barrier >= 0 && barrier <= edge.length()))
                    throw new IllegalArgumentException("edge " + i + " has a barrier at " + barrier + ", outside it");
            stationsOn.add(new ArrayList<>());
        }
        for (int i = 0; i < this.stations.size(); i++) {
            Station station = this.stations.get(i);
            if (station.edge() < 0 || station.edge() >= this.edges.size())
                throw new IllegalArgumentException("station " + i + " is on edge " + station.edge() + ", which is not there");
            if (!(station.offset() >= 0 && station.offset() <= this.edges.get(station.edge()).length()))
                throw new IllegalArgumentException("station " + i + " is at " + station.offset() + ", outside its edge");
            stationsOn.get(station.edge()).add(i);
        }
        for (List<Integer> onEdge : stationsOn)
            onEdge.sort(Comparator.comparingDouble((Integer i) -> this.stations.get(i).offset()).thenComparingInt(i -> i));
    }

    /** The names of the stations, each once, in the order they first come in the list of stations. */
    public List<String> names() {
        Set<String> names = new LinkedHashSet<>();
        for (Station station : stations)
            names.add(station.name());
        return List.copyOf(names);
    }

    /**
     * Every way from a station to another with a different name that a train can run, shortest first
     * where stations share a name: one leg for each ordered pair of names that are connected, in the
     * order of {@link #names()}.
     */
    public List<Leg> legs() {
        Map<List<String>, Leg> best = new LinkedHashMap<>();
        for (int source = 0; source < stations.size(); source++)
            searchFrom(source, best);
        List<String> names = names();
        List<Leg> legs = new ArrayList<>(best.values());
        legs.sort(Comparator.<Leg>comparingInt(leg -> names.indexOf(leg.from())).thenComparingInt(leg -> names.indexOf(leg.to())));
        return legs;
    }

    /** One way of running along the edges so far: where it is, and the way it came. */
    private record Path(int edge, double endDistance, @Nullable Path parent) implements Comparable<Path> {
        @Override
        public int compareTo(Path other) {
            return Double.compare(endDistance, other.endDistance);
        }
    }

    private void searchFrom(int source, Map<List<String>, Leg> best) {
        Station start = stations.get(source);
        Edge first = edges.get(start.edge());
        // The root stands for the rest of the first edge; its end is first.length - start.offset away.
        Path root = new Path(start.edge(), first.length() - start.offset(), null);
        boolean[] done = new boolean[edges.size()];
        // Create's search counts the edge a train starts on as visited from the start. Coming round to
        // it again, it looks only at what lies ahead of where the train stood, which it has looked at
        // already, so a station behind the train on its own edge is not reached by going round.
        done[start.edge()] = true;
        PriorityQueue<Path> frontier = new PriorityQueue<>();

        if (!considerStationsOn(root, source, best))
            return;
        for (int next : first.next())
            if (!done[next])
                frontier.add(new Path(next, root.endDistance() + edges.get(next).length(), root));

        while (!frontier.isEmpty()) {
            Path path = frontier.poll();
            if (done[path.edge()])
                continue;
            done[path.edge()] = true;
            if (!considerStationsOn(path, source, best))
                continue;
            for (int next : edges.get(path.edge()).next())
                if (!done[next])
                    frontier.add(new Path(next, path.endDistance() + edges.get(next).length(), path));
        }
    }

    /**
     * Every station on the last edge of the path that a train running the path stops at, as a
     * candidate, and whether the way goes on past the edge.
     *
     * <p>Create's search goes along the points of an edge in order, from where the train comes onto it
     * (or, on the first edge, from where it stands), and gives up at the first one that does not let
     * the train through: the stations before that barrier can be reached, and nothing after it on the
     * edge or beyond it (Navigation.search). This holds on every edge, not only the first.
     *
     * @return false if a barrier on the edge ends the way there
     */
    private boolean considerStationsOn(Path path, int source, Map<List<String>, Leg> best) {
        Station start = stations.get(source);
        boolean onFirstEdge = path.parent() == null;
        double from = onFirstEdge ? start.offset() : 0;
        double blockedAt = Double.POSITIVE_INFINITY;
        for (double barrier : edges.get(path.edge()).barriers())
            if (barrier >= from) {
                blockedAt = barrier;
                break;
            }
        for (int target : stationsOn.get(path.edge())) {
            Station station = stations.get(target);
            // On the first edge only what lies ahead of the marker counts.
            boolean beforeStart = onFirstEdge && station.offset() <= start.offset() + EPSILON;
            if (target == source || beforeStart || station.offset() >= blockedAt || station.name().equals(start.name()))
                continue;
            double distance = path.endDistance() - edges.get(path.edge()).length() + station.offset();
            List<String> key = List.of(start.name(), station.name());
            Leg known = best.get(key);
            if (known != null && known.distance() <= distance)
                continue;
            best.put(key, leg(source, target, path, distance));
        }
        return blockedAt == Double.POSITIVE_INFINITY;
    }

    private Leg leg(int source, int target, Path last, double distance) {
        Station start = stations.get(source);
        Station end = stations.get(target);

        List<Path> chain = new ArrayList<>();
        for (Path p = last; p != null; p = p.parent())
            chain.add(0, p);

        List<Leg.Curve> curves = new ArrayList<>();
        Set<String> via = new LinkedHashSet<>();
        List<SpeedSign> signs = new ArrayList<>();
        double position = 0;
        for (Path step : chain) {
            Edge edge = edges.get(step.edge());
            boolean first = step.parent() == null;
            boolean lastStep = step == last;
            double from = first ? start.offset() : 0;
            double to = lastStep ? end.offset() : edge.length();
            // A sign at the start marker is behind the train; one at the end marker is passed on arrival.
            for (SpeedSign sign : edge.signs())
                if ((first ? sign.position() > from : sign.position() >= from) && sign.position() <= to)
                    signs.add(moved(sign, position - from));
            if (edge.curved() && to - from > EPSILON) {
                double a = position;
                double b = position + (to - from);
                Leg.Curve previous = curves.isEmpty() ? null : curves.get(curves.size() - 1);
                if (previous != null && a - previous.end() < EPSILON)
                    curves.set(curves.size() - 1, new Leg.Curve(previous.start(), b));
                else
                    curves.add(new Leg.Curve(a, b));
            }
            position += to - from;

            for (int passed : stationsOn.get(step.edge())) {
                Station station = stations.get(passed);
                boolean after = !first || station.offset() > start.offset() + EPSILON;
                boolean before = !lastStep || station.offset() < end.offset() - EPSILON;
                // A station with the name of either end cannot be on the way here: it would have a shorter leg of its own.
                if (after && before)
                    via.add(station.name());
            }
        }
        signs.addAll(signsPastTheEnd(last.edge(), end.offset(), distance));
        return new Leg(start.name(), end.name(), distance, curves, new ArrayList<>(via), signs);
    }

    /**
     * The signs past the end marker that a train coming in to it brakes for: on the rest of the edge,
     * and on the edges after it, as far as it looks. Past the end of its path nothing steers the look
     * ahead, so it goes on along {@link Edge#onward}, branches and all.
     */
    private List<SpeedSign> signsPastTheEnd(int edgeIndex, double offset, double distance) {
        List<SpeedSign> found = new ArrayList<>();
        Edge edge = edges.get(edgeIndex);
        for (SpeedSign sign : edge.signs())
            if (sign.position() > offset && sign.position() - offset <= SIGNS_PAST_THE_END)
                found.add(moved(sign, distance - offset));
        double edgeStart = distance - offset;
        for (int followed = 0; followed < MAX_EDGES_PAST_THE_END; followed++) {
            edgeStart += edge.length();
            if (edgeStart - distance >= SIGNS_PAST_THE_END || edge.onward() < 0)
                break;
            edge = edges.get(edge.onward());
            for (SpeedSign sign : edge.signs())
                if (edgeStart + sign.position() - distance <= SIGNS_PAST_THE_END)
                    found.add(moved(sign, edgeStart));
        }
        return found;
    }

    /** The sign moved along by {@code by} blocks: from where it is on its edge to where it is on a leg. */
    private static SpeedSign moved(SpeedSign sign, double by) {
        return new SpeedSign(sign.position() + by, sign.kmh(), sign.release());
    }
}
