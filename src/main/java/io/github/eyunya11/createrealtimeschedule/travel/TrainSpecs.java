package io.github.eyunya11.createrealtimeschedule.travel;

import com.simibubi.create.content.trains.entity.Train;

/**
 * Where a {@link TrainSpec} comes from in the game. This is the only class of the package that
 * knows about Create; {@link TravelTime}, {@link TrainSpec} and {@link SpeedZone} can be used (and
 * tested) without it.
 */
public final class TrainSpecs {

    private TrainSpecs() {}

    /**
     * The speeds and acceleration this train really has: Create's server config for a train, or its
     * powered values while the train has fuel, as {@code Train.maxSpeed}, {@code maxTurnSpeed} and
     * {@code acceleration} answer for it. Those hand out the config divided by 20 (and by 400 for the
     * acceleration), as Create uses it each tick; this puts the factors back so that the spec is in
     * blocks and seconds again, and {@link TravelTime} divides them in the same way.
     */
    public static TrainSpec of(Train train) {
        return new TrainSpec(train.maxSpeed() * 20.0, train.maxTurnSpeed() * 20.0, train.acceleration() * 400.0);
    }
}
