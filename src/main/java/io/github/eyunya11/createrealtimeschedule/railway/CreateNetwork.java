package io.github.eyunya11.createrealtimeschedule.railway;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.trains.graph.EdgeData;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.signal.TrackEdgePoint;
import com.simibubi.create.content.trains.station.GlobalStation;

import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Edge;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Point;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Station;
import io.github.eyunya11.createrealtimeschedule.travel.SpeedSign;
import net.createmod.catnip.data.Couple;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Reads Create's track graphs into a {@link RailNetwork}. This is the only class of the package that
 * knows about Create; the search and the writing of the result can be used (and tested) without it.
 *
 * <p>Create's own search for a train's way ({@code Navigation.search}) is tied to a train; this
 * takes from it what does not depend on one. A train goes from an edge to another when
 * {@code TrackEdge.canTravelTo} says the track continues smoothly, and not past an edge point that
 * does not let it through that way ({@code canNavigateVia}: a signal for trains running the other
 * way). These points are the edge's barriers, and {@link RailNetwork} keeps to them as Create does: a
 * train reaches the stations before such a point, and nothing after it. A station can be stopped at
 * from one direction only, the one {@code canApproachFrom} allows. The other points that weigh a way
 * are read as well (station markers a train passes, and signals), but what they weigh with depends on
 * the game at the time a train is sent, which {@link CreateRoutes} reads then: signals that are red,
 * other trains, and the kinds of track a particular train cannot use.
 *
 * <p>The speed-limit signs of Create Train Next are read too, where that mod is there, without
 * depending on it: the kind of edge point is looked up by name, and the limit is read by reflection.
 */
public final class CreateNetwork {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The kind of edge point that Create Train Next's speed-limit signs and release signs are. */
    private static final ResourceLocation SPEED_LIMIT = ResourceLocation.fromNamespaceAndPath("createtrainnext", "speed_limit");

    /** {@code isRelease} and {@code getLimit} of a class of sign, looked up once. */
    private record SignMethods(Method isRelease, Method getLimit) {}

