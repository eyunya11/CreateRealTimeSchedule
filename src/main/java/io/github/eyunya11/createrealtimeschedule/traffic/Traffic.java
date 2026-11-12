package io.github.eyunya11.createrealtimeschedule.traffic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import io.github.eyunya11.createrealtimeschedule.railway.SignalBlocks;
import io.github.eyunya11.createrealtimeschedule.travel.Drive;
import io.github.eyunya11.createrealtimeschedule.travel.SpeedSigns;
import io.github.eyunya11.createrealtimeschedule.travel.SpeedZone;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpec;

/**
 * The trains of a timetable running on time together, a tick at a time, with the signals between them:
 * how much longer than on an empty line each leg takes, for the light signals of Create Train Next that a
 * train behind another passes at a lower aspect, and for the signals at red it stops at. It has no
 * Minecraft types in it, so that it can be tested without a game.
 *
 * <p>Each train runs as {@link Drive} has Create run it, and does what Create's {@code Navigation} does
 * about signals: it looks for them ahead as far as it needs to brake (to the next multiple of 3 blocks),
 * reserves the block beyond one it is about to pass, and stops short of one whose block has another
 * train in it or is reserved, until the block is clear. A train standing at a station does not leave
 * while a signal within 4.5 blocks is at red. The block a train takes up is that of every signal its
 * body, from its front back by its length, has passed, as Create counts it, and a block whose track
 * crosses another's is taken up with it. A cross signal of Create holds a train until the blocks after
 * it are clear too, up to the next signal that is not one.
 *
 * <p>Where Create Train Next is there ({@link SignalBlocks#speeds}), a train passing a signal is held
 * to the speed of the aspect it shows then, until it passes the next signal; one of Create's own lifts
 * that limit. The aspect is Create Train Next's {@code AspectResolver}'s: how many blocks ahead are
 * clear, the fewest where the way branches, up the ladder of the head. A train also slows in time for
 * what the signals ahead show, as for its speed-limit signs (see {@link Drive}). The lamps of a light
 * signal are worked out here every tick, where the game does so every half second.
 *
 * <p>A train that falls ten minutes behind ({@link #TOO_LATE_TICKS}) is taken off the line, and the leg
 * it was on is one it never arrives at the end of; one that starts late ({@link Run#lateBy}) is taken off
 * once it falls ten minutes behind that.
 *
 * <p>The trains are taken in the order Create takes its own in: first those waiting for a signal, the
 * one that has waited longest first, and then those on the move, in the order they came onto the line
 * or stopped waiting; a train goes from one to the other at the end of a tick. Where two trains reach
 * for the same block in one tick, the first gets it, so that a train held up for a block gets it the
 * next tick, and two trains after the same block can hold each other up in turn. Trains that come onto
 * the line in the same tick are taken in the order given.
 *
 * <p>Each train is measured by its front, at the stop marker of each station, as {@link Leg} has it. The
 * part of its body behind the start of a leg is taken to lie on the leg before, as it does where the
 * train runs on through the station; a train that turns back there is drawn over the same blocks when it
 * leaves, and its first leg has only the block it starts in behind it. A signal at the stop marker a train
 * stops at is one it has passed, as {@link Leg} has it, and the next leg starts past it.
 *
 * <p>Each train runs the legs it is given: one per two stations, so a station with more than one
 * platform has every train at the same one, where Create would send a train to one that is free. A
 * second train for a platform that a train stands at waits at red until it leaves, or is taken off as
 * too late if it stays there.
 */
public final class Traffic {

    /**
     * How many times at most a train is moved on, in all, before the simulation gives up: once a tick for
     * each train on its way, so that a day of a train a minute, each on its way for two minutes, is a fifth
     * of it. A timetable that asks far more of the line than it can take keeps
     * dozens of trains held up on it for ten minutes each, and would take tens of seconds.
     */
    public static final long MAX_STEPS = 5_000_000;
    /** How long after the last departure of the timetable a train that has not arrived is given up on: an hour. */
    static final long GIVE_UP_TICKS = 20L * 60 * 60;
    /**
     * How far behind a train may fall before it is taken off the line, in ticks: ten minutes later than the
     * timetable leaving a station, or ten minutes longer than the line takes on a leg. A timetable that
     * asks for more than the line can take would otherwise pile its trains up without end; the operation
     * cancels trains that late too.
     */
    static final long TOO_LATE_TICKS = 20L * 60 * 10;
    /** Create does not leave a station while a signal this near ahead is at red, in blocks. */
    private static final double PRE_DEPARTURE_LOOK_AHEAD = 4.5;
    /** Create counts a train this near a signal it waits for as standing at it, and stops looking ahead. */
    private static final double AT_SIGNAL = 1.0 / 16;
    /** Create reserves a block only if it is not past the signal it waits at by more than this. */
    private static final double RESERVE_SLACK = 0.25;
    /** Create counts the wait for a signal over this near the stop, in blocks. */
    private static final double NEAR_THE_STOP = 0.5;
    /** Create leaves a station only if it has further to go than this. */
    private static final double LEAVE_DISTANCE = 1.0 / 32;

