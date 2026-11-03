package io.github.eyunya11.createrealtimeschedule.railway;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.signal.SingleBlockEntityEdgePoint;
import com.simibubi.create.content.trains.signal.TrackEdgePoint;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.track.BezierConnection;

import io.github.eyunya11.createrealtimeschedule.map.LiveMap;
import io.github.eyunya11.createrealtimeschedule.map.SignalLooks;
import io.github.eyunya11.createrealtimeschedule.map.TrackKeys;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.SignalMark;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.SignalSide;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.SpeedMark;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Spot;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.StationMark;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Track;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.TrainMark;
import io.github.eyunya11.createrealtimeschedule.travel.SpeedSign;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Reads Create's track graphs, and the trains on them, into a {@link TrackMap}, or into the
 * {@link LiveMap} of a dimension that map screens are sent.
 *
 * <p>A track is an edge of a graph, read once for both ways of it: the way from the lower of its two
 * nodes, in the order of {@link TrackKeys}. A curve is read as points about two blocks apart along
 * it, from Create's own table of where its segments are. A signal shows each way it has a head for:
 * the state Create gives that side, or, for a head that is a colour light signal of Create Train Next,
 * the aspect that signal shows ({@link TrainNextSignals}). An aspect that cannot be read now, in a
 * chunk that is not loaded, is the one last read, as long as the signal is there.
 *
 * <p>Places are where Create's graph has them, without the smoothing of track that rises a pixel at
 * a time: from above, it makes no difference.
 */
public final class CreateMap {

    /** About how far apart the points of a curve are, in Create's segments of half a block. */
    private static final int SEGMENTS_PER_POINT = 4;

    /**
     * The looks of colour light signals last read, by signal and side (first, then second). In single
     * player the client reads the map on its own thread while the server does on its, so it is used
     * holding its lock.
     */
    private static final Map<SignalBoundary, TrainNextSignals.Look[]> REMEMBERED = new WeakHashMap<>();

    private CreateMap() {}

    /** Where to find the level of a dimension, to read the heads of its signals; null if it is not there. */
    @FunctionalInterface
    public interface Levels {
        @Nullable Level level(ResourceKey<Level> dimension);
    }

    /**
     * The map, and which track of it each edge of the graphs is.
     *
     * @param trackOf  the index of the track of each edge, both ways, by the edge itself
     * @param backward the edges that run from the last point of their track to its first
     */
    public record Read(TrackMap map, Map<TrackEdge, Integer> trackOf, Set<TrackEdge> backward) {}

    /**
     * @param graphs the track graphs
     * @param trains the trains to put on the map
     * @param levels where the heads of the signals are read; null to read none, so that every side shows
     *               Create's state or the look last read. A client, which has no heads, gets no signals.
     */
    public static Read read(Collection<TrackGraph> graphs, Collection<Train> trains, @Nullable Levels levels) {
        return read(graphs, trains, levels, train -> List.of());
    }

