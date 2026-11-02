package io.github.eyunya11.createrealtimeschedule.railway;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.WeakHashMap;

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
import com.simibubi.create.content.trains.signal.TrackEdgePoint;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.track.BezierConnection;

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
 * Reads Create's track graphs, and the trains on them, into a {@link TrackMap}.
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

    /** The looks of colour light signals last read, by signal and side (first, then second). */
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
     *               Create's state or the look last read
     */
    public static Read read(Collection<TrackGraph> graphs, Collection<Train> trains, @Nullable Levels levels) {
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
                    int[] from = place(edge.node1.getLocation());
                    int[] to = place(edge.node2.getLocation());
                    String key = TrackKeys.of(dimension(edge.node1), from, to);
                    // The way from the lower end; the other way only where there is no such way.
                    if (Arrays.compare(from, to) <= 0 || !found.containsKey(key))
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
        for (Map.Entry<String, Found> entry : found.entrySet()) {
            TrackEdge edge = entry.getValue().edge();
            int index = tracks.size();
            tracks.add(new Track(entry.getKey(), dimensions.indexOf(dimension(edge.node1)), points(edge)));
            trackOf.put(edge, index);
            TrackEdge back = entry.getValue().graph().getConnection(Couple.create(edge.node2, edge.node1));
            if (back != null) {
                trackOf.put(back, index);
                backward.add(back);
            }
        }

        List<StationMark> stations = new ArrayList<>();
        List<SignalMark> signals = new ArrayList<>();
        List<SpeedMark> limits = new ArrayList<>();
        EdgePointType<?> signType = EdgePointType.TYPES.get(CreateNetwork.SPEED_LIMIT);
        for (TrackGraph graph : graphs) {
            for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
                At at = at(graph, station, trackOf);
                if (at != null && station.name != null && !station.name.isBlank())
                    stations.add(new StationMark(station.name, at.track(), at.spot()));
            }
            for (SignalBoundary signal : graph.getPoints(EdgePointType.SIGNAL)) {
                At at = at(graph, signal, trackOf);
                if (at == null)
                    continue;
                List<SignalSide> sides = new ArrayList<>();
                Level level = levels == null ? null : levels.level(at.edge().node1.getLocation().dimension);
                for (boolean first : new boolean[] {true, false}) {
                    if (signal.blockEntities.get(first).isEmpty())
                        continue;
                    // The first side is for the trains running from the first node of its place to the second.
                    Spot direction = first ? at.direction() : new Spot(-at.direction().x(), -at.direction().z());
                    sides.add(side(signal, first, direction, level));
                }
                if (!sides.isEmpty())
                    signals.add(new SignalMark(signal.getId().toString(), at.track(), at.spot(), sides));
            }
            if (signType != null)
                for (TrackEdgePoint point : graph.getPoints(signType)) {
                    At at = at(graph, point, trackOf);
                    SpeedSign sign = at == null ? null : CreateNetwork.readSign(point);
                    // A sign holds the trains running from the first node of its place to the second.
                    if (sign != null)
                        limits.add(new SpeedMark(at.track(), at.spot(), at.direction(), sign.kmh(), sign.release()));
                }
        }
        stations.sort(Comparator.comparing(StationMark::name).thenComparingDouble(mark -> mark.at().x())
            .thenComparingDouble(mark -> mark.at().z()));
        signals.sort(Comparator.comparingDouble((SignalMark mark) -> mark.at().x()).thenComparingDouble(mark -> mark.at().z())
            .thenComparing(SignalMark::id));
        limits.sort(Comparator.comparingDouble((SpeedMark mark) -> mark.at().x()).thenComparingDouble(mark -> mark.at().z()));

        List<TrainMark> trainMarks = new ArrayList<>();
        for (Train train : trains) {
            if (train.invalid || train.carriages == null)
                continue;
            List<TrackMap.Carriage> carriages = new ArrayList<>();
            for (Carriage carriage : train.carriages) {
                End front = end(carriage.getLeadingPoint(), trackOf);
                End back = end(carriage.getTrailingPoint(), trackOf);
                if (front != null && back != null)
                    carriages.add(new TrackMap.Carriage(front.spot(), back.spot(), front.track(), back.track()));
            }
            if (!carriages.isEmpty())
                trainMarks.add(new TrainMark(train.name == null ? "" : train.name.getString(), carriages));
        }
        trainMarks.sort(Comparator.comparing(TrainMark::name));

        return new Read(new TrackMap(dimensions, tracks, stations, signals, limits, trainMarks), trackOf, backward);
    }

    /** An edge found, and its graph. */
    private record Found(TrackGraph graph, TrackEdge edge) {}

    /** Create's place of a node, in half blocks. */
    private static int[] place(TrackNodeLocation location) {
        return new int[] {location.getX(), location.getY(), location.getZ()};
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

    /** Where a point on the track is, the track it is on, and the way from the first node of its place to the second. */
    private record At(TrackEdge edge, int track, Spot spot, Spot direction) {}

    private static @Nullable At at(TrackGraph graph, TrackEdgePoint point, Map<TrackEdge, Integer> trackOf) {
        if (point.edgeLocation == null)
            return null;
        TrackNode first = graph.locateNode(point.edgeLocation.getFirst());
        TrackNode second = graph.locateNode(point.edgeLocation.getSecond());
        TrackEdge edge = first == null || second == null ? null : graph.getConnection(Couple.create(first, second));
        Integer track = edge == null ? null : trackOf.get(edge);
        if (track == null || edge.getLength() <= 0)
            return null;
        double offset = Math.max(0, Math.min(edge.getLength(), point.getLocationOn(edge)));
        Vec3 direction = edge.getDirectionAt(offset);
        double length = Math.hypot(direction.x, direction.z);
        Spot way = length > 1e-9 ? new Spot(direction.x / length, direction.z / length) : new Spot(0, 0);
        return new At(edge, track, spot(edge.getPosition(null, offset / edge.getLength())), way);
    }

    /** What one side of a signal shows. */
    private static SignalSide side(SignalBoundary signal, boolean first, Spot direction, @Nullable Level level) {
        TrainNextSignals.Look look = null;
        if (level != null)
            for (BlockPos head : signal.blockEntities.get(first).keySet()) {
                look = TrainNextSignals.look(level, head);
                if (look != null)
                    break;
            }
        TrainNextSignals.Look[] remembered = REMEMBERED.get(signal);
        int side = first ? 0 : 1;
        if (look != null) {
            if (remembered == null)
                REMEMBERED.put(signal, remembered = new TrainNextSignals.Look[2]);
            remembered[side] = look;
        } else if (remembered != null) {
            look = remembered[side];
        }
        if (look != null)
            return new SignalSide(direction, look.kind(), look.lamps());
        return new SignalSide(direction, SignalLooks.CREATE, SignalLooks.create(signal.cachedStates.get(first).name()));
    }

    /** Where one end of a carriage is, and the track it is on. */
    private record End(Spot spot, int track) {}

    private static @Nullable End end(@Nullable TravellingPoint point, Map<TrackEdge, Integer> trackOf) {
        if (point == null || point.edge == null || point.edge.isInterDimensional() || point.edge.getLength() <= 0)
            return null;
        Integer track = trackOf.get(point.edge);
        if (track == null)
            return null;
        return new End(spot(point.edge.getPosition(null, point.position / point.edge.getLength())), track);
    }
}
