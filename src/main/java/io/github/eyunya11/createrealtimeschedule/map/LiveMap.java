package io.github.eyunya11.createrealtimeschedule.map;

import java.util.List;
import java.util.Objects;

import io.github.eyunya11.createrealtimeschedule.map.TrackMap.SignalSide;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Spot;

/**
 * What changes on a map from one moment to the next, as the server sends it to the map screens of a
 * dimension: what the signals show, and where the trains are. A client reads the track itself from
 * the track graphs Create sends it, but Create sends it neither the signals' heads nor what they show,
 * and the places of the trains only for those near. Things are on tracks by the tracks' keys
 * ({@link TrackKeys}), which the server and the client work out alike.
 */
public record LiveMap(List<Signal> signals, List<Train> trains) {

    public static final LiveMap EMPTY = new LiveMap(List.of(), List.of());

    public LiveMap {
        signals = List.copyOf(signals);
        trains = List.copyOf(trains);
    }

    /**
     * @param track the key of the track it is on
     * @param sides what it shows to each way it has a head for
     */
    public record Signal(String track, Spot at, List<SignalSide> sides) {
        public Signal {
            Objects.requireNonNull(track, "track");
            sides = List.copyOf(sides);
        }
    }

    /**
     * @param tags the track tags it runs on in the duty it runs, empty for the ways on none; none if it
     *             runs no duty
     */
    public record Train(String name, List<Carriage> carriages, List<String> tags) {
        public Train {
            Objects.requireNonNull(name, "name");
            carriages = List.copyOf(carriages);
            tags = List.copyOf(tags);
        }

        /** A train that runs no duty. */
        public Train(String name, List<Carriage> carriages) {
            this(name, carriages, List.of());
        }
    }

    /** A carriage from its front end to its back end, and the keys of the tracks they are on. */
    public record Carriage(Spot front, Spot back, String frontTrack, String backTrack) {}
}