    /**
     * {@link #read(Collection, Collection, Levels)}, with the track tags each train runs on.
     *
     * @param tagsOf the track tags a train runs on in its duty ({@link TrainMark#tags})
     */
    public static Read read(Collection<TrackGraph> graphs, Collection<Train> trains, @Nullable Levels levels,
        Function<Train, List<String>> tagsOf) {
        // The tracks, by key: sorted, the map comes out the same whatever order the game holds them in.
        TreeMap<String, Found> found = new TreeMap<>();
        for (TrackGraph graph : graphs)
            for (TrackNodeLocation location : graph.getNodes()) {
                TrackNode node = graph.locateNode(location);
                if (node == null)
                    continue;
                for (TrackEdge edge : graph.getConnectionsFrom(node).values()) {
                    if (edge.isInterDimensional())
                        continue;
                    String key = key(edge);
                    // The way from the lower end; the other way only where there is no such way.
                    if (Arrays.compare(place(edge.node1.getLocation()), place(edge.node2.getLocation())) <= 0 || !found.containsKey(key))
                        found.put(key, new Found(graph, edge));
                }
            }

        TreeSet<String> dimensionNames = new TreeSet<>();
        for (Found track : found.values())
            dimensionNames.add(dimension(track.edge().node1));
        List<String> dimensions = List.copyOf(dimensionNames);

        List<Track> tracks = new ArrayList<>();
        Map<TrackEdge, Integer> trackOf = new IdentityHashMap<>();
        Set<TrackEdge> backward = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<String, Integer> indexOf = new HashMap<>();
        for (Map.Entry<String, Found> entry : found.entrySet()) {
            TrackEdge edge = entry.getValue().edge();
            int index = tracks.size();
            tracks.add(new Track(entry.getKey(), dimensions.indexOf(dimension(edge.node1)), points(edge)));
            indexOf.put(entry.getKey(), index);
            trackOf.put(edge, index);
            TrackEdge back = entry.getValue().graph().getConnection(Couple.create(edge.node2, edge.node1));
            if (back != null) {
                trackOf.put(back, index);
                backward.add(back);
            }
        }

        List<StationMark> stations = new ArrayList<>();
        for (TrackGraph graph : graphs)
            for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
                At at = at(graph, station);
                Integer track = at == null ? null : trackOf.get(at.edge());
                if (track != null && station.name != null && !station.name.isBlank())
                    // A station takes the trains running from the first node of its place to the second (GlobalStation.canApproachFrom).
                    stations.add(new StationMark(station.name, track, at.spot(), at.direction()));
            }
        stations.sort(Comparator.comparing(StationMark::name).thenComparingDouble(mark -> mark.at().x())
            .thenComparingDouble(mark -> mark.at().z()));
        List<SpeedMark> limits = new ArrayList<>();
        for (LiveMap.Limit limit : limits(graphs, null)) {
            Integer track = indexOf.get(limit.track());
            if (track != null)
                limits.add(new SpeedMark(track, limit.at(), limit.direction(), limit.kmh(), limit.release()));
        }

