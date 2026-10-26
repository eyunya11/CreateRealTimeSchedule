package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.List;
import java.util.Objects;

import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Stop;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Wait;

/**
 * One stop a duty's rolling stock makes, as the timetable has it. Where one train of the duty ends and
 * the next starts, at the same station, the two are one call: the set arrives as the one and leaves as
 * the other.
 *
 * @param station       the station, as Create names it
 * @param arrive        when the set arrives, as {@code ServiceTime} seconds, or {@link Stop#NONE} at the first call
 * @param depart        when it leaves, or {@link Stop#NONE} at the last call
 * @param arrivingTrain the number of the train it arrives as; empty at the first call
 * @param leavingTrain  the number of the train it leaves as; empty at the last call
 * @param minimumStop   how many seconds a late set stands here at the least, as the timetable has it
 *                      for the stop it leaves from ({@code Timetable.minimumStop}), or {@link Stop#NONE}
 *                      for the server's config
 * @param waits         the trains the set waits for before it leaves, as the stop it leaves from has
 *                      them, each with its station filled in ({@link Wait#station})
 */
public record Call(String station, int arrive, int depart, String arrivingTrain, String leavingTrain, int minimumStop, List<Wait> waits) {

    public Call {
        Objects.requireNonNull(station, "station");
        Objects.requireNonNull(arrivingTrain, "arrivingTrain");
        Objects.requireNonNull(leavingTrain, "leavingTrain");
        waits = List.copyOf(waits);
    }

    /** A call that waits for no train. */
    public Call(String station, int arrive, int depart, String arrivingTrain, String leavingTrain, int minimumStop) {
        this(station, arrive, depart, arrivingTrain, leavingTrain, minimumStop, List.of());
    }

    /** A call with the server's minimum stop. */
    public Call(String station, int arrive, int depart, String arrivingTrain, String leavingTrain) {
        this(station, arrive, depart, arrivingTrain, leavingTrain, Stop.NONE);
    }

    public boolean hasArrive() {
        return arrive != Stop.NONE;
    }

    public boolean hasDepart() {
        return depart != Stop.NONE;
    }

    /**
     * When the set comes in: its arrival, or its departure at a call the timetable gives only a
     * departure, where the set stands no longer than it takes to stop and go; {@link Stop#NONE} at the
     * first call.
     */
    public int comesIn() {
        return hasArrive() ? arrive : depart;
    }
}
