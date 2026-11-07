package io.github.eyunya11.createrealtimeschedule.railway;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.EdgeData;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.signal.SignalBlock;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.signal.SignalEdgeGroup;
import com.simibubi.create.content.trains.signal.SignalPropagator;
import com.simibubi.create.content.trains.signal.TrackEdgePoint;
import com.simibubi.create.content.trains.station.GlobalStation;

import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Edge;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Point;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Station;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTagEdgePoint;
import io.github.eyunya11.createrealtimeschedule.travel.SpeedSign;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

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
 * So are this mod's track tag blocks, which {@link TagSections} makes the tagged stretches of.
 */
public final class CreateNetwork {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The kind of edge point that Create Train Next's speed-limit signs and release signs are. */
    static final ResourceLocation SPEED_LIMIT = ResourceLocation.fromNamespaceAndPath("createtrainnext", "speed_limit");

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
        return readAll(graphs).network();
    }

    /**
     * The network of {@link #read}, and the graphs' edge of each of its edges.
     *
     * @param edges the edge of the graphs that each edge of the network is, by its index
     */
    public record All(RailNetwork network, List<TrackEdge> edges) {}

    /** {@link #read}, with the way back from the network's edges to the graphs'. */
    public static All readAll(Collection<TrackGraph> graphs) {
        Reading reading = new Reading();
        for (TrackGraph graph : graphs)
            addGraph(graph, reading);
        return new All(reading.network(), List.copyOf(reading.graphEdges));
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
        /** The graph of each signal side of {@link #signals}. */
        final List<TrackGraph> signalGraphs = new ArrayList<>();
        /** For each edge of {@link #edges}, where it changes block; empty where that is not known. */
        final List<List<SignalBlocks.Change>> blockChanges = new ArrayList<>();
        /** The signal blocks met so far, Create's signal edge groups, numbered in the order they are met. */
        final Map<UUID, Integer> blocks = new LinkedHashMap<>();
        /** For the track between two nodes, either way, the block it is taken to be where Create has not worked one out. */
        final Map<Set<TrackNodeLocation>, Integer> unknownBlocks = new HashMap<>();
        /** How many stretches of track and signal sides had no block of Create's. */
        int unknownStretches;
        final Unreadable unreadable = new Unreadable();
        final List<RailNetwork.Place> places = new ArrayList<>();
        final List<RailNetwork.Marker> markers = new ArrayList<>();

        RailNetwork network() {
            if (unreadable.count > 0)
                LOGGER.warn("Could not read {} speed-limit signs of Create Train Next; they are left out of the network",
                    unreadable.count, unreadable.first);
            // The graphs are held in a hash map; sorted by name, the export comes out the same every time.
            List<Station> sorted = new ArrayList<>(stations);
            sorted.sort(Comparator.comparing(Station::name).thenComparingInt(Station::edge).thenComparingDouble(Station::offset));
            SignalBlocks blocks = signalBlocks();
            if (unknownStretches > 0)
                LOGGER.warn("Create has not worked out the signal blocks of {} stretches of track yet; the track between two nodes "
                    + "is taken to be a block of its own there until the network is read again", unknownStretches);
            return new RailNetwork(edges, sorted, places, markers, blocks);
        }

        /**
         * The block taken for the track between {@code a} and {@code b}, either way, where Create has not
         * worked out the block: one of its own, so that trains on it still keep each other out.
         */
        int unknownBlock(TrackNodeLocation a, TrackNodeLocation b) {
            unknownStretches++;
            return unknownBlocks.computeIfAbsent(new HashSet<>(List.of(a, b)), unused -> block(UUID.randomUUID()));
        }

        int block(UUID group) {
            return blocks.computeIfAbsent(group, unused -> blocks.size());
        }

        /** The blocks of what has been read, with the signals between them and the blocks that cross. */
        SignalBlocks signalBlocks() {
            Map<UUID, Map<Boolean, Integer>> sideOf = new HashMap<>();
            for (int i = 0; i < signals.size(); i++) {
                SignalSide side = signals.get(i);
                sideOf.computeIfAbsent(side.signal().getId(), unused -> new HashMap<>()).put(side.signal().isPrimary(side.toward()), i);
            }
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            List<SignalBlocks.Signal> read = new ArrayList<>();
            for (int i = 0; i < signals.size(); i++) {
                SignalSide side = signals.get(i);
                SignalBoundary signal = side.signal();
                boolean primary = signal.isPrimary(side.toward());
                UUID group = signal.getGroup(side.toward());
                // A side whose block Create has not worked out yet leads into that of the track it is on.
                int block = group != null ? block(group)
                    : signal.edgeLocation != null ? unknownBlock(signal.edgeLocation.getFirst(), signal.edgeLocation.getSecond())
                    : block(UUID.randomUUID());
                List<Integer> next = new ArrayList<>();
                for (Map.Entry<UUID, Boolean> chained : SignalPropagator.collectChainedSignals(signalGraphs.get(i), signal, primary).entrySet()) {
                    Integer number = sideOf.getOrDefault(chained.getKey(), Map.of()).get(chained.getValue());
                    if (number != null)
                        next.add(number);
                }
                next.sort(Comparator.naturalOrder());
                read.add(new SignalBlocks.Signal(block, next, lights(server, signal, primary),
                    signal.types.get(primary) == SignalBlock.SignalType.CROSS_SIGNAL));
            }
            List<List<Integer>> crossings = new ArrayList<>();
            for (Map.Entry<UUID, Integer> each : List.copyOf(blocks.entrySet())) {
                SignalEdgeGroup group = Create.RAILWAYS.signalEdgeGroups.get(each.getKey());
                if (group == null)
                    continue;
                for (UUID other : group.intersecting.values()) {
                    Integer number = blocks.get(other);
                    if (number != null && number > each.getValue() && !crossings.contains(List.of(each.getValue(), number)))
                        crossings.add(List.of(each.getValue(), number));
                }
            }
            crossings.sort(Comparator.comparing((List<Integer> pair) -> pair.get(0)).thenComparing(pair -> pair.get(1)));
            return new SignalBlocks(blockChanges, read, blocks.size(), crossings, TrainNextSignals.speeds());
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
     * point on one (a station and its name, a signal, a sign and its limit, a tag block and its tag) put
     * in, taken out or moved;
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
                            else if (point instanceof TrackTagEdgePoint tag)
                                hash = 31 * hash + tag.tags().hashCode() + Boolean.hashCode(tag.oneWay());
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
                unsteered, points(graph, edge, index.get(edge), reading), nodeNumber.getOrDefault(edge.node1, -1),
                nodeNumber.getOrDefault(edge.node2, -1)));
            reading.blockChanges.add(blockChanges(graph, edge, reading));
        }
        markers(graph, index, reading);
    }

    /**
     * The track tag blocks on {@code graph}, each on the edge it faces along: its point's location runs
     * from the node behind it to the node it faces, as a station's does.
     */
    private static void markers(TrackGraph graph, Map<TrackEdge, Integer> index, Reading reading) {
        EdgePointType<TrackTagEdgePoint> type = TrackTagEdgePoint.type();
        if (type == null)
            return;
        for (TrackTagEdgePoint point : graph.getPoints(type)) {
            if (point.edgeLocation == null)
                continue;
            TrackNode first = graph.locateNode(point.edgeLocation.getFirst());
            TrackNode second = graph.locateNode(point.edgeLocation.getSecond());
            TrackEdge edge = first == null || second == null ? null : graph.getConnection(Couple.create(first, second));
            Integer at = edge == null ? null : index.get(edge);
            if (at == null)
                continue;
            double offset = Math.max(0, Math.min(edge.getLength(), point.getLocationOn(edge)));
            BlockPos block = point.getBlockEntityPos();
            RailNetwork.Place place = block == null ? new RailNetwork.Place(Double.NaN, Double.NaN, Double.NaN)
                : new RailNetwork.Place(block.getX(), block.getY(), block.getZ());
            reading.markers.add(new RailNetwork.Marker(at, offset, point.tags(), point.oneWay(), place));
        }
    }

    /**
     * The points of {@code edge} as a train running along it meets them, past those that do not let it
     * through (the barriers): a station marker it can stop at goes to the stations, and the others that
     * weigh its way are returned, a station marker it only passes and a signal.
     */
    private static List<Point> points(TrackGraph graph, TrackEdge edge, int edgeIndex, Reading reading) {
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
                reading.signalGraphs.add(graph);
                points.add(new Point(offset, Point.Kind.SIGNAL, reading.signals.size() - 1));
            }
        }
        return points;
    }

    /**
     * Where {@code edge} changes block, as a train running along it goes: the block it starts in, and at
     * each signal on it, the one a train passing it goes into. A signal a train cannot pass this way
     * counts too, for the trains that stand behind it. Where Create has not worked a block out yet, the
     * track between the edge's two nodes is taken to be a block of its own ({@link Reading#unknownBlock}).
     */
    private static List<SignalBlocks.Change> blockChanges(TrackGraph graph, TrackEdge edge, Reading reading) {
        EdgeData data = edge.getEdgeData();
        UUID first = data.getGroupAtPosition(graph, 0);
        List<SignalBlocks.Change> changes = new ArrayList<>();
        changes.add(new SignalBlocks.Change(0, first != null ? reading.block(first)
            : reading.unknownBlock(edge.node1.getLocation(), edge.node2.getLocation())));
        if (!data.hasPoints())
            return changes;
        List<SignalBlocks.Change> atSignals = new ArrayList<>();
        for (TrackEdgePoint point : data.getPoints()) {
            if (!(point instanceof SignalBoundary signal))
                continue;
            UUID group = signal.getGroup(edge.node2);
            int block = group != null ? reading.block(group) : reading.unknownBlock(edge.node1.getLocation(), edge.node2.getLocation());
            atSignals.add(new SignalBlocks.Change(Math.max(0, Math.min(edge.getLength(), point.getLocationOn(edge))), block));
        }
        atSignals.sort(Comparator.comparingDouble(SignalBlocks.Change::position));
        changes.addAll(atSignals);
        return changes;
    }

    /**
     * For each light signal of Create Train Next on the {@code primary} side of {@code signal}, how many
     * aspects it shows. A head in a chunk that is not loaded cannot be read, and is left out, as a
     * signal of Create's own is.
     */
    private static List<Integer> lights(@Nullable MinecraftServer server, SignalBoundary signal, boolean primary) {
        if (server == null || signal.edgeLocation == null)
            return List.of();
        ServerLevel level = server.getLevel(signal.edgeLocation.getFirst().dimension);
        if (level == null)
            return List.of();
        List<Integer> lights = new ArrayList<>();
        for (BlockPos head : signal.blockEntities.get(primary).keySet()) {
            TrainNextSignals.Look look = TrainNextSignals.look(level, head);
            if (look != null && look.kind().startsWith("light"))
                lights.add(Integer.parseInt(look.kind().substring("light".length())));
        }
        lights.sort(Comparator.naturalOrder());
        return lights;
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

    /** {@link #sign} at the start of its edge, for the map, which places it itself; null, and a warning in the log, if it cannot be read. */
    static @Nullable SpeedSign readSign(TrackEdgePoint point) {
        Unreadable unreadable = new Unreadable();
        SpeedSign sign = sign(point, 0, unreadable);
        if (sign == null)
            LOGGER.warn("Could not read a speed-limit sign of Create Train Next; it is left out of the map", unreadable.first);
        return sign;
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
