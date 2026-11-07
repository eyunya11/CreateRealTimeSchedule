package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import io.github.eyunya11.createrealtimeschedule.railway.SignalBlocks;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Duty;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Train;
import io.github.eyunya11.createrealtimeschedule.traffic.Traffic;
import io.github.eyunya11.createrealtimeschedule.traffic.Traffic.Run;
import io.github.eyunya11.createrealtimeschedule.traffic.Traffic.Stint;
import io.github.eyunya11.createrealtimeschedule.traffic.Traffic.Trip;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpec;

/**
 * The trains of a timetable run on time together on their track, with the signals between them
 * ({@link Traffic}): how long each leg of each train takes with the other trains where the timetable
 * has them. It has no Minecraft types in it so that it can be tested without a game.
 *
 * <p>Each duty is one set, which runs its trains one after another ({@link DutyPlan#calls}); a train
 * in no duty is a set of its own. A set appears at its first station when it is to leave there, and
 * leaves each station at the timetable's time, or after its minimum stop if it comes in late. The set
 * of a duty stands in its depot once it is back, for the rest of the day; that of a train in no duty,
 * which the timetable says nothing more of, is taken off the line where the train ends. It runs at full throttle. Each train starts under no limit
 * of a sign or a signal, and carries them from stop to stop. A set whose way from one station to the
 * next is not on the track runs no further.
 *
 * <p>The way from one station to another is the one leg the track facts have for the two names, so
 * every set comes in at the same platform of a station, where Create would send it to one that is free
 * ({@link Traffic}): at a depot or a terminus where one set stands while another comes in, the second
 * waits at red, or never gets in, though in the game it would go to another platform.
 */
public final class TimetableTraffic {

    /** A leg of a train of the timetable: from its stop {@code stop} (0 for the first) to the next. */
    public record Key(String train, int stop) {}

    private final Map<Key, Trip> trips;

    private TimetableTraffic(Map<Key, Trip> trips) {
        this.trips = trips;
    }

    /**
     * Runs the trains of {@code timetable} on the day it plans, its times counted in ticks from 00:00.
     *
     * @param legs               the ways between stations, by their two ends and the track tag, as
     *                           {@link TrackFacts#legs} has them
     * @param blocks             the signal blocks of the network the legs are of
     * @param spec               how fast the trains go
     * @param defaultMinimumStop the minimum stop, in ticks, where the timetable gives none
     * @param defaultLength      the length of a train, in blocks, where the timetable gives none
     */
    public static TimetableTraffic of(Timetable timetable, Map<List<String>, Leg> legs, SignalBlocks blocks, TrainSpec spec,
        long defaultMinimumStop, int defaultLength) {
        List<Run> runs = new ArrayList<>();
        List<List<Key>> keys = new ArrayList<>();
        Map<String, Train> byNumber = new HashMap<>();
        for (Train train : timetable.trains())
            byNumber.putIfAbsent(train.number(), train);
        Set<String> inDuties = new HashSet<>();
        List<Duty> sets = new ArrayList<>();
        Set<Duty> duties = new HashSet<>(timetable.duties());
        for (Duty duty : timetable.duties()) {
            inDuties.addAll(duty.trains());
            sets.add(duty);
        }
        for (Train train : timetable.trains())
            if (!inDuties.contains(train.number()))
                sets.add(new Duty(train.number(), "", List.of(train.number())));
        for (Duty set : sets) {
            List<Call> calls;
            try {
                calls = DutyPlan.calls(timetable, set);
            } catch (IllegalArgumentException e) {
                // A duty of a train that is not there: the timetable has a problem, and the set is left out.
                continue;
            }
            List<Stint> stints = new ArrayList<>();
            List<Key> legKeys = new ArrayList<>();
            int stop = 0;
            for (int i = 0; i + 1 < calls.size(); i++) {
                Call call = calls.get(i);
                boolean newTrain = i == 0 || !call.leavingTrain().equals(call.arrivingTrain());
                stop = newTrain ? 0 : stop + 1;
                Leg leg = legs.get(List.of(call.station(), calls.get(i + 1).station(), call.tag()));
                if (leg == null || !call.hasDepart())
                    break;
                Train train = byNumber.get(call.leavingTrain());
                int length = train == null ? defaultLength : train.lengthOr(defaultLength);
                stints.add(new Stint(leg, DutyPlan.at(0, call.depart()), i == 0 ? 0 : DutyPlan.minimumStop(call, defaultMinimumStop),
                    newTrain, length));
                legKeys.add(new Key(call.leavingTrain(), stop));
            }
            if (stints.isEmpty())
                continue;
            runs.add(new Run(set.id(), spec, 1, stints, duties.contains(set) && stints.size() == calls.size() - 1));
            keys.add(legKeys);
        }
        Traffic.Result result = Traffic.run(blocks, runs);
        Map<Key, Trip> trips = new HashMap<>();
        for (int run = 0; run < runs.size(); run++) {
            List<Trip> ran = result.trips().get(run);
            for (int i = 0; i < ran.size(); i++)
                trips.put(keys.get(run).get(i), ran.get(i));
        }
        return new TimetableTraffic(trips);
    }

    /** How the leg from stop {@code stop} of {@code train} to the next went, if the train set out on it. */
    public Optional<Trip> trip(String train, int stop) {
        return Optional.ofNullable(trips.get(new Key(train, stop)));
    }

    /** The ticks the leg from stop {@code stop} of {@code train} to the next took, if it got there. */
    public OptionalLong ticks(String train, int stop) {
        Trip trip = trips.get(new Key(train, stop));
        return trip == null || !trip.hasArrived() ? OptionalLong.empty() : OptionalLong.of(trip.ticks());
    }
}