    /**
     * A train's day: it appears at the start of its first leg when it is to leave there, runs its legs one
     * after another, and then stands where the last ends, or is taken off the line there.
     *
     * @param name     what to call it, for a reader
     * @param spec     how fast it goes
     * @param throttle the share of the top speed it is allowed
     * @param stints   its legs, in order, each from where the one before ends
     * @param stays    whether it stands where its last leg ends for the rest of the day, taking up its
     *                 blocks, as a set in its depot does; or is taken off the line there
     * @param lateBy   how many ticks after the time of its first leg it leaves the start of that leg, as
     *                 a set that is late already does; it is taken off for being too late only once it
     *                 falls {@link #TOO_LATE_TICKS} behind that
     */
    public record Run(String name, TrainSpec spec, double throttle, List<Stint> stints, boolean stays, long lateBy) {

        /** A train taken off the line where its last leg ends. */
        public Run(String name, TrainSpec spec, double throttle, List<Stint> stints) {
            this(name, spec, throttle, stints, false);
        }

        /** A train that leaves the start of its first leg on time. */
        public Run(String name, TrainSpec spec, double throttle, List<Stint> stints, boolean stays) {
            this(name, spec, throttle, stints, stays, 0);
        }

        public Run {
            stints = List.copyOf(stints);
            if (!(throttle > 0) || throttle > 1)
                throw new IllegalArgumentException("a throttle is more than 0 and no more than 1: " + throttle);
            if (stints.isEmpty())
                throw new IllegalArgumentException("a train runs at least one leg: " + name);
        }
    }

    /**
     * A leg of a {@link Run}.
     *
     * @param leg         the way it takes
     * @param departs     the tick the timetable has it leave
     * @param minimumStop the fewest ticks it stands at the start of the leg if it comes in late; not
     *                    counted for the first leg, where it stands from the start
     * @param newTrain    whether a train of the timetable starts here: it starts under no limit of a sign
     *                    or a signal, where a train turns back the signs facing the other way being put up
     *                    as well
     * @param length      the train's length, from its front to its back, in blocks: that of the train of the
     *                    timetable it runs the leg as
     */
    public record Stint(Leg leg, long departs, long minimumStop, boolean newTrain, double length) {
        public Stint {
            if (!(length >= 0))
                throw new IllegalArgumentException("a train is no shorter than nothing: " + length);
        }
    }

    /**
     * A train standing at a signal at red, or held at a station by one.
     *
     * @param signal the side of the signal, by its number in {@link SignalBlocks}
     * @param from   the tick it came to a stand
     * @param ticks  how long it stood
     */
    public record Wait(int signal, long from, long ticks) {}

    /**
     * How a leg went.
     *
     * @param departed when the train was to leave, and could: the tick before the first in which it moved
     *                 or was held by a signal
     * @param arrived  the tick in which it stopped at the end, or {@link #NOT_ARRIVED}
     * @param free     how many ticks it takes on an empty line, as {@link Leg#ticks} has it with the limit
     *                 of a sign it left under; -1 where it never gets there
     * @param waits    the signals at red it stood at
     */
    public record Trip(long departed, long arrived, int free, List<Wait> waits) {

        public static final long NOT_ARRIVED = -1;

        public Trip {
            waits = List.copyOf(waits);
        }

        public boolean hasArrived() {
            return arrived != NOT_ARRIVED;
        }

        /** How long the leg took, waits and all; only for a leg that ended. */
        public long ticks() {
            if (!hasArrived())
                throw new IllegalStateException("the train did not arrive");
            return arrived - departed;
        }

        /** How many ticks of all the signals added to the leg; only for a leg that ended on a way it can run. */
        public long signalTicks() {
            return ticks() - free;
        }
    }

