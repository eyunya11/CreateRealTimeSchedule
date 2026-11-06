package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;

import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpec;

/**
 * How long the track takes from one station to another, which only the game knows: put behind an
 * interface so that {@link Forecast} and {@link Recovery} can be tested without a game.
 */
@FunctionalInterface
public interface RunTimes {

    /** Nothing is known of the track: the timetable's own running times are taken. */
    RunTimes NONE = (from, to, tag) -> OptionalLong.empty();

    /**
     * The least ticks a train held to the track tag {@code tag} (empty for none) takes on the track
     * from a station named {@code from} to one named {@code to}, with nothing in its way; empty if that
     * is not known.
     */
    OptionalLong ticks(String from, String to, String tag);

    /** {@link #ticks(String, String, String)} for a train held to no track tag. */
    default OptionalLong ticks(String from, String to) {
        return ticks(from, to, "");
    }

    /**
     * {@link #ticks(String, String, String)} for a train that leaves {@code from} under the limit of a
     * speed-limit sign of Create Train Next it passed before, in km/h (empty for none): the limit lasts
     * until the train passes the next sign. Running times that know of no signs answer as for no limit.
     */
    default OptionalLong ticks(String from, String to, String tag, OptionalInt limitAtStart) {
        return ticks(from, to, tag);
    }

    /**
     * The limit of a speed-limit sign a train is under when it stops at {@code to}, having left
     * {@code from} under {@code limitAtStart}: that of the last sign on the way, or the one it left under
     * if there is none. Running times that know of no signs keep the one it left under.
     */
    default OptionalInt limitAtEnd(String from, String to, String tag, OptionalInt limitAtStart) {
        return limitAtStart;
    }

    /**
     * How long the track takes between two stations of {@code legs} (by their two ends and the track
     * tag), for a train of {@code spec} at {@code throttle}, with the speed-limit signs on the way.
     */
    static RunTimes ofLegs(Map<List<String>, Leg> legs, TrainSpec spec, double throttle) {
        return new RunTimes() {
            @Override
            public OptionalLong ticks(String from, String to, String tag) {
                return ticks(from, to, tag, OptionalInt.empty());
            }

            @Override
            public OptionalLong ticks(String from, String to, String tag, OptionalInt limitAtStart) {
                Leg leg = legs.get(List.of(from, to, tag));
                if (leg == null)
                    return OptionalLong.empty();
                OptionalInt ticks = leg.ticks(spec, throttle, limitAtStart);
                return ticks.isPresent() ? OptionalLong.of(ticks.getAsInt()) : OptionalLong.empty();
            }

            @Override
            public OptionalInt limitAtEnd(String from, String to, String tag, OptionalInt limitAtStart) {
                Leg leg = legs.get(List.of(from, to, tag));
                return leg == null ? limitAtStart : leg.limitAtEnd(limitAtStart);
            }
        };
    }

    /** What {@code times} says, worked out once for each two stations, tag and limit and then remembered. */
    static RunTimes remembered(RunTimes times) {
        Map<List<Object>, OptionalLong> known = new HashMap<>();
        return new RunTimes() {
            @Override
            public OptionalLong ticks(String from, String to, String tag) {
                return ticks(from, to, tag, OptionalInt.empty());
            }

            @Override
            public OptionalLong ticks(String from, String to, String tag, OptionalInt limitAtStart) {
                return known.computeIfAbsent(List.of(from, to, tag, limitAtStart), ends -> times.ticks(from, to, tag, limitAtStart));
            }

            @Override
            public OptionalInt limitAtEnd(String from, String to, String tag, OptionalInt limitAtStart) {
                return times.limitAtEnd(from, to, tag, limitAtStart);
            }
        };
    }

    /**
     * The least that any of {@code times} says, between each two stations: the fastest of them; empty
     * where none knows. They are taken to be of the same track, whose signs are the same for every
     * train, so the limit at the end is the first one's.
     */
    static RunTimes fastest(List<RunTimes> times) {
        List<RunTimes> each = List.copyOf(times);
        return new RunTimes() {
            @Override
            public OptionalLong ticks(String from, String to, String tag) {
                return ticks(from, to, tag, OptionalInt.empty());
            }

            @Override
            public OptionalLong ticks(String from, String to, String tag, OptionalInt limitAtStart) {
                return each.stream().map(one -> one.ticks(from, to, tag, limitAtStart)).filter(OptionalLong::isPresent)
                    .mapToLong(OptionalLong::getAsLong).min();
            }

            @Override
            public OptionalInt limitAtEnd(String from, String to, String tag, OptionalInt limitAtStart) {
                return each.isEmpty() ? limitAtStart : each.getFirst().limitAtEnd(from, to, tag, limitAtStart);
            }
        };
    }
}
