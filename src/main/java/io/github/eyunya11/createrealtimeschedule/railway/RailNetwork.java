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
     * @param length the length in blocks
     * @param curved whether Create holds a train to its turn speed here
     * @param next   the indexes of the edges a train can go on to from the end of this one
     */
    public record Edge(double length, boolean curved, List<Integer> next) {
        public Edge {
            next = List.copyOf(next);
        }
    }

    /**
     * @param name   what a schedule calls it; stations may share a name (platforms of one stop)
     * @param edge   the index of the edge it is on
     * @param offset the distance of its marker from the start of that edge
     */
    public record Station(String name, int edge, double offset) {}

    private static final double EPSILON = 1e-9;

    private final List<Edge> edges;
    private final List<Station> stations;
    /** For every edge, the indexes of its stations by offset. */
    private final List<List<Integer>> stationsOn = new ArrayList<>();

    /**
     * @throws IllegalArgumentException if an index is out of range, a length is negative, or a station
     *                                  is not on its edge
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
        PriorityQueue<Path> frontier = new PriorityQueue<>();

        considerStationsOn(root, source, best);
        for (int next : first.next())
            frontier.add(new Path(next, root.endDistance() + edges.get(next).length(), root));

        while (!frontier.isEmpty()) {
            Path path = frontier.poll();
            if (done[path.edge()])
                continue;
            done[path.edge()] = true;
            considerStationsOn(path, source, best);
            for (int next : edges.get(path.edge()).next())
                if (!done[next])
                    frontier.add(new Path(next, path.endDistance() + edges.get(next).length(), path));
        }
    }

    /** Every station on the last edge of the path that a train running the path stops at, as a candidate. */
    private void considerStationsOn(Path path, int source, Map<List<String>, Leg> best) {
        Station start = stations.get(source);
        for (int target : stationsOn.get(path.edge())) {
            Station station = stations.get(target);
            boolean onFirstEdge = path.parent() == null;
            boolean beforeStart = station.edge() == start.edge() && station.offset() <= start.offset() + EPSILON;
            // On the first edge only what lies ahead of the marker counts; coming round to the first
            // edge again, only what lies behind it (what lies ahead was reached sooner).
            if (onFirstEdge ? target == source || beforeStart : (station.edge() == start.edge() && !beforeStart))
                continue;
            if (station.name().equals(start.name()))
                continue;
            double distance = path.endDistance() - edges.get(path.edge()).length() + station.offset();
            List<String> key = List.of(start.name(), station.name());
            Leg known = best.get(key);
            if (known != null && known.distance() <= distance)
                continue;
            best.put(key, leg(source, target, path, distance));
        }
    }

    private Leg leg(int source, int target, Path last, double distance) {
        Station start = stations.get(source);
        Station end = stations.get(target);

        List<Path> chain = new ArrayList<>();
        for (Path p = last; p != null; p = p.parent())
            chain.add(0, p);

        List<Leg.Curve> curves = new ArrayList<>();
        Set<String> via = new LinkedHashSet<>();
        double position = 0;
        for (Path step : chain) {
            Edge edge = edges.get(step.edge());
            boolean first = step.parent() == null;
            boolean lastStep = step == last;
            double from = first ? start.offset() : 0;
            double to = lastStep ? end.offset() : edge.length();
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
        return new Leg(start.name(), end.name(), distance, curves, new ArrayList<>(via));
    }
}