    /**
     * How the trains went: for each {@link Run}, in order, a trip for each of its legs it set out on, or
     * was to set out on and was taken off before it did. A train that never arrived somewhere has a trip
     * that says so, and none for the legs after. A leg a train was still on when the simulation stopped
     * at its end ({@link #run(SignalBlocks, List, long)}) has no trip: how it would have gone is not known.
     *
     * @param finished whether the simulation ran to the end, and did not give up after {@link #MAX_STEPS};
     *                 the trips of one that gave up are not to be gone by
     */
    public record Result(List<List<Trip>> trips, boolean finished) {
        public Result {
            trips = trips.stream().map(List::copyOf).toList();
        }
    }

    /** Runs the trains of {@code runs} on the signal blocks of their network; those that come onto the line together, in that order. */
    public static Result run(SignalBlocks blocks, List<Run> runs) {
        return run(blocks, runs, Long.MAX_VALUE);
    }

    /**
     * {@link #run(SignalBlocks, List)}, up to the tick {@code until} and no further: a train still on its
     * way then has no trip for that leg, nor any for the legs after.
     */
    public static Result run(SignalBlocks blocks, List<Run> runs, long until) {
        return run(blocks, runs, until, MAX_STEPS);
    }

    /** {@link #run(SignalBlocks, List)}, giving up after {@code maxSteps} moves of a train. */
    static Result run(SignalBlocks blocks, List<Run> runs, long until, long maxSteps) {
        return new Traffic(blocks, runs).simulate(until, maxSteps);
    }

    private final SignalBlocks blocks;
    private final @Nullable SignalBlocks.LightSpeeds speeds;
    /** For each block, itself and the blocks whose tracks cross it, through others too. */
    private final int[][] crossing;
    private final List<Train> trains = new ArrayList<>();
    /** The trains not on the line yet, by when they appear. */
    private final List<Train> coming;
    private int nextToCome;
    /** The trains on the line that wait for a signal, the one that has waited longest first: Create's {@code waitingTrains}. */
    private final List<Train> waiting = new ArrayList<>();
    /** The trains on the line that do not, in the order they came on or stopped waiting: Create's {@code movingTrains}. */
    private final List<Train> moving = new ArrayList<>();
    /** The trains that have run their legs and stand where they ended. */
    private final List<Train> parked = new ArrayList<>();
    /** For each block, the trains in it this tick, as Create's {@code SignalEdgeGroup.trains}. */
    private final List<List<Train>> in = new ArrayList<>();
    /** For each block, the signal side that reserved it this tick, or -1. */
    private final int[] reserved;
    private final List<Integer> touched = new ArrayList<>();
    /** The trains on the line this tick, waiting ones first; filled again every tick. */
    private final List<Train> onTheLine = new ArrayList<>();
    private long now;
    /** How many times a train has been moved on. */
    private long steps;

    private Traffic(SignalBlocks blocks, List<Run> runs) {
        this.blocks = blocks;
        this.speeds = blocks.speeds();
        this.crossing = crossings(blocks);
        this.reserved = new int[blocks.count()];
        Arrays.fill(reserved, -1);
        for (int b = 0; b < blocks.count(); b++)
            in.add(new ArrayList<>());
        for (Run run : runs)
            trains.add(new Train(run));
        coming = trains.stream().sorted(Comparator.comparingLong(Train::appears)).toList();
    }

    private static int[][] crossings(SignalBlocks blocks) {
        List<List<Integer>> with = new ArrayList<>();
        for (int b = 0; b < blocks.count(); b++)
            with.add(new ArrayList<>());
        for (List<Integer> pair : blocks.crossings()) {
            with.get(pair.get(0)).add(pair.get(1));
            with.get(pair.get(1)).add(pair.get(0));
        }
        int[][] crossing = new int[blocks.count()][];
        for (int b = 0; b < blocks.count(); b++) {
            List<Integer> found = new ArrayList<>(List.of(b));
            for (int i = 0; i < found.size(); i++)
                for (int other : with.get(found.get(i)))
                    if (!found.contains(other))
                        found.add(other);
            crossing[b] = found.stream().mapToInt(Integer::intValue).toArray();
        }
        return crossing;
    }

