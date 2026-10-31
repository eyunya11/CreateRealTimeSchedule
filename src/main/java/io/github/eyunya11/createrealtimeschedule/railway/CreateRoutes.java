package io.github.eyunya11.createrealtimeschedule.railway;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.signal.SignalEdgeGroup;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.track.TrackMaterial;

import io.github.eyunya11.createrealtimeschedule.railway.CreateNetwork.Mapped;
import io.github.eyunya11.createrealtimeschedule.railway.CreateNetwork.SignalSide;
import io.github.eyunya11.createrealtimeschedule.railway.RouteSearch.Presence;
import io.github.eyunya11.createrealtimeschedule.railway.RouteSearch.Route;
import io.github.eyunya11.createrealtimeschedule.railway.RouteSearch.SignalState;
import io.github.eyunya11.createrealtimeschedule.railway.RouteSearch.Start;
import net.createmod.catnip.data.Couple;
import net.minecraft.util.Mth;

/**
 * Finds a train's way with {@link RouteSearch}, on its track graph as it is now, and gives it as the
 * {@code DiscoveredPath} that Create's {@code Navigation.startNavigation} drives a train along: the
 * way Create's own {@code Navigation.findPathTo} finds, weighed with what the game is like at the time
 * (the trains on the track and at the stations, and the signals).
 *
 * <p>A graph is read once and used again until its fingerprint changes. This keeps to the server's
 * thread, as the operation does.
 */
public final class CreateRoutes {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<TrackGraph, Mapped> READ = new WeakHashMap<>();

    /**
     * {@code CarriageBogey.type}, which Create does not make public: the kind of bogey, which says what
     * kinds of track it runs on. Null if it cannot be got at.
     */
    private static final @Nullable Field BOGEY_TYPE = bogeyType();

    private CreateRoutes() {}

    private static @Nullable Field bogeyType() {
        try {
            Field field = CarriageBogey.class.getDeclaredField("type");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Cannot read the kind of a train's bogeys; every kind of track is taken to be one it runs on", e);
            return null;
        }
    }

