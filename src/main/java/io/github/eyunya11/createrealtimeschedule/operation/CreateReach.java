package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.station.GlobalStation;

import io.github.eyunya11.createrealtimeschedule.railway.CreateRoutes;
import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTagData;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpec;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpecs;
import io.github.eyunya11.createrealtimeschedule.travel.TravelTime;

/**
 * What a set can reach on Create's track, and the trains around it, for {@link Recovery}: the part of
 * the recovery that reads the game. Stations and trains are those of the set's own track network; a
 * station of the same name on track the set cannot get to is no platform for it.
 */
public final class CreateReach implements Reach {

    private final Train train;
    private final TrainSpec spec;
    private final RunTimes times;

    private CreateReach(Train train, TrainSpec spec, RunTimes times) {
        this.train = train;
        this.spec = spec;
        this.times = times;
    }

    /**
     * What {@code train} can reach, its running times from {@code legs} (see {@link TrackFacts#legs})
     * at its own speed and throttle, with what the signals add to the ways of the trains of the timetable
     * ({@code traffic}); empty if Create's config leaves the train no speed.
     */
    public static Optional<CreateReach> of(Train train, Map<List<String>, Leg> legs, TimetableTraffic traffic) {
        return of(train, legs, traffic, LiveTraffic.NONE, null);
    }

    /**
     * {@link #of(Train, Map, TimetableTraffic)}, for {@code train} running {@code run}: what the signals
     * add to its ways as {@code live} has them, the sets of the duties run on from where they were, and
     * as {@code traffic} has them where that says nothing.
     */
    public static Optional<CreateReach> of(Train train, Map<List<String>, Leg> legs, TimetableTraffic traffic, LiveTraffic live,
                                           @Nullable DutyRun run) {
        TrainSpec spec;
        try {
            spec = TrainSpecs.of(train);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        RunTimes times = RunTimes.withSignals(TrackFacts.runTimes(legs, spec, train.throttle), traffic);
        return Optional.of(new CreateReach(train, spec, run == null ? times : live.on(times, run.duty(), run.day(), run.calls())));
    }

    @Override
    public OptionalLong ticks(String from, String to, String tag) {
        return times.ticks(from, to, tag);
    }

    @Override
    public OptionalLong ticks(String from, String to, String tag, OptionalInt limitAtStart) {
        return times.ticks(from, to, tag, limitAtStart);
    }

    @Override
    public OptionalInt limitAtEnd(String from, String to, String tag, OptionalInt limitAtStart) {
        return times.limitAtEnd(from, to, tag, limitAtStart);
    }

    @Override
    public long signalTicks(String train, int stop) {
        return times.signalTicks(train, stop);
    }

    @Override
    public int platforms(String station, String tag) {
        if (train.graph == null)
            return 0;
        if (!tag.isEmpty())
            return open(station, tag).size();
        int platforms = 0;
        for (GlobalStation each : train.graph.getPoints(EdgePointType.STATION))
            if (station.equals(each.name))
                platforms++;
        return platforms;
    }

    @Override
    public int standing(String station, String tag) {
        Set<GlobalStation> open = tag.isEmpty() || train.graph == null ? null : open(station, tag);
        int standing = 0;
        for (Train other : Create.RAILWAYS.trains.values()) {
            if (other == train || other.graph != train.graph)
                continue;
            GlobalStation at = other.getCurrentStation();
            if (at != null && station.equals(at.name) && (open == null || open.contains(at)))
                standing++;
        }
        return standing;
    }

    @Override
    public boolean shares(String station, String tag, String other) {
        if (tag.isEmpty() || other.isEmpty() || tag.equals(other) || train.graph == null)
            return true;
        Set<GlobalStation> theirs = open(station, other);
        return open(station, tag).stream().anyMatch(theirs::contains);
    }

    /** The stations of the name on the set's track network that are open to the track tag {@code tag}. */
    private Set<GlobalStation> open(String station, String tag) {
        Set<GlobalStation> found = Collections.newSetFromMap(new IdentityHashMap<>());
        found.addAll(CreateRoutes.platformsOpenTo(train.graph, station, TrackTagData.held(tag)));
        return found;
    }

    /**
     * The way Create would send the set, as {@link TrainDriver} does, as long as a train of its speed
     * takes for it on straight track: the path search gives the way's length but not its curves, so
     * this is the least it can take.
     */
    @Override
    public OptionalLong ticksTo(String station, String tag) {
        DiscoveredPath path = TrainDriver.pathTo(train, station, tag);
        if (path == null)
            return OptionalLong.empty();
        OptionalInt ticks = TravelTime.ticks(spec, train.throttle, Math.abs(path.distance), List.of());
        return ticks.isPresent() ? OptionalLong.of(ticks.getAsInt()) : OptionalLong.empty();
    }
}
