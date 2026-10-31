package io.github.eyunya11.createrealtimeschedule.railway;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.IntPredicate;

import org.jetbrains.annotations.Nullable;

import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Edge;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Point;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Station;

/**
 * The way Create sends a train to a station ({@code Navigation.search} and {@code findPathTo}), worked
 * out on a {@link RailNetwork}, with no Minecraft or Create types in it so that it can be tested on
 * small networks written out by hand. The operation sends its trains on the ways found here, and the
 * running times are worked out along the same ways, so that the two agree.
 *
 * <p>A way is weighed by its length and by what it passes, as Create weighs it: a station passed that
 * the train does not stand at, a station with another train at it, a signal held red, a signal block
 * with a train in it, and the edges another train is on ({@link Weights}). The search goes along the
 * edges by least length and weight, and along the points of an edge in order: a point that does not
 * let the train through ends the way there, and the stations before it can still be stopped at. On
 * the edge the train starts on, only what lies ahead of it counts, and a station there is taken at
 * once; that edge is not come back to. A station further on is taken when the search comes to the
 * edge it is on, by the length to the end of that edge and the weight so far, as Create's does.
 *
 * <p>Create's search orders its way by an estimate of what is left as well (A*), which can make it
 * take another way where two are close; this takes the way of least length and weight.
 */
public final class RouteSearch {

    /** What Create adds for a station passed that the train does not stand at ({@code Train.Penalties.STATION}). */
    static final int STATION = 50;
    /** What it adds for a station with another train at it ({@code STATION_WITH_TRAIN}). */
    static final int STATION_WITH_TRAIN = 300;
    /** What it adds for a signal held red by redstone ({@code REDSTONE_RED_SIGNAL}). */
    static final int SIGNAL_HELD_RED = 400;
    /** The least it adds for a signal block with a train in it ({@code RED_SIGNAL}). */
    static final int RED_SIGNAL = 25;

    private RouteSearch() {}

    /** Whose train stands at a station marker. */
    public enum Presence {
        NONE,
        /** The train being sent: passing its own station costs nothing. */
        OWN,
        OTHER
    }

    /** What a signal that lets a train through says to it. */
    public enum SignalState {
        CLEAR,
        /** The block beyond it has a train in it. */
        OCCUPIED,
        /** It is held red by redstone. */
        HELD_RED
    }

    /**
     * What weighs a way besides the track: what the game is like when the train is sent. The defaults
     * are an empty railway, with no train anywhere and every signal clear.
     */
    public interface Weights {

        /** What another train on {@code edge} adds to a way along it. */
        default int edge(int edge) {
            return 0;
        }

        /** Whose train stands at the station marker numbered {@code platform} ({@link RailNetwork#platformOf}). */
        default Presence presence(int platform) {
            return Presence.NONE;
        }

        /** What the signal numbered {@code signal} ({@link Point#ref}) says. */
        default SignalState signal(int signal) {
            return SignalState.CLEAR;
        }

        /** Whether the train can run on {@code edge} at all: whether its bogeys take that kind of track. */
        default boolean canUse(int edge) {
            return true;
        }

        /**
         * What the first signal block with a train in it adds; each one after adds half of what the one
         * before did. Create starts at twice the ticks the train has waited at a signal, from 25 to 200.
         */
        default int firstSignalWeight() {
            return RED_SIGNAL;
        }
    }

    /** Where a train is: on {@code edge}, {@code offset} from its start, facing the way the edge runs. */
    public record Start(int edge, double offset) {}

    /**
     * A way to a station.
     *
     * @param forward   whether the train runs forward along it, or backs up
     * @param station   the index of the station it ends at
     * @param distance  its length, from the train to the station's marker
     * @param cost      what Create counts it as: the weight of the way, and for a station on the edge the
     *                  train starts on, its length to the end of that edge too ({@code DiscoveredPath.cost})
     * @param edges     the edges it runs along, from the one the train is on to the one the station is on
     * @param junctions the edges of {@code edges} that the train takes at a junction, where the track
     *                  goes on more than one way: what steers it ({@code DiscoveredPath.path})
     */
    public record Route(boolean forward, int station, double distance, double cost, List<Integer> edges, List<Integer> junctions) {
        public Route {
            edges = List.copyOf(edges);
            junctions = List.copyOf(junctions);
        }

        /** The length, less than 0 when the train backs up, as {@code DiscoveredPath.distance} has it. */
        public double signedDistance() {
            return forward ? distance : -distance;
        }

        Route backwards() {
            return new Route(false, station, distance, cost, edges, junctions);
        }
    }

