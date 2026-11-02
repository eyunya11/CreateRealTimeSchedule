package io.github.eyunya11.createrealtimeschedule.railway;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
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
 * from one station to another is the way a train sent there takes ({@link RouteSearch}): the way of
 * least length and weight, as Create's own search weighs it.
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
     * @param points   the points on the edge that weigh a way through it and that a train does not stop
     *                 at running this way: the station markers that face the other way, or have no name,
     *                 and the signals that let the train through (kept sorted by position)
     * @param start    a number for the node the edge starts at, the same for every edge that starts
     *                 there, or -1 if it is not known
     * @param end      a number for the node the edge ends at, or -1 if it is not known
     */
    public record Edge(double length, boolean curved, List<Integer> next, List<SpeedSign> signs, List<Double> barriers,
        int onward, List<Point> points, int start, int end) {
        public Edge {
            next = List.copyOf(next);
            signs = signs.stream().sorted(Comparator.comparingDouble(SpeedSign::position)).toList();
            barriers = barriers.stream().sorted().toList();
            points = points.stream().sorted(Comparator.comparingDouble(Point::position)).toList();
        }

        /** An edge with no points that weigh a way, of a node that is not known. */
        public Edge(double length, boolean curved, List<Integer> next, List<SpeedSign> signs, List<Double> barriers, int onward) {
            this(length, curved, next, signs, barriers, onward, List.of(), -1, -1);
        }

        /**
         * An edge with no signs on it and nothing to stop a train, that goes on to the first of
         * {@code next} when nothing steers the train. The {@code with} methods give it the rest.
         */
        public Edge(double length, boolean curved, List<Integer> next) {
            this(length, curved, next, List.of(), List.of(), next.isEmpty() ? -1 : next.get(0));
        }

        public Edge withSigns(List<SpeedSign> signs) {
            return new Edge(length, curved, next, signs, barriers, onward, points, start, end);
        }

        public Edge withBarriers(List<Double> barriers) {
            return new Edge(length, curved, next, signs, barriers, onward, points, start, end);
        }

        public Edge withOnward(int onward) {
            return new Edge(length, curved, next, signs, barriers, onward, points, start, end);
        }

        public Edge withPoints(List<Point> points) {
            return new Edge(length, curved, next, signs, barriers, onward, points, start, end);
        }

        /** The edge between the nodes numbered {@code start} and {@code end}. */
        public Edge withNodes(int start, int end) {
            return new Edge(length, curved, next, signs, barriers, onward, points, start, end);
        }
    }

    /** Where a node is in the world, which Create's search uses to estimate the way left to a station. */
    public record Place(double x, double y, double z) {
    }

    /**
     * A track tag block's point on the track (see {@link TagSections}).
     *
     * @param edge   the index of the edge it faces along: its section runs on the way this edge runs
     * @param offset its distance from the start of that edge
     * @param tags   the ids of its tags, none for a block that only ends a section
     * @param oneWay whether it is a one-way block, whose tags hold only for trains running the way it faces
     * @param block  where the block is, to tell the player which one is meant
     */
    public record Marker(int edge, double offset, List<Integer> tags, boolean oneWay, Place block) {

        public Marker {
            tags = List.copyOf(tags);
        }
    }

    /**
     * A point on an edge that weighs a way through it (see {@link RouteSearch}).
     *
     * @param position its distance from the start of the edge
     * @param kind     what it is
     * @param ref      for a station marker, the number of its platform ({@link Station#platform}); for a
     *                 signal, a number for the signal and the side of it a train running this way meets
     */
    public record Point(double position, Kind kind, int ref) {
        public enum Kind {
            /** A station marker a train passes, which Create weighs whether or not the train can stop at it. */
            STATION,
            /** A signal that lets the train through, which Create weighs when it is red. */
            SIGNAL
        }
    }

    /**
     * @param name     what a schedule calls it; stations may share a name (platforms of one stop)
     * @param edge     the index of the edge it is on
     * @param offset   the distance of its marker from the start of that edge
     * @param platform a number for the station marker, which {@link Point}s of it on the edge the other
     *                 way have too, or -1 if it has none
     */
    public record Station(String name, int edge, double offset, int platform) {

        /** A station with no number of its own. */
        public Station(String name, int edge, double offset) {
            this(name, edge, offset, -1);
        }
    }

    /**
     * Where a train standing at one station goes when it is sent to a name: to the first station of
     * that name its search comes to (see {@link #ways}).
     *
     * @param from    the index of the station it stands at
     * @param to      the name it is sent to
     * @param station the index of the station of that name it comes to
     */
    public record Way(int from, String to, int station) {
    }

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
    /** Where each node is, by its number; empty if not known. */
    private final List<Place> places;
    private final List<Marker> markers;
    /** For every edge, the indexes of its stations by offset. */
    private final List<List<Integer>> stationsOn = new ArrayList<>();
    /** For every edge, the points on it that the search looks at, in order. */
    private final List<List<RouteSearch.Item>> items = new ArrayList<>();
    /** The sections of the track tag blocks, worked out the first time they are wanted. */
    private @Nullable TagSections tagSections;

    /**
     * @throws IllegalArgumentException if an index is out of range, a length is negative, or a station
     *                                  or a sign is not on its edge
     */
    public RailNetwork(List<Edge> edges, List<Station> stations) {
        this(edges, stations, List.of());
    }

    /**
     * A network whose nodes are where {@code places} says, by their numbers ({@link Edge#start}), so
     * that a search can estimate the way left as Create's does.
     */
    public RailNetwork(List<Edge> edges, List<Station> stations, List<Place> places) {
        this(edges, stations, places, List.of());
    }

    /** A network with track tag blocks on it. */
    public RailNetwork(List<Edge> edges, List<Station> stations, List<Place> places, List<Marker> markers) {
        this.edges = List.copyOf(edges);
        this.stations = List.copyOf(stations);
        this.places = List.copyOf(places);
        this.markers = List.copyOf(markers);
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
            for (Point point : edge.points())
                if (!(point.position() >= 0 && point.position() <= edge.length()))
                    throw new IllegalArgumentException("edge " + i + " has a point at " + point.position() + ", outside it");
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
        for (int i = 0; i < this.markers.size(); i++) {
            Marker marker = this.markers.get(i);
            if (marker.edge() < 0 || marker.edge() >= this.edges.size())
                throw new IllegalArgumentException("tag block " + i + " is on edge " + marker.edge() + ", which is not there");
            if (!(marker.offset() >= 0 && marker.offset() <= this.edges.get(marker.edge()).length()))
                throw new IllegalArgumentException("tag block " + i + " is at " + marker.offset() + ", outside its edge");
        }
        for (List<Integer> onEdge : stationsOn)
            onEdge.sort(Comparator.comparingDouble((Integer i) -> this.stations.get(i).offset()).thenComparingInt(i -> i));
        for (int i = 0; i < this.edges.size(); i++)
            items.add(RouteSearch.Item.of(this, i));
    }

    public List<Edge> edges() {
        return edges;
    }

    public List<Station> stations() {
        return stations;
    }

    /** The track tag blocks on the network. */
    public List<Marker> markers() {
        return markers;
    }

    /** The sections of the track tag blocks on the network. */
    public TagSections tagSections() {
        if (tagSections == null)
            tagSections = TagSections.of(this);
        return tagSections;
    }

    /** Where the node numbered {@code node} is, or null if that is not known. */
    @Nullable Place place(int node) {
        return node >= 0 && node < places.size() ? places.get(node) : null;
    }

    /** The indexes of the stations a train running along {@code edge} can stop at, by their offset on it. */
    List<Integer> stationsOn(int edge) {
        return stationsOn.get(edge);
    }

    List<RouteSearch.Item> items(int edge) {
        return items.get(edge);
    }

    /** The number a train standing at {@code station} has for it: its platform's, or one of its own that no platform has. */
    int platformOf(int station) {
        int platform = stations.get(station).platform();
        return platform >= 0 ? platform : -1 - station;
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
        return legs(names());
    }

    /**
     * The legs of {@link #legs()} from and to stations of the given names only, for which only those
     * stations are searched from: much less work on a large network when a few stations are wanted.
     *
     * <p>From each station, the way to a station of another name is the one a train standing there
     * would be sent on ({@link RouteSearch}), weighing only what the track says: the stations passed,
     * with no train at any. Where stations share the name of the start, the shortest of their ways is
     * the leg.
     */
    public List<Leg> legs(Collection<String> among) {
        return legs(among, OptionalInt.empty());
    }

    /**
     * The legs of {@link #legs(Collection)} for a train held to the track tag {@code tag}, if any
     * ({@link RouteSearch}); see {@link #routes}.
     */
    public List<Leg> legs(Collection<String> among, OptionalInt tag) {
        return routes(among, tag).legs();
    }

    /** The ways of {@link #routes}: where a train held to {@code tag}, if any, comes to from each station. */
    public List<Way> ways(Collection<String> among, OptionalInt tag) {
        return routes(among, tag).ways();
    }

    /**
     * The legs and the ways between the stations of the given names, for a train held to the track tag
     * {@code tag}, if any, from one search from each station.
     *
     * @param legs the legs, as {@link #legs(Collection)} has them. For a train held to a tag, a leg is
     *             timed from the stations of its first name where the track is open to the tag, where
     *             any of them has a way to the other name: a train of the tag may leave a section
     *             without its tag that it stands in, but would not be standing there. From the others
     *             only where none of those has a way, as a set that came there on another tag
     * @param ways where a train standing at each station goes when it is sent to each other name: to
     *             the station it comes to, by the station it starts from and then by the order of
     *             {@link #names()}. A name it cannot get to from a station has no way
     */
    public record Routes(List<Leg> legs, List<Way> ways) {
        public Routes {
            legs = List.copyOf(legs);
            ways = List.copyOf(ways);
        }
    }

    /** See {@link Routes}. */
    public Routes routes(Collection<String> among, OptionalInt tag) {
        Set<String> wanted = Set.copyOf(among);
        List<String> names = names();
        Map<List<String>, Leg> best = new LinkedHashMap<>();
        Set<List<String>> bestOpen = new HashSet<>();
        List<Way> ways = new ArrayList<>();
        for (int i = 0; i < stations.size(); i++) {
            int source = i;
            Station start = stations.get(source);
            if (!wanted.contains(start.name()))
                continue;
            boolean open = opensTo(source, tag);
            List<Way> from = new ArrayList<>();
            for (RouteSearch.Route route : routesFrom(source, wanted, tag).values()) {
                from.add(new Way(source, stations.get(route.station()).name(), route.station()));
                List<String> key = List.of(start.name(), stations.get(route.station()).name());
                Leg known = best.get(key);
                boolean knownOpen = bestOpen.contains(key);
                if (known == null || open && !knownOpen || open == knownOpen && route.distance() < known.distance()) {
                    best.put(key, leg(source, route));
                    if (open)
                        bestOpen.add(key);
                }
            }
            from.sort(Comparator.comparingInt(way -> names.indexOf(way.to())));
            ways.addAll(from);
        }
        List<Leg> legs = new ArrayList<>(best.values());
        legs.sort(Comparator.<Leg>comparingInt(leg -> names.indexOf(leg.from())).thenComparingInt(leg -> names.indexOf(leg.to())));
        return new Routes(legs, ways);
    }

    /** Whether the track at station {@code station} is open to a train held to {@code tag}, if any. */
    private boolean opensTo(int station, OptionalInt tag) {
        Station s = stations.get(station);
        return tag.isEmpty() || tagSections().allows(s.edge(), s.offset(), tag.getAsInt());
    }

    /** The way from station {@code source} to each other name of {@code wanted}, weighing only what the track says. */
    private Map<String, RouteSearch.Route> routesFrom(int source, Set<String> wanted, OptionalInt tag) {
        Station start = stations.get(source);
        int own = platformOf(source);
        RouteSearch.Weights weights = new RouteSearch.Weights() {
            @Override
            public RouteSearch.Presence presence(int platform) {
                return platform == own ? RouteSearch.Presence.OWN : RouteSearch.Presence.NONE;
            }
        };
        return RouteSearch.toEveryName(this, new RouteSearch.Start(start.edge(), start.offset()),
            station -> station != source && wanted.contains(stations.get(station).name())
                && !stations.get(station).name().equals(start.name()),
            weights, tag);
    }

    /** The leg of {@code route}, from station {@code source}: its curves, the stations it passes and the signs it meets. */
    private Leg leg(int source, RouteSearch.Route route) {
        Station start = stations.get(source);
        Station end = stations.get(route.station());
        double distance = route.distance();
        List<Integer> chain = route.edges();

        List<Leg.Curve> curves = new ArrayList<>();
        Set<String> via = new LinkedHashSet<>();
        List<SpeedSign> signs = new ArrayList<>();
        double position = 0;
        for (int step = 0; step < chain.size(); step++) {
            int edgeIndex = chain.get(step);
            Edge edge = edges.get(edgeIndex);
            boolean first = step == 0;
            boolean lastStep = step == chain.size() - 1;
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

            for (int passed : stationsOn.get(edgeIndex)) {
                Station station = stations.get(passed);
                boolean after = !first || station.offset() > start.offset() + EPSILON;
                boolean before = !lastStep || station.offset() < end.offset() - EPSILON;
                // A station with the name of either end cannot be on the way here: it would have a shorter leg of its own.
                if (after && before)
                    via.add(station.name());
            }
        }
        signs.addAll(signsPastTheEnd(chain.getLast(), end.offset(), distance));
        return new Leg(start.name(), end.name(), distance, curves, new ArrayList<>(via), signs, chain);
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
