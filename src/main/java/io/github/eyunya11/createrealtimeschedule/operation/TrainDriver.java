package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.ArrayList;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.station.GlobalStation;

/**
 * Takes a Create train through a {@link DutyRun}, by Create's own path finding and driving and not by
 * a schedule: when the run says the set is to leave, the train is sent to the next station; when it
 * has really left the platform, the run is told so; and when it stands at the next station, the run is
 * told it has arrived.
 *
 * <p>A train is sent the way Create's destination instruction sends one: to whichever station of the
 * name it finds the best path to ({@code Navigation.findPathTo} over all of them, which keeps away from
 * platforms with a train at them), by {@code Navigation.startNavigation}. Create then drives it, and
 * leaves the station itself once the train moves, which is on a later tick, and later still when the
 * signal ahead is not yet green: that is when the run records the departure. Create cancels a journey
 * when the train loses its driver or overshoots; the train is then sent again.
 */
public final class TrainDriver {

    /** What came of one step. */
    public enum Outcome {
        /** Standing until it is time to leave, or on the way. */
        GOING,
        /** It is time to leave, or the train has lost its way, and there is no path to the next station. */
        NO_WAY,
        /** The set has arrived at the last call. */
        FINISHED
    }

    private TrainDriver() {}

    /**
     * Why the operation is to let go of a train, or null if it may go on with it: a train that is gone,
     * that has come off the track, or that a player has given a schedule of Create's to follow instead.
     */
    public static @Nullable String whyLetGo(@Nullable Train train) {
        if (train == null)
            return "the train is gone";
        if (train.derailed)
            return "the train has derailed";
        if (train.runtime.getSchedule() != null)
            return "the train has been given a schedule";
        return null;
    }

    /** One tick of the run on {@code train}, at clock {@code now}; {@code minimumStop}, in ticks, is for a call the timetable gives none. */
    public static Outcome step(DutyRun run, Train train, long now, long minimumStop) {
        switch (run.phase()) {
            case FINISHED:
                return Outcome.FINISHED;
            case STANDING:
                if (!run.dueToLeave(now))
                    return Outcome.GOING;
                if (!sendTo(train, run.nextStation()))
                    return Outcome.NO_WAY;
                run.sent();
                return Outcome.GOING;
            case DEPARTING:
                if (train.getCurrentStation() == null) {
                    run.left(now);
                    return Outcome.GOING;
                }
                // Still at the platform: held by a signal, or the journey was cancelled before it moved.
                if (train.navigation.destination != null)
                    return Outcome.GOING;
                return sendTo(train, run.nextStation()) ? Outcome.GOING : Outcome.NO_WAY;
            case RUNNING:
            default:
                if (train.navigation.destination != null)
                    return Outcome.GOING;
                if (standsAt(train, run.nextStation())) {
                    run.arrived(now, minimumStop);
                    return run.finished() ? Outcome.FINISHED : Outcome.GOING;
                }
                return sendTo(train, run.nextStation()) ? Outcome.GOING : Outcome.NO_WAY;
        }
    }

    /** Whether the train stands at a station of that name, and is not on its way anywhere. */
    public static boolean standsAt(Train train, String station) {
        if (train.navigation.destination != null)
            return false;
        GlobalStation at = train.getCurrentStation();
        return at != null && station.equals(at.name);
    }

    /** Sends the train to the station of that name it has the best path to; false if it has none. */
    static boolean sendTo(Train train, String station) {
        if (train.graph == null)
            return false;
        ArrayList<GlobalStation> stations = new ArrayList<>();
        for (GlobalStation candidate : train.graph.getPoints(EdgePointType.STATION))
            if (station.equals(candidate.name))
                stations.add(candidate);
        if (stations.isEmpty())
            return false;
        DiscoveredPath path = train.navigation.findPathTo(stations, Double.MAX_VALUE);
        if (path == null || path.destination == train.getCurrentStation())
            return false;
        return train.navigation.startNavigation(path) >= 0;
    }
}