    /**
     * The way a train is sent to one of the stations that {@code destination} takes, as Create's
     * {@code findPathTo} chooses it: forward from {@code front}, the front of the train, and backing up
     * from {@code back}, the back of it on the edge the other way; of the two it can drive, the one of
     * less cost, the backing up where they are even. Null for a direction that is not to be searched.
     *
     * @return the way, or null if there is none the train can drive
     */
    public static @Nullable Route find(RailNetwork network, @Nullable Start front, @Nullable Start back, boolean canDriveForward,
                                       boolean canDriveBackward, IntPredicate destination, Weights weights) {
        Route forward = front == null ? null : first(network, front, destination, weights);
        Route backward = back == null ? null : first(network, back, destination, weights);
        if (backward != null)
            backward = backward.backwards();
        if (backward == null || !canDriveBackward)
            return canDriveForward ? forward : null;
        if (forward == null || !canDriveForward)
            return backward;
        return backward.cost() > forward.cost() ? forward : backward;
    }

    /** The first station that {@code destination} takes that the search from {@code start} comes to, or null if it comes to none. */
    public static @Nullable Route first(RailNetwork network, Start start, IntPredicate destination, Weights weights) {
        Route[] found = new Route[1];
        new Search(network, start, destination, weights, true).run(route -> {
            found[0] = route;
            return true;
        });
        return found[0];
    }

    /**
     * The way from {@code start} to each name of the stations {@code destination} takes: to the first
     * station of that name the search comes to, which is where a train sent to that name would go.
     */
    public static Map<String, Route> toEveryName(RailNetwork network, Start start, IntPredicate destination, Weights weights) {
        Map<String, Route> found = new HashMap<>();
        new Search(network, start, destination, weights, false).run(route -> {
            found.putIfAbsent(network.stations().get(route.station()).name(), route);
            return false;
        });
        return found;
    }

    /** One search from a place. */
    private static final class Search {

        /** What is still to be looked at: an edge to go on from, or a station that can be stopped at. */
        private record Entry(double distance, int penalty, int edge, int station, double stationDistance, long order) {
            double key() {
                return distance + penalty;
            }
        }

        private interface Finder {
            /** True to stop. */
            boolean found(Route route);
        }

        private final RailNetwork network;
        private final Start start;
        private final IntPredicate destination;
        private final Weights weights;
        /** Whether only the first station is wanted, so that the edge it is on is done with, as Create's search is. */
        private final boolean once;
        private final int[] reachedVia;
        private final boolean[] atJunction;
        private final boolean[] visited;
        private final PriorityQueue<Entry> frontier = new PriorityQueue<>(
            Comparator.comparingDouble(Entry::key).thenComparingLong(Entry::order));
        private long order;
        private int signalWeight;

        Search(RailNetwork network, Start start, IntPredicate destination, Weights weights, boolean once) {
            this.network = network;
            this.start = start;
            this.destination = destination;
            this.weights = weights;
            this.once = once;
            int count = network.edges().size();
            reachedVia = new int[count];
            Arrays.fill(reachedVia, -1);
            atJunction = new boolean[count];
            visited = new boolean[count];
            signalWeight = Math.max(RED_SIGNAL, Math.min(200, weights.firstSignalWeight()));
        }

        void run(Finder finder) {
            Edge first = network.edges().get(start.edge());
            double toEnd = first.length() - start.offset();
            int penalty = weights.edge(start.edge());
            // The edge the train is on: only what lies ahead of it, and a station there is taken at once.
            for (Item item : items(start.edge())) {
                if (item.position() < start.offset())
                    continue;
                if (item.kind() == Item.Kind.BARRIER)
                    return;
                if (item.kind() == Item.Kind.SIGNAL) {
                    penalty += signal(item.ref());
                    continue;
                }
                Presence presence = weights.presence(item.ref());
                if (presence == Presence.OTHER)
                    penalty += STATION_WITH_TRAIN;
                if (item.station() >= 0 && destination.test(item.station())) {
                    Route route = new Route(true, item.station(), item.position() - start.offset(), toEnd + penalty,
                        List.of(start.edge()), List.of());
                    if (finder.found(route))
                        return;
                }
                if (presence != Presence.OWN)
                    penalty += STATION;
            }
            push(new Entry(toEnd, penalty, start.edge(), -1, 0, order++));

            while (!frontier.isEmpty()) {
                Entry entry = frontier.poll();
                if (entry.station() >= 0) {
                    // An edge already gone along is not come back to, as the train's own is not: Create's
                    // search skips it, the station on it too. Looking for every name, the edge is still to
                    // be gone along from, after its stations.
                    if (visited[entry.edge()])
                        continue;
                    if (once)
                        visited[entry.edge()] = true;
                    if (finder.found(route(entry)))
                        return;
                    continue;
                }
                if (visited[entry.edge()])
                    continue;
                visited[entry.edge()] = true;
                List<Integer> next = network.edges().get(entry.edge()).next();
                for (int target : next)
                    goOn(entry, target, next.size() > 1);
            }
        }