    private Result simulate(long until, long maxSteps) {
        if (trains.isEmpty())
            return new Result(List.of(), true);
        now = coming.getFirst().appears();
        long giveUp = trains.stream()
            .mapToLong(train -> train.run.stints().stream().mapToLong(Stint::departs).max().orElseThrow() + Math.max(0, train.run.lateBy()))
            .max().orElseThrow() + GIVE_UP_TICKS;
        while (now <= giveUp && now <= until && (nextToCome < coming.size() || !waiting.isEmpty() || !moving.isEmpty())) {
            if (steps > maxSteps)
                break;
            if (tick()) {
                now++;
                continue;
            }
            // Nothing moved: nothing will until a train is due to leave or to appear.
            long next = nextToCome < coming.size() ? coming.get(nextToCome).appears() : Long.MAX_VALUE;
            for (Train train : onTheLine()) {
                if (train.drive == null)
                    next = Math.min(next, train.leaves + 1);
                else
                    // A train held up for good is taken off at its deadline, which may set others free.
                    next = Math.min(next, train.departed + Math.max(train.free, 0) + TOO_LATE_TICKS + 1);
            }
            if (next == Long.MAX_VALUE || next <= now)
                break;
            now = next;
        }
        boolean cut = now > until;
        List<List<Trip>> trips = new ArrayList<>();
        for (Train train : trains) {
            if (train.drive != null && !cut)
                train.trips.add(new Trip(train.departed, Trip.NOT_ARRIVED, train.free, train.waits));
            trips.add(train.trips);
        }
        return new Result(trips, steps <= maxSteps);
    }

    /** One tick of every train; whether anything moved or changed. */
    private boolean tick() {
        boolean changed = false;
        if (nextToCome < coming.size() && coming.get(nextToCome).appears() <= now) {
            while (nextToCome < coming.size() && coming.get(nextToCome).appears() <= now)
                coming.get(nextToCome++).appear();
            changed = true;
        }

        // What every train's earlyTick puts into the blocks: where its body is, and the blocks it holds for
        // a cross signal.
        for (int block : touched) {
            in.get(block).clear();
            reserved[block] = -1;
        }
        touched.clear();
        List<Train> onTheLine = onTheLine();
        for (Train train : parked)
            for (int block : train.parkedBlocks)
                enter(block, train);
        for (Train train : onTheLine) {
            for (int block : train.body())
                enter(block, train);
            for (int block : train.chain)
                enter(block, train);
        }

        for (Train train : onTheLine) {
            int waitedFor = train.waitingFor;
            if (train.drive == null) {
                if (now <= train.leaves)
                    continue;
                if (train.leaves - train.run.stints().get(train.stint).departs() > TOO_LATE_TICKS + Math.max(0, train.run.lateBy())) {
                    train.dropBeforeLeaving();
                    changed = true;
                    continue;
                }
                train.leave();
                changed = true;
            } else if (now - train.departed > Math.max(train.free, 0) + TOO_LATE_TICKS) {
                train.trips.add(new Trip(train.departed, Trip.NOT_ARRIVED, train.free, train.waits));
                train.drive = null;
                train.done = true;
                changed = true;
                continue;
            }
            changed |= control(train) || train.waitingFor != waitedFor;
        }

        // GlobalRailwayManager.tickTrains: trains that stopped waiting go to the end of the moving ones,
        // and those that started to the end of the waiting ones.
        for (Iterator<Train> each = waiting.iterator(); each.hasNext(); ) {
            Train train = each.next();
            if (train.done) {
                each.remove();
            } else if (train.waitingFor == -1) {
                moving.add(train);
                each.remove();
            }
        }
        for (Iterator<Train> each = moving.iterator(); each.hasNext(); ) {
            Train train = each.next();
            if (train.done) {
                each.remove();
            } else if (train.waitingFor != -1) {
                waiting.add(train);
                each.remove();
            }
        }
        return changed;
    }

    /** The trains on the line, waiting ones first, in a list kept for the purpose: done with it before the next call. */
    private List<Train> onTheLine() {
        onTheLine.clear();
        onTheLine.addAll(waiting);
        onTheLine.addAll(moving);
        return onTheLine;
    }

    private void enter(int block, Train train) {
        if (block < 0)
            return;
        List<Train> there = in.get(block);
        if (there.isEmpty() && reserved[block] == -1)
            touched.add(block);
        if (!there.contains(train))
            there.add(train);
    }

    private void reserve(int block, int signal) {
        if (block < 0)
            return;
        if (in.get(block).isEmpty() && reserved[block] == -1)
            touched.add(block);
        reserved[block] = signal;
    }

