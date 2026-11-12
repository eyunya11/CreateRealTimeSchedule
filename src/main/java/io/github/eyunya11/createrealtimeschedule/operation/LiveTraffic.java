package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import io.github.eyunya11.createrealtimeschedule.railway.SignalBlocks;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Train;
import io.github.eyunya11.createrealtimeschedule.traffic.Traffic;
import io.github.eyunya11.createrealtimeschedule.traffic.Traffic.Run;
import io.github.eyunya11.createrealtimeschedule.traffic.Traffic.Stint;
import io.github.eyunya11.createrealtimeschedule.traffic.Traffic.Trip;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpec;

/**
 * The sets of the duties running now, run on from where they are with the signals between them
 * ({@link Traffic}), for an hour ({@link #HORIZON_TICKS}): what the signals add to each of their ways,
 * late as they are, where {@link TimetableTraffic} has the trains on time. It has no Minecraft types in
 * it so that it can be tested without a game.
 *
 * <p>A set that stands at a call leaves it when it is to, or now if that has gone by; one on its way
 * left the call before when it did, and is put on the line from there as if nothing had held it up
 * since. From there each runs the calls of its duty that are left, at its own speed and throttle, and
 * leaves each at the timetable's time, or after its minimum stop if it comes in late, as a set does.
 * It starts under no limit of a sign or a signal, as a train of the timetable does. A duty that is to
 * start within the hour starts on time, at the speed the trains on time run at. A set that falls ten
 * minutes behind the delay it starts with is taken off the line. The trains that run no duty, and the
 * sets that stand at their depots, are not on the line: what they do is not known. Nor are the waits
 * for other trains: a set leaves without them.
 */
public final class LiveTraffic {

    /** How far ahead the sets are run, in ticks: an hour. */
    public static final long HORIZON_TICKS = 20L * 60 * 60;

    /** A way of a set: from call {@code call} of duty {@code duty} on service {@code day} to the next call. */
    public record Key(String duty, long day, int call) {}

    /**
     * A set running a duty now.
     *
     * @param state    how far it has got
     * @param calls    the calls of its duty
     * @param spec     how fast it goes
     * @param throttle the share of its top speed it runs at
     */
    public record Running(DutyRun.State state, List<Call> calls, TrainSpec spec, double throttle) {
        public Running {
            calls = List.copyOf(calls);
        }
    }

    /** A duty that is to start on service {@code day} within the hour and has no set yet, with its calls. */
    public record Coming(String duty, long day, List<Call> calls) {
        public Coming {
            calls = List.copyOf(calls);
        }
    }

    /** Nothing known: the signals are taken as {@link TimetableTraffic} has them on every way. */
    public static final LiveTraffic NONE = new LiveTraffic(Map.of(), Long.MIN_VALUE, true);

    private final Map<Key, Trip> trips;
    private final long from;
    private final boolean finished;

    private LiveTraffic(Map<Key, Trip> trips, long from, boolean finished) {
        this.trips = trips;
        this.from = from;
        this.finished = finished;
    }

    /**
     * Runs the sets of {@code running} on from where they are at {@code now}, with the duties of
     * {@code coming}, up to {@link #HORIZON_TICKS} after it.
     *
     * @param legs               the ways between stations, by their two ends and the track tag, as
     *                           {@link TrackFacts#legs} has them
     * @param blocks             the signal blocks of the network the legs are of
     * @param spec               how fast the sets of the duties to come go
     * @param defaultMinimumStop the minimum stop, in ticks, where the timetable gives none
     * @param defaultLength      the length of a train, in blocks, where the timetable gives none
     */
    public static LiveTraffic of(Timetable timetable, List<Running> running, List<Coming> coming, Map<List<String>, Leg> legs,
        SignalBlocks blocks, TrainSpec spec, long defaultMinimumStop, int defaultLength, long now) {
        Map<String, Train> byNumber = TimetableTraffic.byNumber(timetable);
        List<Run> runs = new ArrayList<>();
        List<List<Key>> keys = new ArrayList<>();
        for (Running set : running) {
            DutyRun.State state = set.state();
            List<Call> calls = set.calls();
            int from;
            long left;
            switch (state.phase()) {
                case STANDING, DEPARTING -> {
                    from = state.call();
                    left = Math.max(state.departure(), now);
                }
                case RUNNING -> {
                    from = previousMade(state);
                    left = from < 0 ? DutyRun.NOT_YET : state.departures().get(from);
                }
                default -> {
                    continue;
                }
            }
            if (from < 0 || left == DutyRun.NOT_YET || !(set.throttle() > 0) || set.throttle() > 1)
                continue;
            add(runs, keys, state.duty(), state.day(), calls, from, left - DutyPlan.at(state.day(), calls.get(from).depart()), set.spec(),
                set.throttle(), legs, byNumber, defaultMinimumStop, defaultLength);
        }
        for (Coming duty : coming)
            add(runs, keys, duty.duty(), duty.day(), duty.calls(), 0, 0, spec, 1, legs, byNumber, defaultMinimumStop, defaultLength);
        Traffic.Result result = Traffic.run(blocks, runs, now + HORIZON_TICKS);
        if (!result.finished())
            return new LiveTraffic(Map.of(), now, false);
        Map<Key, Trip> trips = new HashMap<>();
        for (int run = 0; run < runs.size(); run++) {
            List<Trip> ran = result.trips().get(run);
            for (int i = 0; i < ran.size(); i++)
                trips.put(keys.get(run).get(i), ran.get(i));
        }
        return new LiveTraffic(trips, now, true);
    }

