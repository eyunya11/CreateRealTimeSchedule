package io.github.eyunya11.createrealtimeschedule.railway;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.trains.graph.EdgeData;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.signal.TrackEdgePoint;
import com.simibubi.create.content.trains.station.GlobalStation;

import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Edge;
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
 * {@code TrackEdge.canTravelTo} says the track continues smoothly, and not onto an edge that has an
 * edge point that does not let it through that way ({@code canNavigateVia}: a signal for trains
 * running the other way), nor past such a point on the edge it starts on; these points are the
 * edge's barriers, and {@link RailNetwork} keeps to them as Create does. A station can be stopped at
 * from one direction only, the one {@code canApproachFrom} allows. What a train does not know until it is on the road is left out: signals that are red,
 * other trains, and the kinds of track a particular train cannot use.
 *
 * <p>The speed-limit signs of Create Train Next are read too, where that mod is there, without
 * depending on it: the kind of edge point is looked up by name, and the limit is read by reflection.
 */
public final class CreateNetwork {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The kind of edge point that Create Train Next's speed-limit signs and release signs are. */
    private static final ResourceLocation SPEED_LIMIT = ResourceLocation.fromNamespaceAndPath("createtrainnext", "speed_limit");

    /** Whether a sign that could not be read has been reported, so that the log is told once. */
    private static boolean warned;

    private CreateNetwork() {}

    /** The network of all the track graphs of the game, which a train cannot get from one to another of. */
    public static RailNetwork read(Collection<TrackGraph> graphs) {
        List<Edge> edges = new ArrayList<>();
        List<Station> stations = new ArrayList<>();
        for (TrackGraph graph : graphs)
            addGraph(graph, edges, stations);
        // The graphs are held in a hash map; sorted by name, the export comes out the same every time.
        stations.sort(Comparator.comparing(Station::name).thenComparingInt(Station::edge).thenComparingDouble(Station::offset));
        return new RailNetwork(edges, stations);
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

    private static void addGraph(TrackGraph graph, List<Edge> edges, List<Station> stations) {
        List<TrackNode> nodes = new ArrayList<>();
        for (TrackNodeLocation location : graph.getNodes()) {
            TrackNode node = graph.locateNode(location);
            if (node != null)
                nodes.add(node);
        }
        nodes.sort(NODE_ORDER);

        // The edges, numbered in the order of the nodes they start at and the nodes they lead to.
        int base = edges.size();
        List<TrackEdge> graphEdges = new ArrayList<>();
        Map<TrackEdge, Integer> index = new IdentityHashMap<>();
        for (TrackNode node : nodes) {
            List<Map.Entry<TrackNode, TrackEdge>> leaving = new ArrayList<>(graph.getConnectionsFrom(node).entrySet());
            leaving.sort(Map.Entry.comparingByKey(NODE_ORDER));
            for (Map.Entry<TrackNode, TrackEdge> connection : leaving) {
                index.put(connection.getValue(), base + graphEdges.size());
                graphEdges.add(connection.getValue());
            }
        }

        Map<TrackEdge, List<SpeedSign>> signs = signs(graph);
        for (TrackEdge edge : graphEdges) {
            List<Integer> next = new ArrayList<>();
            for (Map.Entry<TrackNode, TrackEdge> connection : graph.getConnectionsFrom(edge.node2).entrySet())
                if (connection.getKey() != edge.node1 && edge.canTravelTo(connection.getValue()))
                    next.add(index.get(connection.getValue()));
            next.sort(Integer::compare);
            edges.add(new Edge(edge.getLength(), edge.isTurn(), next, signs.getOrDefault(edge, List.of()), barriers(edge)));
        }

        for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
            if (station.name == null || station.name.isBlank() || station.edgeLocation == null)
                continue;
            TrackNode first = graph.locateNode(station.edgeLocation.getFirst());
            TrackNode second = graph.locateNode(station.edgeLocation.getSecond());
            if (first == null || second == null)
                continue;
            TrackEdge edge = graph.getConnection(Couple.create(first, second));
            Integer edgeIndex = edge == null ? null : index.get(edge);
            if (edgeIndex == null || !station.canApproachFrom(second))
                continue;
            double offset = Math.max(0, Math.min(edge.getLength(), station.getLocationOn(edge)));
            stations.add(new Station(station.name, edgeIndex, offset));
        }
    }

    /**
     * Create Train Next's signs on the graph, by the edge they face along. A sign is placed on the edge
     * from the first node of its location to the second, and holds the trains that run that way: its
     * {@code appliesTo(toward)} is {@code isPrimary(toward)}, true for the second node. The edge the
     * other way has it behind its trains, and gets nothing.
     */
    private static Map<TrackEdge, List<SpeedSign>> signs(TrackGraph graph) {
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
            SpeedSign sign = sign(point, offset);
            if (sign != null)
                signs.computeIfAbsent(edge, unused -> new ArrayList<>()).add(sign);
        }
        return signs;
    }

    /** The sign as Create Train Next's {@code isRelease} and {@code getLimit} (in km/h) give it, or null if they cannot be read. */
    private static @Nullable SpeedSign sign(TrackEdgePoint point, double offset) {
        try {
            if ((Boolean) point.getClass().getMethod("isRelease").invoke(point))
                return SpeedSign.release(offset);
            return SpeedSign.limit(offset, (Integer) point.getClass().getMethod("getLimit").invoke(point));
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warned) {
                warned = true;
                LOGGER.warn("Could not read a speed-limit sign of Create Train Next; such signs are left out of the network", e);
            }
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