    /** Create's {@code SignalEdgeGroup.isOccupiedUnless(Train)}: whether the block keeps {@code train} out. */
    private boolean occupiedFor(int block, Train train) {
        if (block < 0)
            return false;
        for (int each : crossing[block]) {
            List<Train> there = in.get(each);
            if (reserved[each] != -1 || there.size() > 1 || !there.isEmpty() && !there.contains(train))
                return true;
        }
        return false;
    }

    /** Create's {@code SignalEdgeGroup.isOccupiedUnless(SignalBoundary)}: whether the block is taken, but for {@code signal}'s reservation. */
    private boolean occupiedPast(int block, int signal) {
        if (block < 0)
            return true;
        for (int each : crossing[block])
            if (!in.get(each).isEmpty() || reserved[each] != -1 && reserved[each] != signal)
                return true;
        return false;
    }

    /** Navigation.tick and the move of Train.tick for a train on its way; whether it moved or changed. */
    private boolean control(Train train) {
        steps++;
        Leg leg = train.leg;
        Drive drive = train.drive;
        double front = drive.position();
        double toGo = leg.distance() - front;

        if (train.waitingFor != -1 && resolved(train, toGo)) {
            train.endWait(now);
            train.waitingFor = -1;
            train.waitChain.clear();
        }
        if (train.waitingFor == -1 || train.waitAt - front > AT_SIGNAL)
            scan(train, front, toGo);

        boolean waiting = train.waitingFor != -1;
        double stopAt = waiting ? train.waitAt : leg.distance();
        if (train.atStation && stopAt - front + 0.25 > LEAVE_DISTANCE) {
            // Create does not leave until a signal this near is green.
            if (waiting && train.waitAt - front < PRE_DEPARTURE_LOOK_AHEAD) {
                train.startWait(now);
                return false;
            }
            train.atStation = false;
        }

        double speed = drive.speed();
        Drive.Step step = drive.step(stopAt, train.ahead);
        double moved = drive.position();
        List<Leg.PassedSignal> signals = leg.signals();
        while (train.nextSignal < signals.size() && signals.get(train.nextSignal).at() < moved)
            pass(train, signals.get(train.nextSignal++).signal());
        boolean changed = moved != front || drive.speed() != speed;
        if (step != Drive.Step.REACHED)
            return changed;
        if (waiting) {
            // The front is at the signal, which stops it: Create Train Next takes it as passed all the same.
            train.startWait(now);
            if (speeds != null)
                drive.signalLimit(seenFrom(train.waitingFor));
            return changed;
        }
        // A signal at the stop marker is passed, as the leg has it: the next leg starts past it.
        while (train.nextSignal < signals.size() && signals.get(train.nextSignal).at() <= leg.distance())
            pass(train, signals.get(train.nextSignal++).signal());
        train.arrive(now);
        return true;
    }

    /** Navigation.currentSignalResolved: whether the block the train waits for is clear now. */
    private boolean resolved(Train train, double toGo) {
        if (toGo < NEAR_THE_STOP)
            return true;
        SignalBlocks.Signal signal = blocks.signals().get(train.waitingFor);
        if (signal.cross()) {
            for (int[] held : train.waitChain)
                if (occupiedFor(held[0], train))
                    return false;
            return true;
        }
        return !occupiedFor(signal.block(), train);
    }