    /** The last call before the one the set of {@code state} runs to that it makes; -1 if there is none. */
    private static int previousMade(DutyRun.State state) {
        Set<Integer> skipped = new HashSet<>(state.skipped());
        int previous = state.call() - 1;
        while (previous >= 0 && skipped.contains(previous))
            previous--;
        return previous;
    }

    private static void add(List<Run> runs, List<List<Key>> keys, String duty, long day, List<Call> calls, int from, long lateBy,
        TrainSpec spec, double throttle, Map<List<String>, Leg> legs, Map<String, Train> byNumber, long defaultMinimumStop,
        int defaultLength) {
        List<Stint> stints = TimetableTraffic.stints(calls, from, day, legs, byNumber, defaultMinimumStop, defaultLength);
        if (stints.isEmpty())
            return;
        List<Key> legKeys = new ArrayList<>();
        for (int i = 0; i < stints.size(); i++)
            legKeys.add(new Key(duty, day, from + i));
        runs.add(new Run(duty + " of day " + day, spec, throttle, stints, from + stints.size() == calls.size() - 1, lateBy));
        keys.add(legKeys);
    }

    /** The clock value the sets were run on from; {@link Long#MIN_VALUE} for {@link #NONE}. */
    public long from() {
        return from;
    }

    /**
     * Whether the simulation ran to its end ({@link Traffic.Result#finished}); if not, it says nothing of
     * any way.
     */
    public boolean finished() {
        return finished;
    }

    /** How the way {@code key} went, if the set set out on it within the hour. */
    public Optional<Trip> trip(Key key) {
        return Optional.ofNullable(trips.get(key));
    }

    /**
     * The ticks the signals add to the way {@code key}, waits at red and all ({@link Trip#signalTicks});
     * empty where the set did not get to the end of it within the hour, as one taken off the line, or on
     * its way at the end of the hour, does not, or for a way it cannot finish on an empty line.
     */
    public OptionalLong signalTicks(Key key) {
        Trip trip = trips.get(key);
        if (trip == null || !trip.hasArrived() || trip.free() < 0)
            return OptionalLong.empty();
        return OptionalLong.of(Math.max(0, trip.signalTicks()));
    }

    /**
     * {@code times} for the set of duty {@code duty} on service {@code day}, whose calls are
     * {@code calls}: with what the signals add to its ways as this has them, and as {@code times} has
     * them where this says nothing.
     */
    public RunTimes on(RunTimes times, String duty, long day, List<Call> calls) {
        return new RunTimes() {
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
                for (int call = 0; call + 1 < calls.size(); call++) {
                    if (!calls.get(call).leavingTrain().equals(train))
                        continue;
                    // The first call the set leaves as the train, and its stop from there.
                    int way = call + stop;
                    if (way + 1 < calls.size()) {
                        OptionalLong live = LiveTraffic.this.signalTicks(new Key(duty, day, way));
                        if (live.isPresent())
                            return live.getAsLong();
                    }
                    break;
                }
                return times.signalTicks(train, stop);
            }
        };
    }
}
