package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.schedule.DeparturePlanner;

/**
 * How far one duty has got on one service day: which call the set is at or running to, when it is to
 * leave, and when it really arrived at and left each call. It has no Minecraft types in it so that it
 * can be tested without a game; what drives the train tells it what happened, and asks it what to do.
 *
 * <p>The set leaves a call at {@code max(T, A + M)}, by the rule of {@link DeparturePlanner}: T is the
 * timetable's departure on this service day, A when the set arrived, and M the minimum stop: the
 * timetable's for the call (the stop's own, or else the station's), or else the server's config. An early
 * set waits for its time, and a late one stops for the minimum and goes, which is how it gets back to
 * the timetable. At the first call, where the set has been waiting at the depot, there is no minimum
 * stop.
 *
 * <p>A set that is too late can be held at a station until a later call of its duty at the same
 * station ({@link #hold}): the calls in between are not made, and the trains that would have made
 * them are cancelled. The set does not leave the call it was held at, nor arrive at the one it
 * leaves from, so neither has a time.
 *
 * <p>Times are clock values. The timetable's times count from 00:00 of the service day, and run on
 * past midnight, so a departure at {@code 24:10} is ten past midnight the day after.
 */
public final class DutyRun {

    /** A time that has not come yet, in {@link #arrivals()} and {@link #departures()}. */
    public static final long NOT_YET = Long.MIN_VALUE;

    public enum Phase {
        /** At {@link #call()}, to leave at {@link #departure()}. */
        STANDING,
        /**
         * Still at {@link #call()}, but sent on to the next: Create holds a train at the platform until
         * the signal ahead lets it go, so it has not left yet.
         */
        DEPARTING,
        /** On the way to {@link #call()}. */
        RUNNING,
        /** At the last call: the duty is done. */
        FINISHED
    }

    /**
     * All of a run, in plain values, to save it and make it again.
     *
     * @param duty       the duty's id
     * @param day        the service day, as {@link RealClock#day} counts days
     * @param call       the index of the call the set is at or running to
     * @param phase      what it is doing
     * @param departure  when it is to leave, while it is {@link Phase#STANDING} or {@link Phase#DEPARTING}
     * @param arrivals   when it arrived at each call, or {@link #NOT_YET}
     * @param departures when it left each call, or {@link #NOT_YET}
     * @param skipped    the calls it does not make, in order (see {@link #hold})
     * @param passes     the stations its set has been seen to run through, in order (see {@link #passed})
     */
    public record State(String duty, long day, int call, Phase phase, long departure, List<Long> arrivals, List<Long> departures,
                        List<Integer> skipped, List<Pass> passes) {
        public State {
            Objects.requireNonNull(duty, "duty");
            Objects.requireNonNull(phase, "phase");
            arrivals = List.copyOf(arrivals);
            departures = List.copyOf(departures);
            skipped = List.copyOf(skipped);
            passes = List.copyOf(passes);
        }

        /** A state of a run that has run through no station it was watched at. */
        public State(String duty, long day, int call, Phase phase, long departure, List<Long> arrivals, List<Long> departures,
                     List<Integer> skipped) {
            this(duty, day, call, phase, departure, arrivals, departures, skipped, List.of());
        }

        /** A state of a run that makes all its calls. */
        public State(String duty, long day, int call, Phase phase, long departure, List<Long> arrivals, List<Long> departures) {
            this(duty, day, call, phase, departure, arrivals, departures, List.of());
        }

        /** A state with the times as arrays, as they are saved. */
        public static State of(String duty, long day, int call, Phase phase, long departure, long[] arrivals, long[] departures) {
            return of(duty, day, call, phase, departure, arrivals, departures, new int[0]);
        }

        /** A state with the times and the calls not made as arrays, as they are saved. */
        public static State of(String duty, long day, int call, Phase phase, long departure, long[] arrivals, long[] departures,
                               int[] skipped) {
            return new State(duty, day, call, phase, departure, boxed(arrivals), boxed(departures), Arrays.stream(skipped).boxed().toList());
        }
    }

    private final String duty;
    private final long day;
    private final List<Call> calls;
    private int call;
    private Phase phase;
    private long departure;
    private final long[] arrivals;
    private final long[] departures;
    private final boolean[] skipped;
    private final List<Pass> passes = new ArrayList<>();

    /**
     * The set ran through a station without stopping, its last carriage gone past it: on the way from
     * call {@code way} to the next, at clock {@code at}. Only stations a train waits for the set to run
     * through are watched (see {@link Waits}).
     */
    public record Pass(int way, String station, long at) {
        public Pass {
            Objects.requireNonNull(station, "station");
        }
    }

