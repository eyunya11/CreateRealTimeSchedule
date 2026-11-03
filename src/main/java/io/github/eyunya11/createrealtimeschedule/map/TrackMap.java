package io.github.eyunya11.createrealtimeschedule.map;

import java.util.List;
import java.util.Objects;

/**
 * The track of the game seen from above, as the editor and the map screens draw it: where each stretch
 * of track runs, and the stations, signals, speed-limit signs and trains on it. It has no Minecraft
 * types in it, so that it can be written and tested without a game; {@code CreateMap} reads one from
 * Create's track graphs.
 *
 * <p>A place is given by the world's X and Z in blocks; the height is left out. A <em>track</em> is one
 * edge of Create's graph, both ways of it at once, as a line through points: two for a straight edge,
 * more along a curve. Everything on the track says which track it is on, by its index in
 * {@link #tracks}, so that a map that shows only some of the track shows only what is on it.
 *
 * @param dimensions  the dimensions the track is in, such as {@code minecraft:overworld}, each once
 * @param tracks      the tracks
 * @param stations    the station markers
 * @param signals     the signals
 * @param speedLimits the speed-limit signs of Create Train Next
 * @param trains      the trains
 */
public record TrackMap(List<String> dimensions, List<Track> tracks, List<StationMark> stations, List<SignalMark> signals,
                       List<SpeedMark> speedLimits, List<TrainMark> trains) {

    /** A map with nothing on it. */
    public static final TrackMap EMPTY = new TrackMap(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    public TrackMap {
        dimensions = List.copyOf(dimensions);
        tracks = List.copyOf(tracks);
        stations = List.copyOf(stations);
        signals = List.copyOf(signals);
        speedLimits = List.copyOf(speedLimits);
        trains = List.copyOf(trains);
    }

    /** A place, or a direction, on the map: the world's X and Z. */
    public record Spot(double x, double z) {}

    /**
     * @param key       what a timetable names the track by ({@link TrackKeys}): the same as long as the
     *                  track's two ends stay where they are
     * @param dimension the index of its dimension in {@link #dimensions}
     * @param points    the line it runs along, from one end to the other; at least two points
     */
    public record Track(String key, int dimension, List<Spot> points) {
        public Track {
            Objects.requireNonNull(key, "key");
            points = List.copyOf(points);
        }
    }

    /**
     * A stretch of a track that track tags are on, as a share of the track's length from the first of
     * its points, either way of it: a one-way tag block's tags count as on the track too.
     *
     * @param from where it starts, from 0 to 1
     * @param to   where it ends, after {@code from}
     * @param tags the names of the tags on all of it, each once, in the order of the world's list
     */
    public record TagPiece(double from, double to, List<String> tags) {
        public TagPiece {
            tags = List.copyOf(tags);
        }
    }

    /**
     * @param name      the station's name
     * @param track     the index of the track its marker is on
     * @param at        where the marker is
     * @param direction the way trains run as they come in to stop there (Create's trains stop at a station
     *                  running one way only); the map screen shows it, the network file does not
     */
    public record StationMark(String name, int track, Spot at, Spot direction) {
        public StationMark {
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * A signal, with what it shows to the trains of each way it has a signal head for.
     *
     * @param id    Create's id of the signal, by which a map screen is told what it shows now
     * @param track the index of the track it is on
     * @param at    where it is
     * @param sides what it shows to each way, one for each way it has a head for
     */
    public record SignalMark(String id, int track, Spot at, List<SignalSide> sides) {
        public SignalMark {
            Objects.requireNonNull(id, "id");
            sides = List.copyOf(sides);
        }
    }

    /**
     * One way of a signal.
     *
     * @param direction the way the trains it is for run, as a vector of length 1
     * @param kind      what kind of signal it is: {@code create} for Create's own, or {@code lightN} for a
     *                  colour light signal of Create Train Next with N aspects
     * @param lamps     the lamps it shows lit, as {@link Lamp}s from the top
     * @param left      whether its block is to the left of the trains it is for, as on a line where trains
     *                  keep left; the map screen puts its head that side first, and the network file does
     *                  not write it
     */
    public record SignalSide(Spot direction, String kind, List<Lamp> lamps, boolean left) {
        public SignalSide {
            Objects.requireNonNull(kind, "kind");
            lamps = List.copyOf(lamps);
        }

        /** A side whose block is to the right of the trains. */
        public SignalSide(Spot direction, String kind, List<Lamp> lamps) {
            this(direction, kind, lamps, false);
        }
    }

    /** The colour of a lit lamp of a signal, which a map shows as one dot. */
    public enum Lamp {
        RED("R"), YELLOW("Y"), GREEN("G");

        private final String letter;

        Lamp(String letter) {
            this.letter = letter;
        }

        /** How the JSON writes it. */
        public String letter() {
            return letter;
        }
    }

    /**
     * A speed-limit sign of Create Train Next.
     *
     * @param track     the index of the track it is on
     * @param at        where it is
     * @param direction the way the trains it holds run, as a vector of length 1
     * @param kmh       the limit in km/h; 0 for a sign that ends a limit
     * @param release   whether it ends a limit instead of setting one
     */
    public record SpeedMark(int track, Spot at, Spot direction, int kmh, boolean release) {}

    /**
     * @param name      what the train is called
     * @param carriages its carriages, each from one end to the other
     * @param tags      the track tags it runs on in the duty it runs, each once, empty for the ways it
     *                  runs on none; none if it runs no duty
     */
    public record TrainMark(String name, List<Carriage> carriages, List<String> tags) {
        public TrainMark {
            Objects.requireNonNull(name, "name");
            carriages = List.copyOf(carriages);
            tags = List.copyOf(tags);
        }

        /** A train that runs no duty. */
        public TrainMark(String name, List<Carriage> carriages) {
            this(name, carriages, List.of());
        }
    }

    /**
     * @param front      where the carriage's front end is
     * @param back       where its back end is
     * @param frontTrack the index of the track the front end is on
     * @param backTrack  the index of the track the back end is on
     */
    public record Carriage(Spot front, Spot back, int frontTrack, int backTrack) {}
}
