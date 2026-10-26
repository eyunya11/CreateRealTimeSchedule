package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.station.GlobalStation;

import io.github.eyunya11.createrealtimeschedule.railway.Leg;
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
     * at its own speed and throttle; empty if Create's config leaves the train no speed.
     */
    public static Optional<CreateReach> of(Train train, Map<List<String>, Leg> legs) {
        TrainSpec spec;
        try {
            spec = TrainSpecs.of(train);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return Optional.of(new CreateReach(train, spec, TrackFacts.runTimes(legs, spec, train.throttle)));
    }

    @Override
    public OptionalLong ticks(String from, String to) {
        return times.ticks(from, to);
    }

    @Override
    public int platforms(String station) {
        if (train.graph == null)
            return 0;
        int platforms = 0;
        for (GlobalStation each : train.graph.getPoints(EdgePointType.STATION))
            if (station.equals(each.name))
                platforms++;
        return platforms;
    }

    @Override
    public int standing(String station) {
        int standing = 0;
        for (Train other : Create.RAILWAYS.trains.values()) {
            if (other == train || other.graph != train.graph)
                continue;
            GlobalStation at = other.getCurrentStation();
            if (at != null && station.equals(at.name))
                standing++;
        }
        return standing;
    }

    /**
     * The way Create would send the set, as {@link TrainDriver} does, as long as a train of its speed
     * takes for it on straight track: the path search gives the way's length but not its curves, so
     * this is the least it can take.
     */
    @Override
    public OptionalLong ticksTo(String station) {
        DiscoveredPath path = TrainDriver.pathTo(train, station);
        if (path == null)
            return OptionalLong.empty();
        OptionalInt ticks = TravelTime.ticks(spec, train.throttle, Math.abs(path.distance), List.of());
        return ticks.isPresent() ? OptionalLong.of(ticks.getAsInt()) : OptionalLong.empty();
    }
}