    /**
     * Navigation.tick's look ahead for signals, as far as the train needs to brake: whether one is at red,
     * and the block beyond one it is about to pass reserved for it.
     */
    private void scan(Train train, double front, double toGo) {
        double braking = train.drive.brakingDistance();
        double noFlicker = braking + 3 - braking % 3;
        double least = train.atStation ? PRE_DEPARTURE_LOOK_AHEAD : 0;
        double scanDistance = noFlicker < least ? least : Math.min(noFlicker, toGo);
        double distanceToSignal = train.waitingFor == -1 ? Double.MAX_VALUE : train.waitAt - front;

        int tracking = -1;
        double trackingAt = 0;
        List<int[]> chain = new ArrayList<>();
        List<Leg.PassedSignal> signals = train.leg.signals();
        for (int i = train.nextSignal; i < signals.size(); i++) {
            Leg.PassedSignal passed = signals.get(i);
            double distance = passed.at() - front;
            boolean tracked = tracking != -1;
            if (!tracked && distance > scanDistance)
                break;
            SignalBlocks.Signal signal = blocks.signals().get(passed.signal());
            boolean occupied = occupiedFor(signal.block(), train);
            if (!tracked) {
                if (signal.cross()) {
                    tracking = passed.signal();
                    trackingAt = passed.at();
                    chain.add(new int[] {signal.block(), passed.signal()});
                }
                if (occupied) {
                    train.waitingFor = passed.signal();
                    train.waitAt = passed.at();
                    distanceToSignal = distance;
                    if (!signal.cross())
                        break;
                }
                if (!occupied && !signal.cross() && distance < distanceToSignal + RESERVE_SLACK && distance < noFlicker)
                    reserve(signal.block(), passed.signal());
                continue;
            }
            chain.add(new int[] {signal.block(), passed.signal()});
            if (occupied) {
                train.waitingFor = tracking;
                train.waitAt = trackingAt;
                distanceToSignal = trackingAt - front;
                if (!signal.cross())
                    break;
            }
            if (!signal.cross()) {
                if (distance < distanceToSignal + RESERVE_SLACK) {
                    tracking = -1;
                    reserveChain(train, chain);
                    continue;
                }
                break;
            }
        }
        if (tracking != -1 && train.waitingFor == -1)
            reserveChain(train, chain);
        train.waitChain = chain;
    }

    /** Navigation.reserveChain: the blocks after a cross signal, held for the train until it is in them. */
    private void reserveChain(Train train, List<int[]> chain) {
        for (int[] held : chain) {
            train.chain.add(held[0]);
            reserve(held[0], held[1]);
        }
        chain.clear();
    }

    /** The front passes a signal: Create Train Next sees what it shows, and the train goes into its block. */
    private void pass(Train train, int signal) {
        if (speeds != null)
            train.drive.signalLimit(seenFrom(signal));
        int block = blocks.signals().get(signal).block();
        reserve(block, signal);
        enter(block, train);
        train.chain.remove(block);
    }

    /**
     * {@code SignalSpeeds.seenFrom}: the speed in km/h the heads of a signal hold a train passing it to,
     * the lowest of them; infinity where they hold it to none, as a signal without a head does.
     */
    private double seenFrom(int signal) {
        double kmh = Double.POSITIVE_INFINITY;
        for (int aspects : blocks.signals().get(signal).lights())
            kmh = Math.min(kmh, speedOf(aspects, aspect(signal, aspects)));
        return kmh;
    }

    /**
     * {@code SignalSpeeds.shownTo}: what a train still some way off goes by, as {@link Drive.SignalsAhead#kmh}
     * has it; NaN where every head is at stop.
     */
    private double shownTo(int signal) {
        if (speeds == null)
            return Double.NaN;
        double kmh = Double.POSITIVE_INFINITY;
        boolean stop = false;
        for (int aspects : blocks.signals().get(signal).lights()) {
            Aspect aspect = aspect(signal, aspects);
            if (aspect == Aspect.STOP)
                stop = true;
            else
                kmh = Math.min(kmh, speedOf(aspects, aspect));
        }
        return stop && kmh == Double.POSITIVE_INFINITY ? Double.NaN : kmh;
    }

    /** The aspects of a light signal, from the most restrictive. */
    enum Aspect {
        STOP, RESTRICTED, APPROACH, REDUCED_SPEED, PROCEED, HIGH_SPEED_PROCEED
    }

    /** Create Train Next's {@code SignalAspect.Ladder} for a head of that many aspects, most permissive first. */
    static Aspect[] ladder(int aspects) {
        return switch (aspects) {
            case 2 -> new Aspect[] {Aspect.PROCEED, Aspect.STOP};
            case 3 -> new Aspect[] {Aspect.PROCEED, Aspect.APPROACH, Aspect.STOP};
            case 4 -> new Aspect[] {Aspect.PROCEED, Aspect.REDUCED_SPEED, Aspect.APPROACH, Aspect.STOP};
            case 5 -> new Aspect[] {Aspect.PROCEED, Aspect.REDUCED_SPEED, Aspect.APPROACH, Aspect.RESTRICTED, Aspect.STOP};
            case 6 -> new Aspect[] {Aspect.HIGH_SPEED_PROCEED, Aspect.PROCEED, Aspect.REDUCED_SPEED, Aspect.APPROACH, Aspect.STOP};
            default -> throw new IllegalArgumentException("a light signal shows 2 to 6 aspects: " + aspects);
        };
    }