    /** The kinds of track {@code bogey} runs on, as Create's search asks; null if they cannot be read. */
    private static @Nullable Set<TrackMaterial.TrackType> types(CarriageBogey bogey) {
        if (BOGEY_TYPE == null)
            return null;
        try {
            AbstractBogeyBlock<?> type = (AbstractBogeyBlock<?>) BOGEY_TYPE.get(bogey);
            return type.getValidPathfindingTypes(bogey.getStyle());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** {@code graph} as it is now: read again if it has changed since it was last read. */
    static Mapped mapped(TrackGraph graph) {
        long print = CreateNetwork.fingerprint(List.of(graph));
        Mapped mapped = READ.get(graph);
        if (mapped == null || mapped.fingerprint() != print) {
            mapped = CreateNetwork.readMapped(graph);
            READ.put(graph, mapped);
        }
        return mapped;
    }

    /**
     * The way {@code train} would be sent to one of {@code stations}, as {@code Navigation.findPathTo}
     * finds it with no cap on the cost; null if there is none, or the train is on no graph.
     */
    public static @Nullable DiscoveredPath pathTo(Train train, List<GlobalStation> stations) {
        TrackGraph graph = train.graph;
        if (graph == null || stations.isEmpty())
            return null;
        Mapped mapped = mapped(graph);
        Live live = new Live(train, graph, mapped);
        if (live.runsOnNoTrack())
            return null; // no kind of track every bogey runs on: Create finds no way
        Set<GlobalStation> wanted = Collections.newSetFromMap(new IdentityHashMap<>());
        wanted.addAll(stations);
        RailNetwork network = mapped.network();

        // On the way already, the train is not turned back (as findPathTo does when a destination changes).
        boolean going = train.navigation.destination != null;
        Start front = going && train.navigation.destinationBehindTrain ? null : start(train, mapped, true);
        Start back = going && !train.navigation.destinationBehindTrain ? null : start(train, mapped, false);
        boolean canDriveForward = train.hasForwardConductor() || train.runtime.paused;
        boolean canDriveBackward = train.doubleEnded && train.hasBackwardConductor() || train.runtime.paused;
        Route route = RouteSearch.find(network, front, back, canDriveForward, canDriveBackward,
            station -> wanted.contains(platform(mapped, network.stations().get(station).platform())), live);
        if (route == null)
            return null;
        List<Couple<TrackNode>> path = new ArrayList<>();
        for (int edge : route.junctions()) {
            TrackEdge trackEdge = mapped.edges().get(edge);
            path.add(Couple.create(trackEdge.node1, trackEdge.node2));
        }
        GlobalStation destination = platform(mapped, network.stations().get(route.station()).platform());
        return new DiscoveredPath(route.signedDistance(), route.cost(), path, destination);
    }

    private static @Nullable GlobalStation platform(Mapped mapped, int platform) {
        return platform >= 0 && platform < mapped.platforms().size() ? mapped.platforms().get(platform) : null;
    }

    /**
     * Where the search starts: forward from the front of the train, along the edge it is on; or
     * backing up from its back, along the edge the other way, as findPathTo starts.
     */
    private static @Nullable Start start(Train train, Mapped mapped, boolean forward) {
        TravellingPoint point = forward ? train.carriages.getFirst().getLeadingPoint() : train.carriages.getLast().getTrailingPoint();
        if (point.node1 == null || point.node2 == null)
            return null;
        TrackNode from = forward ? point.node1 : point.node2;
        TrackNode to = forward ? point.node2 : point.node1;
        TrackEdge edge = train.graph.getConnectionsFrom(from).get(to);
        Integer index = edge == null ? null : mapped.index().get(edge);
        if (index == null)
            return null;
        return new Start(index, forward ? point.position : edge.getLength() - point.position);
    }

    /** What weighs a way for {@code train} now. */
    private static final class Live implements RouteSearch.Weights {
        private final Train train;
        private final Mapped mapped;
        private final Map<Integer, Integer> trainsOn = new HashMap<>();
        /** The kinds of track every bogey runs on; null if they could not be read, when any track is taken to do. */
        private @Nullable Set<TrackMaterial.TrackType> validTypes = new HashSet<>();

        Live(Train train, TrackGraph graph, Mapped mapped) {
            this.train = train;
            this.mapped = mapped;
            // As Navigation.search: each other train weighs on the edges its ends are on, both ways.
            for (Train other : Create.RAILWAYS.trains.values()) {
                if (other.graph != graph || other == train)
                    continue;
                int penalty = other.getNavigationPenalty();
                other.getEndpointEdges().forEach(nodes -> {
                    if (nodes.either(node -> node == null))
                        return;
                    for (boolean flip : new boolean[] {true, false}) {
                        TrackEdge edge = graph.getConnection(flip ? nodes.swap() : nodes);
                        Integer index = edge == null ? null : mapped.index().get(edge);
                        if (index != null)
                            trainsOn.merge(index, penalty / 2, Integer::sum);
                    }
                });
            }
            // The kinds of track every bogey of the train can run on.
            for (int i = 0; i < train.carriages.size(); i++) {
                Carriage carriage = train.carriages.get(i);
                Set<TrackMaterial.TrackType> leading = types(carriage.leadingBogey());
                if (leading == null) {
                    validTypes = null;
                    break;
                }
                if (i == 0)
                    validTypes.addAll(leading);
                else
                    validTypes.retainAll(leading);
                if (carriage.isOnTwoBogeys()) {
                    Set<TrackMaterial.TrackType> trailing = types(carriage.trailingBogey());
                    if (trailing == null) {
                        validTypes = null;
                        break;
                    }
                    validTypes.retainAll(trailing);
                }
            }
        }

        /** Whether the train's bogeys run on no kind of track at all, so that Create finds no way for it. */
        boolean runsOnNoTrack() {
            return validTypes != null && validTypes.isEmpty();
        }

        @Override
        public int edge(int edge) {
            return trainsOn.getOrDefault(edge, 0);
        }

        @Override
        public Presence presence(int platform) {
            GlobalStation station = platform(mapped, platform);
            Train present = station == null ? null : station.getPresentTrain();
            return present == null ? Presence.NONE : present == train ? Presence.OWN : Presence.OTHER;
        }

        @Override
        public SignalState signal(int signal) {
            SignalSide side = mapped.signals().get(signal);
            if (side.signal().isForcedRed(side.toward()))
                return SignalState.HELD_RED;
            var group = side.signal().getGroup(side.toward());
            SignalEdgeGroup edgeGroup = group == null ? null : Create.RAILWAYS.signalEdgeGroups.get(group);
            return edgeGroup != null && edgeGroup.isOccupiedUnless(side.signal()) ? SignalState.OCCUPIED : SignalState.CLEAR;
        }

        @Override
        public boolean canUse(int edge) {
            return validTypes == null || validTypes.contains(mapped.edges().get(edge).getTrackMaterial().trackType);
        }

        @Override
        public int firstSignalWeight() {
            return Mth.clamp(train.navigation.ticksWaitingForSignal * 2, RouteSearch.RED_SIGNAL, 200);
        }
    }
}
