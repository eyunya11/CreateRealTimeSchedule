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
 * timetable's departure on this service day, A when the set arrived, and M the minimum stop. An early
 * set waits for its time, and a late one stops for the minimum and goes, which is how it gets back to
 * the timetable. At the first call, where the set has been waiting at the depot, there is no minimum
 * stop.
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
     * @param departure  when it is to leave, while it is {@link Phase#STANDING}
     * @param arrivals   when it arrived at each call, or {@link #NOT_YET}
     * @param departures when it left each call, or {@link #NOT_YET}
     */
    public record State(String duty, long day, int call, Phase phase, long departure, List<Long> arrivals, List<Long> departures) {
        public State {
            Objects.requireNonNull(duty, "duty");
            Objects.requireNonNull(phase, "phase");
            arrivals = List.copyOf(arrivals);
            departures = List.copyOf(departures);
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

    private DutyRun(String duty, long day, List<Call> calls, int call, Phase phase, long departure, long[] arrivals, long[] departures) {
        this.duty = duty;
        this.day = day;
        this.calls = List.copyOf(calls);
        this.call = call;
        this.phase = phase;
        this.departure = departure;
        this.arrivals = arrivals;
        this.departures = departures;
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
        DutyRun run = new DutyRun(duty, day, calls, 0, Phase.STANDING, 0, arrivals, departures);
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
        return new DutyRun(state.duty(), state.day(), calls, state.call(), state.phase(), state.departure(),
            state.arrivals().stream().mapToLong(Long::longValue).toArray(),
            state.departures().stream().mapToLong(Long::longValue).toArray());
    }

    public State state() {
        return new State(duty, day, call, phase, departure, boxed(arrivals), boxed(departures));
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

    /** The set has left the call it stood at, and is running to the next. */
    public void left(long now) {
        if (phase != Phase.STANDING)
            throw new IllegalStateException("duty " + duty + " is not standing at a call but " + phase);
        departures[call] = now;
        call++;
        phase = Phase.RUNNING;
    }

    /** The set has arrived at the call it was running to; it is to leave after {@code minimumStop} ticks at the least. */
    public void arrived(long now, long minimumStop) {
        if (phase != Phase.RUNNING)
            throw new IllegalStateException("duty " + duty + " is not running but " + phase);
        arrivals[call] = now;
        if (call == calls.size() - 1) {
            phase = Phase.FINISHED;
            return;
        }
        phase = Phase.STANDING;
        departure = DeparturePlanner.departure(plannedDeparture(call), now, minimumStop);
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

    /** When the set is to leave the call it stands at; meaningful only while it is {@link Phase#STANDING}. */
    public long departure() {
        return departure;
    }

    /** The station the set goes to next: the call it is running to, or the one after the call it stands at. */
    public String nextStation() {
        return calls.get(phase == Phase.STANDING ? call + 1 : call).station();
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
        return day * RealClock.TICKS_PER_DAY + seconds * (long) RealClock.TICKS_PER_SECOND;
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
     * How many ticks behind the timetable the set is: at the call it stands at, by when it is to leave;
     * on the way, by when it left; at the end, by when it arrived. Less than 0 when it is early.
     */
    public long delay() {
        return switch (phase) {
            case STANDING -> departure - plannedDeparture(call);
            case RUNNING -> departures[call - 1] - plannedDeparture(call - 1);
            case FINISHED -> calls.get(call).hasArrive() ? arrivals[call] - plannedArrival(call) : 0;
        };
    }
}