    /** {@code AspectResolver.resolve}: the aspect a head of that many aspects shows on {@code signal} now. */
    private Aspect aspect(int signal, int aspects) {
        Aspect[] steps = ladder(aspects);
        int clear = clearBlocks(signal, steps.length - 1);
        return steps[Math.max(0, steps.length - 1 - clear)];
    }

    /** {@code AspectResolver.countClearBlocks}: how many blocks in a row past the signal are clear, up to {@code limit}. */
    private int clearBlocks(int signal, int limit) {
        if (limit <= 0)
            return 0;
        SignalBlocks.Signal side = blocks.signals().get(signal);
        if (occupiedPast(side.block(), signal))
            return 0;
        if (side.next().isEmpty())
            return limit;
        int beyond = limit - 1;
        for (int next : side.next())
            beyond = Math.min(beyond, clearBlocks(next, limit - 1));
        return 1 + beyond;
    }

    /** {@code SignalSpeeds.speedOf}: the speed an aspect of a head of that many aspects holds a train to; infinity for none. */
    private double speedOf(int aspects, Aspect aspect) {
        return switch (aspect) {
            case PROCEED -> aspects == 6 ? speeds.sixProceed() : Double.POSITIVE_INFINITY;
            case HIGH_SPEED_PROCEED -> Double.POSITIVE_INFINITY;
            case STOP, RESTRICTED -> speeds.restricted();
            case APPROACH -> speeds.approach();
            case REDUCED_SPEED -> speeds.reduced();
        };
    }

    /** A train of the timetable, as it goes. */
    private final class Train {
        final Run run;
        final List<Trip> trips = new ArrayList<>();
        /** The leg it is on, or stands at the start of; the number of legs once it is done. */
        int stint;
        boolean done;
        /** The tick after which it may leave the start of its leg. */
        long leaves;
        /** Its run of the leg it is on, or null while it stands at a station. */
        @Nullable Drive drive;
        /** The leg its body is measured along: the one it is on, or the one it came in on while it stands. */
        Leg leg;
        /** Its length on {@link #leg}. */
        double length;
        /** Where its front is along {@link #leg}. */
        double front;
        /** The leg before {@link #leg}, where the rest of its body is, or null. */
        @Nullable Leg behind;
        /** The limit of a sign it left the start of its leg under. */
        OptionalInt signAtStart = OptionalInt.empty();
        /** The limit of the last signal it passed, in km/h, kept from leg to leg. */
        double signalKmh = Double.POSITIVE_INFINITY;
        /** Whether it stands at the station it is to leave, as Create's {@code getCurrentStation}. */
        boolean atStation;
        /** The signal side it waits for, or -1. */
        int waitingFor = -1;
        /** Where along the leg it is to stop for that signal. */
        double waitAt;
        /** The blocks after a cross signal it waits at, with the signals into them. */
        List<int[]> waitChain = new ArrayList<>();
        /** The blocks after a cross signal held for it. */
        final Set<Integer> chain = new HashSet<>();
        /** The first of the leg's signals its front has not passed. */
        int nextSignal;
        /** The blocks it takes up where it stays for the rest of the day, once it does. */
        List<Integer> parkedBlocks = List.of();
        long departed;
        int free;
        List<Wait> waits = new ArrayList<>();
        /** The tick it came to a stand for the signal it waits for, or -1. */
        long waitFrom = -1;
        /** The signals ahead, as Create Train Next sees them. */
        final Drive.SignalsAhead ahead = new Drive.SignalsAhead() {
            @Override
            public int count() {
                return leg.signals().size() - nextSignal;
            }

            @Override
            public double position(int i) {
                return leg.signals().get(nextSignal + i).at();
            }

            @Override
            public double kmh(int i) {
                return shownTo(leg.signals().get(nextSignal + i).signal());
            }
        };

        Train(Run run) {
            this.run = run;
            this.leg = run.stints().getFirst().leg();
            this.length = run.stints().getFirst().length();
        }

        long appears() {
            return run.stints().getFirst().departs() + run.lateBy();
        }

        void appear() {
            moving.add(this);
            leaves = appears();
            atStation = true;
        }

        /** The limit of a sign it leaves the start of {@code stint}, its next leg, under: none for a new train of the timetable. */
        OptionalInt signAtLeaving(Stint stint) {
            return this.stint == 0 || stint.newTrain() ? OptionalInt.empty() : leg.limitAtEnd(signAtStart);
        }