    private DutyRun(String duty, long day, List<Call> calls, int call, Phase phase, long departure, long[] arrivals, long[] departures,
                    boolean[] skipped) {
        this.duty = duty;
        this.day = day;
        this.calls = List.copyOf(calls);
        this.call = call;
        this.phase = phase;
        this.departure = departure;
        this.arrivals = arrivals;
        this.departures = departures;
        this.skipped = skipped;
    }

    /**
     * A run of {@code duty} on service {@code day}, by a set that stands at the first call (the depot)
     * at {@code now}. It leaves at the timetable's time, or at once if that has gone by.
     *
     * @throws IllegalArgumentException if there are fewer than two calls
     */
    public static DutyRun start(String duty, long day, List<Call> calls, long now) {
        if (calls.size() < 2)
            throw new IllegalArgumentException("a duty makes at least 2 calls, not " + calls.size());
        long[] arrivals = new long[calls.size()];
        long[] departures = new long[calls.size()];
        Arrays.fill(arrivals, NOT_YET);
        Arrays.fill(departures, NOT_YET);
        DutyRun run = new DutyRun(duty, day, calls, 0, Phase.STANDING, 0, arrivals, departures, new boolean[calls.size()]);
        run.departure = DeparturePlanner.departure(run.plannedDeparture(0), now, 0);
        return run;
    }

    /**
     * The run that {@code state} was taken from, with the calls of its duty.
     *
     * @throws IllegalArgumentException if the state does not fit the calls, as when the duty has changed
     */
    public static DutyRun restore(State state, List<Call> calls) {
        int size = calls.size();
        if (state.arrivals().size() != size || state.departures().size() != size || state.call() < 0 || state.call() >= size)
            throw new IllegalArgumentException("the run of duty " + state.duty() + " does not fit its " + size + " calls");
        DutyRun run = new DutyRun(state.duty(), state.day(), calls, state.call(), state.phase(), state.departure(),
            state.arrivals().stream().mapToLong(Long::longValue).toArray(),
            state.departures().stream().mapToLong(Long::longValue).toArray(),
            skipped(state, calls));
        for (Pass pass : state.passes())
            if (pass.way() < 0 || pass.way() >= size - 1)
                throw new IllegalArgumentException("the run of duty " + state.duty() + " has no way " + pass.way());
        run.passes.addAll(state.passes());
        return run;
    }

    /**
     * The calls {@code state} does not make, as flags by call. Each run of them lies between two calls
     * at the same station, where the set was held, and none is the first or the last call or the one
     * the set is at or running to.
     */
    private static boolean[] skipped(State state, List<Call> calls) {
        boolean[] skipped = new boolean[calls.size()];
        int last = 0;
        for (int index : state.skipped()) {
            if (index <= last || index >= calls.size() - 1 || index == state.call())
                throw new IllegalArgumentException("the run of duty " + state.duty() + " does not make call " + index
                    + ", which it cannot leave out");
            skipped[index] = true;
            last = index;
        }
        for (int from = 1; from < calls.size() - 1; from++) {
            if (!skipped[from] || skipped[from - 1])
                continue;
            int to = from;
            while (skipped[to + 1])
                to++;
            if (!calls.get(from - 1).station().equals(calls.get(to + 1).station()))
                throw new IllegalArgumentException("the run of duty " + state.duty() + " leaves out calls " + from + " to " + to
                    + ", which do not start and end at the same station");
        }
        return skipped;
    }

    public State state() {
        int[] left = new int[calls.size()];
        int count = 0;
        for (int index = 0; index < skipped.length; index++)
            if (skipped[index])
                left[count++] = index;
        State state = State.of(duty, day, call, phase, departure, arrivals, departures, Arrays.copyOf(left, count));
        return new State(state.duty(), state.day(), state.call(), state.phase(), state.departure(), state.arrivals(), state.departures(),
            state.skipped(), passes);
    }

    private static List<Long> boxed(long[] values) {
        List<Long> list = new ArrayList<>(values.length);
        for (long value : values)
            list.add(value);
        return list;
    }

    // ------------------------------------------------------------------ what happens

    /** Whether the set is standing and its time to leave has come. */
    public boolean dueToLeave(long now) {
        return phase == Phase.STANDING && now >= departure;
    }

    /** The set has been sent on to the next call, and has not left yet. */
    public void sent() {
        if (phase != Phase.STANDING)
            throw new IllegalStateException("duty " + duty + " is not standing at a call but " + phase);
        phase = Phase.DEPARTING;
    }

    /** The set has left the call it stood at, and is running to the next it makes. */
    public void left(long now) {
        if (phase != Phase.STANDING && phase != Phase.DEPARTING)
            throw new IllegalStateException("duty " + duty + " is not at a call but " + phase);
        departures[call] = now;
        call = nextMade(call);
        phase = Phase.RUNNING;
    }

