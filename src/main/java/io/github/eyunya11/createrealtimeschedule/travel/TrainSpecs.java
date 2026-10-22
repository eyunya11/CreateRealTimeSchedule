package io.github.eyunya11.createrealtimeschedule.travel;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.infrastructure.config.AllConfigs;
import com.simibubi.create.infrastructure.config.CTrains;

/**
 * Where a {@link TrainSpec} comes from in the game. This is the only class of the package that
 * knows about Create; {@link TravelTime}, {@link TrainSpec} and {@link SpeedZone} can be used (and
 * tested) without it.
 */
public final class TrainSpecs {

    private TrainSpecs() {}

    /**
     * The speeds and acceleration this train has: Create's server config for a train, or its powered
     * values while the train has fuel, which is what {@code Train.maxSpeed}, {@code maxTurnSpeed} and
     * {@code acceleration} choose between too.
     *
     * <p>The config is read here instead of asking the train, because {@code Train.maxSpeed} is not
     * always the top speed: Create Train Next lowers it to the limit of the last speed sign or signal
     * the train passed. Such a limit belongs to where the train is, not to the train. A sign is not a
     * {@link SpeedZone}, though: Create Train Next brakes for it in its own way and caps the speed
     * before the throttle, and {@link TravelTime} does not take signs yet (see the proposal).
     *
     * @throws IllegalArgumentException if the config sets a speed or the acceleration to nothing
     */
    public static TrainSpec of(Train train) {
        return byConfig(train.fuelTicks > 0);
    }

    /**
     * What a train with fuel has by the server's config.
     *
     * @throws IllegalArgumentException if the config sets a speed or the acceleration to nothing
     */
    public static TrainSpec powered() {
        return byConfig(true);
    }

    /**
     * What a train without fuel has by the server's config, for when there is no train to ask: the
     * export of the network.
     *
     * @throws IllegalArgumentException if the config sets a speed or the acceleration to nothing, which
     *                                  leaves no train that moves
     */
    public static TrainSpec unpowered() {
        return byConfig(false);
    }

    /** Picks each value as {@code Train.maxSpeed}, {@code maxTurnSpeed} and {@code acceleration} do. */
    private static TrainSpec byConfig(boolean powered) {
        CTrains trains = AllConfigs.server().trains;
        return new TrainSpec(
            (powered ? trains.poweredTrainTopSpeed : trains.trainTopSpeed).getF(),
            (powered ? trains.poweredTrainTurningTopSpeed : trains.trainTurningTopSpeed).getF(),
            (powered ? trains.poweredTrainAcceleration : trains.trainAcceleration).getF());
    }
}