    private static final ClassValue<SignMethods> SIGN_METHODS = new ClassValue<>() {
        @Override
        protected SignMethods computeValue(Class<?> type) {
            try {
                return new SignMethods(type.getMethod("isRelease"), type.getMethod("getLimit"));
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }
    };

    /** The signs of one reading that could not be read, so that the log is told once for each reading. */
    private static final class Unreadable {
        int count;
        @Nullable Exception first;
    }

    private CreateNetwork() {}

    /** The network of all the track graphs of the game, which a train cannot get from one to another of. */
    public static RailNetwork read(Collection<TrackGraph> graphs) {
        Reading reading = new Reading();
        for (TrackGraph graph : graphs)
            addGraph(graph, reading);
        return reading.network();
    }

    /**
     * One track graph read, and how its network stands for the graph's own things: the edge of each
     * of its numbers, the station marker of each platform number, and the signal and the side of it of
     * each signal number. It holds the graph's objects, so it is good only until the graph changes.
     *
     * @param fingerprint the graph's {@link #fingerprint} when it was read
     */
    public record Mapped(RailNetwork network, List<TrackEdge> edges, Map<TrackEdge, Integer> index, List<GlobalStation> platforms,
                         List<SignalSide> signals, long fingerprint) {}

    /** A signal, and the node a train running past it is heading for: the side of it that train meets. */
    public record SignalSide(SignalBoundary signal, TrackNode toward) {}

    /** {@code graph} read, with the way back from the network to it; {@code fingerprint} is its {@link #fingerprint} now. */
    public static Mapped readMapped(TrackGraph graph, long fingerprint) {
        Reading reading = new Reading();
        addGraph(graph, reading);
        return new Mapped(reading.network(), List.copyOf(reading.graphEdges), reading.index, List.copyOf(reading.platforms),
            List.copyOf(reading.signals), fingerprint);
    }

    /** What has been read so far. */
    private static final class Reading {
        final List<Edge> edges = new ArrayList<>();
        final List<Station> stations = new ArrayList<>();
        final List<TrackEdge> graphEdges = new ArrayList<>();
        final Map<TrackEdge, Integer> index = new IdentityHashMap<>();
        final Map<TrackEdgePoint, Integer> platformOf = new IdentityHashMap<>();
        final List<GlobalStation> platforms = new ArrayList<>();
        final List<SignalSide> signals = new ArrayList<>();
        final Unreadable unreadable = new Unreadable();
        final List<RailNetwork.Place> places = new ArrayList<>();

        RailNetwork network() {
            if (unreadable.count > 0)
                LOGGER.warn("Could not read {} speed-limit signs of Create Train Next; they are left out of the network",
                    unreadable.count, unreadable.first);
            // The graphs are held in a hash map; sorted by name, the export comes out the same every time.
            List<Station> sorted = new ArrayList<>(stations);
            sorted.sort(Comparator.comparing(Station::name).thenComparingInt(Station::edge).thenComparingDouble(Station::offset));
            return new RailNetwork(edges, sorted, places);
        }

        int platform(GlobalStation station) {
            return platformOf.computeIfAbsent(station, unused -> {
                platforms.add(station);
                return platforms.size() - 1;
            });
        }
    }

    /**
     * A number that changes when the track changes in a way {@link #read} sees: a node, an edge or a
     * point on one (a station and its name, a signal, a sign and its limit) put in, taken out or moved;
     * an edge laid again or of another kind of track; or a point that lets trains through one way
     * where it did not (a station being assembled, a signal given another side).
     * The same track gives the same number whatever order the game holds it in, and working it out
     * reads much less than {@link #read} does, so that it can be taken often to see whether the track
     * has changed. It is for comparing while the server runs, not for keeping: it is made of hash codes.
     */
    public static long fingerprint(Collection<TrackGraph> graphs) {
        EdgePointType<?> signType = EdgePointType.TYPES.get(SPEED_LIMIT);
        Unreadable unreadable = new Unreadable();
        long print = 0;
        for (TrackGraph graph : graphs) {
            for (TrackNodeLocation location : graph.getNodes()) {
                TrackNode node = graph.locateNode(location);
                if (node == null)
                    continue;
                for (Map.Entry<TrackNode, TrackEdge> connection : graph.getConnectionsFrom(node).entrySet()) {
                    TrackEdge edge = connection.getValue();
                    long hash = 31L * location.hashCode() + connection.getKey().getLocation().hashCode();
                    hash = 31 * hash + Double.hashCode(edge.getLength());
                    hash = 31 * hash + Boolean.hashCode(edge.isTurn());
                    // A track laid again is a new edge of the same shape, which a reading holds the old one of.
                    hash = 31 * hash + System.identityHashCode(edge);
                    hash = 31 * hash + edge.getTrackMaterial().id.hashCode();
                    EdgeData data = edge.getEdgeData();
                    if (data.hasPoints())
                        for (TrackEdgePoint point : data.getPoints()) {
                            hash = 31 * hash + point.getId().hashCode();
                            hash = 31 * hash + Double.hashCode(point.getLocationOn(edge));
                            hash = 31 * hash + Boolean.hashCode(point.canNavigateVia(edge.node2));
                            if (point instanceof GlobalStation station)
                                hash = 31 * hash + Objects.hashCode(station.name) + Boolean.hashCode(station.canApproachFrom(edge.node2));
                            else if (signType != null && point.getType() == signType)
                                hash = 31 * hash + Objects.hashCode(sign(point, 0, unreadable));
                        }
                    // Summed, so that the order of the nodes and the edges does not count; spread first, so that no two cancel out.
                    print += spread(hash);
                }
            }
        }
        return print;
    }

    /** The bits of {@code hash} spread over the whole number, as SplitMix64 finishes one. */
    private static long spread(long hash) {
        hash = (hash ^ (hash >>> 30)) * 0xbf58476d1ce4e5b9L;
        hash = (hash ^ (hash >>> 27)) * 0x94d049bb133111ebL;
        return hash ^ (hash >>> 31);
    }

    private static final Comparator<TrackNode> NODE_ORDER = Comparator
        .<TrackNode, Double>comparing(node -> position(node).x)
        .thenComparing(node -> position(node).y)
        .thenComparing(node -> position(node).z)
        .thenComparingInt(node -> node.getLocation().yOffsetPixels)
        .thenComparing(node -> node.getLocation().dimension.location().toString());

    private static Vec3 position(TrackNode node) {
        return node.getLocation().getLocation();
    }

    private static void addGraph(TrackGraph graph, Reading reading) {
        List<TrackNode> nodes = new ArrayList<>();
        for (TrackNodeLocation location : graph.getNodes()) {
            TrackNode node = graph.locateNode(location);
            if (node != null)
                nodes.add(node);
        }
        nodes.sort(NODE_ORDER);
        Map<TrackNode, Integer> nodeNumber = new IdentityHashMap<>();
        for (TrackNode node : nodes) {
            nodeNumber.put(node, reading.places.size());
            Vec3 at = position(node);
            reading.places.add(new RailNetwork.Place(at.x, at.y, at.z));
        }

        // The edges, numbered in the order of the nodes they start at and the nodes they lead to.
        List<Edge> edges = reading.edges;
        Map<TrackEdge, Integer> index = reading.index;
        List<TrackEdge> graphEdges = new ArrayList<>();
        for (TrackNode node : nodes) {
            List<Map.Entry<TrackNode, TrackEdge>> leaving = new ArrayList<>(graph.getConnectionsFrom(node).entrySet());
            leaving.sort(Map.Entry.comparingByKey(NODE_ORDER));
            for (Map.Entry<TrackNode, TrackEdge> connection : leaving) {
                index.put(connection.getValue(), edges.size() + graphEdges.size());
                graphEdges.add(connection.getValue());
            }
        }
        reading.graphEdges.addAll(graphEdges);

        Map<TrackEdge, List<SpeedSign>> signs = signs(graph, reading.unreadable);
        for (TrackEdge edge : graphEdges) {
            List<Integer> next = new ArrayList<>();
            // What TravellingPoint.travel offers a train at the end of the edge, in the graph's order;
            // with no path to follow, Create takes the first (Navigation.navigateOptions).
            int unsteered = -1;
            for (Map.Entry<TrackNode, TrackEdge> connection : graph.getConnectionsFrom(edge.node2).entrySet()) {
                TrackEdge onward = connection.getValue();
                if (connection.getKey() == edge.node1 || !edge.canTravelTo(onward))
                    continue;
                if (unsteered < 0)
                    unsteered = index.get(onward);
                next.add(index.get(onward));
            }
            edges.add(new Edge(edge.getLength(), edge.isTurn(), next, signs.getOrDefault(edge, List.of()), barriers(edge),
                unsteered, points(edge, index.get(edge), reading), nodeNumber.getOrDefault(edge.node1, -1),
                nodeNumber.getOrDefault(edge.node2, -1)));
        }
    }

    /**
     * The points of {@code edge} as a train running along it meets them, past those that do not let it
     * through (the barriers): a station marker it can stop at goes to the stations, and the others that
     * weigh its way are returned, a station marker it only passes and a signal.
     */
    private static List<Point> points(TrackEdge edge, int edgeIndex, Reading reading) {
        List<Point> points = new ArrayList<>();
        EdgeData data = edge.getEdgeData();
        if (!data.hasPoints())
            return points;
        for (TrackEdgePoint point : data.getPoints()) {
            if (!point.canNavigateVia(edge.node2))
                continue; // a barrier
            double offset = Math.max(0, Math.min(edge.getLength(), point.getLocationOn(edge)));
            if (point instanceof GlobalStation station) {
                int platform = reading.platform(station);
                if (station.name != null && !station.name.isBlank() && station.canApproachFrom(edge.node2))
                    reading.stations.add(new Station(station.name, edgeIndex, offset, platform));
                else
                    points.add(new Point(offset, Point.Kind.STATION, platform));
            } else if (point instanceof SignalBoundary signal) {
                reading.signals.add(new SignalSide(signal, edge.node2));
                points.add(new Point(offset, Point.Kind.SIGNAL, reading.signals.size() - 1));
            }
        }
        return points;
    }

    /**
     * Create Train Next's signs on the graph, by the edge they face along. A sign is placed on the edge
     * from the first node of its location to the second, and holds the trains that run that way: its
     * {@code appliesTo(toward)} is {@code isPrimary(toward)}, true for the second node. The edge the
     * other way has it behind its trains, and gets nothing.
     */
    private static Map<TrackEdge, List<SpeedSign>> signs(TrackGraph graph, Unreadable unreadable) {
        Map<TrackEdge, List<SpeedSign>> signs = new IdentityHashMap<>();
        EdgePointType<?> type = EdgePointType.TYPES.get(SPEED_LIMIT);
        if (type == null)
            return signs;
        for (TrackEdgePoint point : graph.getPoints(type)) {
            if (point.edgeLocation == null)
                continue;
            TrackNode first = graph.locateNode(point.edgeLocation.getFirst());
            TrackNode second = graph.locateNode(point.edgeLocation.getSecond());
            TrackEdge edge = first == null || second == null ? null : graph.getConnection(Couple.create(first, second));
            if (edge == null)
                continue;
            double offset = Math.max(0, Math.min(edge.getLength(), point.getLocationOn(edge)));
            SpeedSign sign = sign(point, offset, unreadable);
            if (sign != null)
                signs.computeIfAbsent(edge, unused -> new ArrayList<>()).add(sign);
        }
        return signs;
    }

    /** The sign as Create Train Next's {@code isRelease} and {@code getLimit} (in km/h) give it, or null if they cannot be read. */
    private static @Nullable SpeedSign sign(TrackEdgePoint point, double offset, Unreadable unreadable) {
        try {
            SignMethods methods = SIGN_METHODS.get(point.getClass());
            if ((Boolean) methods.isRelease().invoke(point))
                return SpeedSign.release(offset);
            return SpeedSign.limit(offset, (Integer) methods.getLimit().invoke(point));
        } catch (ReflectiveOperationException | RuntimeException e) {
            unreadable.count++;
            if (unreadable.first == null)
                unreadable.first = e;
            return null;
        }
    }

    /** Where on the edge there is a point that does not let a train through towards the edge's far end. */
    private static List<Double> barriers(TrackEdge edge) {
        List<Double> barriers = new ArrayList<>();
        EdgeData data = edge.getEdgeData();
        if (data.hasPoints())
            for (TrackEdgePoint point : data.getPoints())
                if (!point.canNavigateVia(edge.node2))
                    barriers.add(Math.max(0, Math.min(edge.getLength(), point.getLocationOn(edge))));
        return barriers;
    }
}