    /**
     * The set, standing at its call, is held there until call {@code to} of the duty at the same
     * station, and leaves from that at {@code departure}: the calls in between are not made.
     *
     * @throws IllegalArgumentException if call {@code to} is not a later call at this station that the
     *                                  set can leave from, with a call in between
     */
    public void hold(int to, long departure) {
        if (phase != Phase.STANDING)
            throw new IllegalStateException("duty " + duty + " is not standing at a call but " + phase);
        if (to <= call + 1 || to >= calls.size() - 1 || !calls.get(to).station().equals(calls.get(call).station()))
            throw new IllegalArgumentException("duty " + duty + " cannot be held from call " + call + " to call " + to);
        for (int index = call + 1; index < to; index++)
            skipped[index] = true;
        call = to;
        this.departure = departure;
    }

    /**
     * The set has arrived at the call it was running to; it is to leave after the call's minimum stop
     * at the least, or after {@code defaultMinimumStop} ticks if the timetable gives the call none.
     */
    public void arrived(long now, long defaultMinimumStop) {
        if (phase != Phase.RUNNING)
            throw new IllegalStateException("duty " + duty + " is not running but " + phase);
        arrivals[call] = now;
        if (call == calls.size() - 1) {
            phase = Phase.FINISHED;
            return;
        }
        phase = Phase.STANDING;
        departure = DeparturePlanner.departure(plannedDeparture(call), now, minimumStop(call, defaultMinimumStop));
    }

    /** The minimum stop at a call, in ticks: the timetable's, or else {@code defaultMinimumStop}. */
    public long minimumStop(int index, long defaultMinimumStop) {
        return DutyPlan.minimumStop(calls.get(index), defaultMinimumStop);
    }

    // ------------------------------------------------------------------ what it is

    public String duty() {
        return duty;
    }

    public long day() {
        return day;
    }

    public List<Call> calls() {
        return calls;
    }

    /** The index of the call the set is at, or running to. */
    public int call() {
        return call;
    }

    public Phase phase() {
        return phase;
    }

    public boolean finished() {
        return phase == Phase.FINISHED;
    }

    /** When the set is to leave the call it stands at; meaningful only while it is {@link Phase#STANDING} or {@link Phase#DEPARTING}. */
    public long departure() {
        return departure;
    }

    /** The station the set goes to next: the call it is running to, or the next it makes after the call it stands at. */
    public String nextStation() {
        return calls.get(phase == Phase.STANDING || phase == Phase.DEPARTING ? nextMade(call) : call).station();
    }

    /** The set has run through {@code station} on the way from call {@code way} at {@code now}; once is enough. */
    public void passed(int way, String station, long now) {
        if (!passedBy(way, station))
            passes.add(new Pass(way, station, now));
    }

    /** Whether the set has been seen to run through {@code station} on the way from call {@code way}. */
    public boolean passedBy(int way, String station) {
        return passes.stream().anyMatch(pass -> pass.way() == way && pass.station().equals(station));
    }

    /** The stations the set has been seen to run through, in order. */
    public List<Pass> passes() {
        return List.copyOf(passes);
    }

    /** Whether the set does not make call {@code index}, having been held past it. */
    public boolean skipped(int index) {
        return skipped[index];
    }

    /** The calls the set does not make, in order. */
    public List<Integer> skipped() {
        return state().skipped();
    }

    /** The first call after {@code index} that the set makes. */
    private int nextMade(int index) {
        int next = index + 1;
        while (skipped[next])
            next++;
        return next;
    }

    /** The last call before {@code index} that the set makes. */
    private int previousMade(int index) {
        int previous = index - 1;
        while (skipped[previous])
            previous--;
        return previous;
    }

    /** The clock value of the timetable's departure from call {@code index}. */
    public long plannedDeparture(int index) {
        return at(calls.get(index).depart());
    }

    /** The clock value of the timetable's arrival at call {@code index}. */
    public long plannedArrival(int index) {
        return at(calls.get(index).arrive());
    }

    private long at(int seconds) {
        return DutyPlan.at(day, seconds);
    }

    /** When the set arrived at each call, or {@link #NOT_YET}. */
    public List<Long> arrivals() {
        return boxed(arrivals);
    }

    /** When it left each call, or {@link #NOT_YET}. */
    public List<Long> departures() {
        return boxed(departures);
    }

    /**
     * How many ticks behind the timetable the set is at {@code now}: at the call it stands at, by when it
     * is to leave, or by now if it has been held there past that; on the way, by when it left; at the
     * end, by when it arrived. Less than 0 when it is early.
     */
    public long delay(long now) {
        return switch (phase) {
            // Held past its time to leave, by a signal or by a train it waits for, it is as late as now.
            case STANDING, DEPARTING -> Math.max(departure, now) - plannedDeparture(call);
            case RUNNING -> departures[previousMade(call)] - plannedDeparture(previousMade(call));
            case FINISHED -> calls.get(call).hasArrive() ? arrivals[call] - plannedArrival(call) : 0;
        };
    }
}