        /** How many ticks {@code stint} takes on an empty line, leaving under {@code sign}; -1 where it never gets there. */
        int freeTicks(Stint stint, OptionalInt sign) {
            OptionalInt ticks = stint.leg().ticks(run.spec(), run.throttle(), sign);
            return ticks.isPresent() ? ticks.getAsInt() : -1;
        }

        /** It sets out on its leg: under the limits it is under, unless a new train of the timetable starts here. */
        void leave() {
            Stint stint = run.stints().get(this.stint);
            if (this.stint > 0) {
                signAtStart = signAtLeaving(stint);
                behind = leg;
            }
            if (this.stint == 0 || stint.newTrain())
                signalKmh = Double.POSITIVE_INFINITY;
            leg = stint.leg();
            length = stint.length();
            front = 0;
            nextSignal = 0;
            List<SpeedZone> zones = new ArrayList<>();
            for (Leg.Curve curve : leg.curves())
                zones.add(SpeedZone.curve(curve.start(), curve.end(), run.spec()));
            drive = new Drive(run.spec(), run.throttle(), zones, new SpeedSigns(signAtStart, leg.signs()));
            drive.signalLimit(signalKmh);
            departed = leaves;
            free = freeTicks(stint, signAtStart);
            waits = new ArrayList<>();
        }

        /**
         * It is too late to leave the station it stands at, and is taken off the line there: the leg it
         * was to set out on is one it never arrives at the end of.
         */
        void dropBeforeLeaving() {
            Stint stint = run.stints().get(this.stint);
            trips.add(new Trip(leaves, Trip.NOT_ARRIVED, freeTicks(stint, signAtLeaving(stint)), List.of()));
            done = true;
        }

        void arrive(long at) {
            trips.add(new Trip(departed, at, free, waits));
            signalKmh = drive.signalLimit();
            front = leg.distance();
            drive = null;
            chain.clear();
            waitChain.clear();
            waitingFor = -1;
            atStation = true;
            stint++;
            if (stint == run.stints().size()) {
                done = true;
                if (run.stays()) {
                    parked.add(this);
                    parkedBlocks = body();
                }
                return;
            }
            Stint next = run.stints().get(stint);
            leaves = Math.max(next.departs(), at + next.minimumStop());
        }

        void startWait(long at) {
            if (waitFrom == -1)
                waitFrom = at;
        }

        void endWait(long at) {
            if (waitFrom != -1)
                waits.add(new Wait(waitingFor, waitFrom, at - waitFrom));
            waitFrom = -1;
        }

        /** The blocks its body takes up: from its front back by its length, onto the leg before if it reaches. */
        List<Integer> body() {
            double at = drive == null ? front : drive.position();
            double tail = at - length;
            List<Integer> taken = new ArrayList<>(2);
            if (tail < 0 && behind != null)
                span(behind, Math.max(0, behind.distance() + tail), behind.distance(), true, taken);
            // Standing at the end of the leg it came in on, it has passed a signal at the stop marker.
            span(leg, Math.max(0, tail), at, drive == null && at >= leg.distance(), taken);
            return taken;
        }
    }

    /**
     * The blocks of {@code leg} from {@code from} up to {@code to}: the one {@code from} is in, a signal
     * there not yet passed by the back, and that of each signal from there up to the front, which has not
     * gone into the block of one it stands at; unless {@code atTheEnd}, for a front at the stop marker at
     * the end of the leg, which has passed a signal there.
     */
    private void span(Leg leg, double from, double to, boolean atTheEnd, List<Integer> taken) {
        int block = leg.block();
        List<Leg.PassedSignal> signals = leg.signals();
        int i = 0;
        while (i < signals.size() && signals.get(i).at() < from)
            i++;
        if (i > 0)
            block = blockOf(leg, i - 1);
        add(taken, block);
        for (; i < signals.size() && (signals.get(i).at() < to || atTheEnd && signals.get(i).at() <= to); i++)
            add(taken, blockOf(leg, i));
    }

    private int blockOf(Leg leg, int signal) {
        return blocks.signals().get(leg.signals().get(signal).signal()).block();
    }

    private static void add(List<Integer> taken, int block) {
        if (block >= 0 && !taken.contains(block))
            taken.add(block);
    }
}
