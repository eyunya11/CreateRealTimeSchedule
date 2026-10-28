package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.HashSet;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.entity.TravellingPoint.IEdgePointListener;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.signal.TrackEdgePoint;
import com.simibubi.create.content.trains.station.GlobalStation;

/**
 * Sees a train run through stations without stopping, as {@link Waits} needs: when the end of its
 * last carriage goes past a station, it is past it. Create raises nothing when a train goes past a
 * station it is not bound for, so the end of the train is copied each tick ({@link Tail}), and on the
 * next it is run on again from there, by as far as the train moved, to see what it went past; as
 * Create runs a point on, so that a short piece of track crossed within a tick is not missed.
 */
public final class PassWatch {

    /** Where the end of a train was on the track: a copy, as Create moves its points in place. */
    public record Tail(TrackNode node1, TrackNode node2, TrackEdge edge, double position) {}

    private PassWatch() {}

    /** Whether {@code tail} is still on an edge of {@code graph}: the track it was copied on may have been taken up or laid again since. */
    public static boolean stillOn(Tail tail, @Nullable TrackGraph graph) {
        return graph != null && graph.getConnectionsFrom(tail.node1()).get(tail.node2()) == tail.edge();
    }

    /** The end of {@code train} that goes last: the back of its last carriage, or the front of its first while it runs backwards; null off the track. */
    public static @Nullable Tail tail(Train train) {
        TravellingPoint point = tailPoint(train);
        return point == null || point.edge == null ? null : new Tail(point.node1, point.node2, point.edge, point.position);
    }

    private static @Nullable TravellingPoint tailPoint(Train train) {
        if (train.carriages.isEmpty())
            return null;
        Carriage carriage = train.speed >= 0 ? train.carriages.getLast() : train.carriages.getFirst();
        return train.speed >= 0 ? carriage.getTrailingPoint() : carriage.getLeadingPoint();
    }

    /** Where {@code now}, the end of {@code train}, was on the tick before: back by as far as the train moved. */
    private static Tail behind(TravellingPoint now, Train train) {
        TravellingPoint back = new TravellingPoint(now.node1, now.node2, now.edge, now.position, false);
        back.travel(train.graph, -train.speed, (graph, choices) -> choices.getSecond().getFirst());
        return new Tail(back.node1, back.node2, back.edge, back.position);
    }

    /**
     * The stations of {@code names} the end of {@code train} has gone past since it was at
     * {@code before}, the tick before.
     */
    public static Set<String> passed(@Nullable Tail before, Train train, Set<String> names) {
        Set<String> passed = new HashSet<>();
        TravellingPoint now = tailPoint(train);
        if (now == null || now.edge == null || train.graph == null || names.isEmpty())
            return passed;
        if (before == null)
            before = behind(now, train); // first watched, as after a restart: where it was, by how far it moved
        IEdgePointListener seen = (distance, point) -> {
            if (point.getFirst() instanceof GlobalStation station && names.contains(station.name))
                passed.add(station.name);
            return false;
        };
        TravellingPoint scout = new TravellingPoint(before.node1(), before.node2(), before.edge(), before.position(), false);
        double moved = train.speed;
        scout.travel(train.graph, moved, scout.follow(now), seen);
        // The speed leaves out what Create adds to keep the carriages their length apart: the rest of the
        // way, on the piece of track the end is on now, is looked at as it is.
        if (scout.edge == now.edge && (moved >= 0 ? scout.position < now.position : scout.position > now.position)) {
            double from = Math.min(scout.position, now.position);
            double to = Math.max(scout.position, now.position);
            for (TrackEdgePoint point : now.edge.getEdgeData().getPoints()) {
                if (!(point instanceof GlobalStation station) || !names.contains(station.name))
                    continue;
                double at = point.getLocationOn(now.edge);
                if (moved >= 0 ? at >= from && at < to : at > from && at <= to)
                    passed.add(station.name);
            }
        }
        return passed;
    }
}