        /** Looks at the way from the end of {@code from} on along {@code target}, as Create does for each way a junction offers. */
        private void goOn(Entry from, int target, boolean junction) {
            if (!weights.canUse(target))
                return;
            Edge edge = network.edges().get(target);
            int penalty = from.penalty() + weights.edge(target);
            double distance = from.distance() + edge.length();
            // Coming round to the node the train's own edge starts at, Create leaves out the points of the
            // way on that lie as far along it as the train stands back from the end of its own edge.
            Edge own = network.edges().get(start.edge());
            double skipBefore = own.start() >= 0 && edge.start() == own.start()
                ? edge.length() - (own.length() - start.offset())
                : Double.NEGATIVE_INFINITY;
            List<Entry> stations = new ArrayList<>();
            boolean blocked = false;
            for (Item item : items(target)) {
                if (item.position() < skipBefore)
                    continue;
                if (item.kind() == Item.Kind.BARRIER) {
                    blocked = true;
                    break;
                }
                if (item.kind() == Item.Kind.SIGNAL) {
                    penalty += signal(item.ref());
                    continue;
                }
                Presence presence = weights.presence(item.ref());
                if (presence == Presence.OTHER)
                    penalty += STATION_WITH_TRAIN;
                if (item.station() >= 0 && destination.test(item.station())) {
                    stations.add(new Entry(distance, penalty, target, item.station(), distance - edge.length() + item.position(), order++));
                    if (once)
                        break; // Create's search takes this edge as the way to the station, and looks no further along it
                }
                if (presence != Presence.OWN)
                    penalty += STATION;
            }
            if (blocked && stations.isEmpty())
                return;
            if (reachedVia[target] < 0 && target != start.edge()) {
                reachedVia[target] = from.edge();
                atJunction[target] = junction;
            }
            stations.forEach(this::push);
            if (!blocked && !(once && !stations.isEmpty()))
                push(new Entry(distance, penalty, target, -1, 0, order++));
        }

        /** What a signal adds; one with a train in its block adds less each time, as Create's search counts them. */
        private int signal(int ref) {
            return switch (weights.signal(ref)) {
                case HELD_RED -> SIGNAL_HELD_RED;
                case OCCUPIED -> {
                    int weight = signalWeight;
                    signalWeight /= 2;
                    yield weight;
                }
                case CLEAR -> 0;
            };
        }

        private void push(Entry entry) {
            frontier.add(entry);
        }

        private Route route(Entry entry) {
            List<Integer> edges = new ArrayList<>();
            List<Integer> junctions = new ArrayList<>();
            for (int edge = entry.edge(); ; edge = reachedVia[edge]) {
                edges.add(edge);
                if (edge == start.edge())
                    break;
                if (atJunction[edge])
                    junctions.add(edge);
                if (reachedVia[edge] < 0)
                    throw new IllegalStateException("edge " + edge + " was reached from nowhere");
            }
            Collections.reverse(edges);
            Collections.reverse(junctions);
            return new Route(true, entry.station(), entry.stationDistance(), entry.penalty(), edges, junctions);
        }

        /** The points on an edge that the search looks at, in order. */
        private List<Item> items(int edge) {
            return network.items(edge);
        }
    }

    /**
     * A point on an edge as the search meets it.
     *
     * @param station for a station marker, the index of the station a train running along the edge can
     *                stop at, or -1 if it cannot stop there
     * @param ref     for a station marker, its platform number; for a signal, the signal's number
     */
    record Item(double position, Kind kind, int station, int ref) {
        enum Kind {
            /** A point that does not let the train through. */
            BARRIER,
            STATION,
            SIGNAL
        }

        /** The points of {@code edge} of {@code network}, in order; a barrier comes first of points at one place. */
        static List<Item> of(RailNetwork network, int edge) {
            Edge e = network.edges().get(edge);
            List<Item> items = new ArrayList<>();
            for (double barrier : e.barriers())
                items.add(new Item(barrier, Kind.BARRIER, -1, -1));
            for (int station : network.stationsOn(edge)) {
                Station s = network.stations().get(station);
                items.add(new Item(s.offset(), Kind.STATION, station, network.platformOf(station)));
            }
            for (Point point : e.points())
                items.add(point.kind() == Point.Kind.STATION
                    ? new Item(point.position(), Kind.STATION, -1, point.ref())
                    : new Item(point.position(), Kind.SIGNAL, -1, point.ref()));
            items.sort(Comparator.comparingDouble(Item::position).thenComparingInt(item -> item.kind().ordinal()));
            return List.copyOf(items);
        }
    }
}