        List<SignalMark> signals = new ArrayList<>();
        for (Keyed<LiveMap.Signal> signal : signals(graphs, levels, null)) {
            Integer track = indexOf.get(signal.value().track());
            if (track != null)
                signals.add(new SignalMark(signal.id(), track, signal.value().at(), signal.value().sides()));
        }
        List<TrainMark> trainMarks = new ArrayList<>();
        for (LiveMap.Train train : trains(trains, null, tagsOf)) {
            List<TrackMap.Carriage> carriages = new ArrayList<>();
            for (LiveMap.Carriage carriage : train.carriages()) {
                Integer front = indexOf.get(carriage.frontTrack());
                Integer back = indexOf.get(carriage.backTrack());
                if (front != null && back != null)
                    carriages.add(new TrackMap.Carriage(carriage.front(), carriage.back(), front, back));
            }
            if (!carriages.isEmpty())
                trainMarks.add(new TrainMark(train.name(), carriages, train.tags()));
        }
        return new Read(new TrackMap(dimensions, tracks, stations, signals, limits, trainMarks), trackOf, backward);
    }

    /**
     * The signals, the trains and the speed-limit signs of one dimension now.
     *
     * @param dimension the dimension, such as {@code minecraft:overworld}
     */
    public static LiveMap live(Collection<TrackGraph> graphs, Collection<Train> trains, Levels levels, String dimension) {
        return live(graphs, trains, levels, dimension, train -> List.of());
    }

    /**
     * {@link #live(Collection, Collection, Levels, String)}, with the track tags each train runs on.
     *
     * @param tagsOf the track tags a train runs on in its duty ({@link LiveMap.Train#tags})
     */
    public static LiveMap live(Collection<TrackGraph> graphs, Collection<Train> trains, Levels levels, String dimension,
        Function<Train, List<String>> tagsOf) {
        List<LiveMap.Signal> signals = new ArrayList<>();
        for (Keyed<LiveMap.Signal> signal : signals(graphs, levels, dimension))
            signals.add(signal.value());
        return new LiveMap(signals, trains(trains, dimension, tagsOf), limits(graphs, dimension));
    }

    /** Create Train Next's speed-limit signs, in order of where they are; of one dimension, or of all for null. */
    private static List<LiveMap.Limit> limits(Collection<TrackGraph> graphs, @Nullable String dimension) {
        List<LiveMap.Limit> limits = new ArrayList<>();
        EdgePointType<?> signType = EdgePointType.TYPES.get(CreateNetwork.SPEED_LIMIT);
        if (signType == null)
            return limits;
        for (TrackGraph graph : graphs)
            for (TrackEdgePoint point : graph.getPoints(signType)) {
                At at = at(graph, point);
                if (at == null || dimension != null && !dimension.equals(dimension(at.edge().node1)))
                    continue;
                SpeedSign sign = CreateNetwork.readSign(point);
                // A sign holds the trains running from the first node of its place to the second.
                BlockPos block = point instanceof SingleBlockEntityEdgePoint single ? single.getBlockEntityPos() : null;
                if (sign != null)
                    limits.add(new LiveMap.Limit(key(at.edge()), at.spot(), at.direction(), sign.kmh(), sign.release(),
                        block != null && left(at.spot(), at.direction(), block)));
            }
        limits.sort(Comparator.comparingDouble((LiveMap.Limit limit) -> limit.at().x()).thenComparingDouble(limit -> limit.at().z()));
        return limits;
    }

    /** A thing read, with Create's id for it. */
    private record Keyed<T>(String id, T value) {}

    /** The signals with a head, in order of where they are; of one dimension, or of all for null. */
    private static List<Keyed<LiveMap.Signal>> signals(Collection<TrackGraph> graphs, @Nullable Levels levels, @Nullable String dimension) {
        List<Keyed<LiveMap.Signal>> signals = new ArrayList<>();
        for (TrackGraph graph : graphs)
            for (SignalBoundary signal : graph.getPoints(EdgePointType.SIGNAL)) {
                At at = at(graph, signal);
                if (at == null || dimension != null && !dimension.equals(dimension(at.edge().node1)))
                    continue;
                List<SignalSide> sides = new ArrayList<>();
                Level level = levels == null ? null : levels.level(at.edge().node1.getLocation().dimension);
                for (boolean first : new boolean[] {true, false}) {
                    if (signal.blockEntities.get(first).isEmpty())
                        continue;
                    // The first side is for the trains running from the first node of its place to the second.
                    Spot direction = first ? at.direction() : new Spot(-at.direction().x(), -at.direction().z());
                    sides.add(side(signal, first, at.spot(), direction, level));
                }
                if (!sides.isEmpty())
                    signals.add(new Keyed<>(signal.getId().toString(), new LiveMap.Signal(key(at.edge()), at.spot(), sides)));
            }
        signals.sort(Comparator.comparingDouble((Keyed<LiveMap.Signal> signal) -> signal.value().at().x())
            .thenComparingDouble(signal -> signal.value().at().z()).thenComparing(Keyed::id));
        return signals;
    }

    /** The trains, by name, with their carriages that are on track; of one dimension, or of all for null. */
    private static List<LiveMap.Train> trains(Collection<Train> trains, @Nullable String dimension, Function<Train, List<String>> tagsOf) {
        List<LiveMap.Train> found = new ArrayList<>();
        for (Train train : trains) {
            if (train.invalid || train.carriages == null)
                continue;
            List<LiveMap.Carriage> carriages = new ArrayList<>();
            for (Carriage carriage : train.carriages) {
                End front = end(carriage.getLeadingPoint());
                End back = end(carriage.getTrailingPoint());
                if (front != null && back != null && (dimension == null || dimension.equals(dimension(front.edge().node1))))
                    carriages.add(new LiveMap.Carriage(front.spot(), back.spot(), key(front.edge()), key(back.edge())));
            }
            if (!carriages.isEmpty())
                found.add(new LiveMap.Train(train.name == null ? "" : train.name.getString(), carriages, tagsOf.apply(train)));
        }
        found.sort(Comparator.comparing(LiveMap.Train::name));
        return found;
    }

    /** An edge found, and its graph. */
    private record Found(TrackGraph graph, TrackEdge edge) {}

    /** The key of the track an edge is, whichever way it runs. */
    private static String key(TrackEdge edge) {
        return TrackKeys.of(dimension(edge.node1), place(edge.node1.getLocation()), place(edge.node2.getLocation()));
    }

    /** Create's place of a node, in half blocks, and the pixels it is raised by: all that tells Create's nodes apart. */
    private static int[] place(TrackNodeLocation location) {
        return new int[] {location.getX(), location.getY(), location.getZ(), location.yOffsetPixels};
    }

    private static String dimension(TrackNode node) {
        return node.getLocation().dimension.location().toString();
    }

    private static Spot spot(Vec3 at) {
        return new Spot(at.x, at.z);
    }

    /** The line an edge runs along, from its first node to its second. */
    private static List<Spot> points(TrackEdge edge) {
        Vec3 start = edge.node1.getLocation().getLocation();
        Vec3 end = edge.node2.getLocation().getLocation();
        List<Spot> points = new ArrayList<>();
        points.add(spot(start));
        BezierConnection turn = edge.isTurn() ? edge.getTurn() : null;
        if (turn != null) {
            int segments = turn.getSegmentCount();
            for (int i = SEGMENTS_PER_POINT; i < segments - SEGMENTS_PER_POINT / 2; i += SEGMENTS_PER_POINT)
                points.add(spot(edge.getPosition(null, turn.getSegmentT(i))));
        }
        points.add(spot(end));
        return points;
    }

    /** Where a point on the track is, the edge it is on, and the way from the first node of its place to the second. */
    private record At(TrackEdge edge, Spot spot, Spot direction) {}

    private static @Nullable At at(TrackGraph graph, TrackEdgePoint point) {
        if (point.edgeLocation == null)
            return null;
        TrackNode first = graph.locateNode(point.edgeLocation.getFirst());
        TrackNode second = graph.locateNode(point.edgeLocation.getSecond());
        TrackEdge edge = first == null || second == null ? null : graph.getConnection(Couple.create(first, second));
        if (edge == null || edge.isInterDimensional() || edge.getLength() <= 0)
            return null;
        double offset = Math.max(0, Math.min(edge.getLength(), point.getLocationOn(edge)));
        Vec3 direction = edge.getDirectionAt(offset);
        double length = Math.hypot(direction.x, direction.z);
        Spot way = length > 1e-9 ? new Spot(direction.x / length, direction.z / length) : new Spot(0, 0);
        return new At(edge, spot(edge.getPosition(null, offset / edge.getLength())), way);
    }

    /** What one side of a signal shows. */
    private static SignalSide side(SignalBoundary signal, boolean first, Spot at, Spot direction, @Nullable Level level) {
        boolean left = signal.blockEntities.get(first).keySet().stream().findFirst().map(block -> left(at, direction, block)).orElse(false);
        TrainNextSignals.Look look = null;
        if (level != null)
            for (BlockPos head : signal.blockEntities.get(first).keySet()) {
                look = TrainNextSignals.look(level, head);
                if (look != null)
                    break;
            }
        int side = first ? 0 : 1;
        synchronized (REMEMBERED) {
            TrainNextSignals.Look[] remembered = REMEMBERED.get(signal);
            if (look != null) {
                if (remembered == null)
                    REMEMBERED.put(signal, remembered = new TrainNextSignals.Look[2]);
                remembered[side] = look;
            } else if (remembered != null) {
                look = remembered[side];
            }
        }
        if (look != null)
            return new SignalSide(direction, look.kind(), look.lamps(), left);
        return new SignalSide(direction, SignalLooks.CREATE, SignalLooks.create(signal.cachedStates.get(first).name()), left);
    }

    /** Whether the middle of {@code block} is to the left of trains running {@code direction} at {@code at}. */
    private static boolean left(Spot at, Spot direction, BlockPos block) {
        // To the right of (dx, dz) in the world is (-dz, dx).
        return (block.getX() + 0.5 - at.x()) * -direction.z() + (block.getZ() + 0.5 - at.z()) * direction.x() < 0;
    }

    /** Where one end of a carriage is, and the edge it is on. */
    private record End(TrackEdge edge, Spot spot) {}

    private static @Nullable End end(@Nullable TravellingPoint point) {
        if (point == null || point.edge == null || point.edge.isInterDimensional() || point.edge.getLength() <= 0)
            return null;
        return new End(point.edge, spot(point.edge.getPosition(null, point.position / point.edge.getLength())));
    }
}
