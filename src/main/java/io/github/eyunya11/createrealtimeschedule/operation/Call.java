package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.Objects;

import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Stop;

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
 */
public record Call(String station, int arrive, int depart, String arrivingTrain, String leavingTrain) {

    public Call {
        Objects.requireNonNull(station, "station");
        Objects.requireNonNull(arrivingTrain, "arrivingTrain");
        Objects.requireNonNull(leavingTrain, "leavingTrain");
    }

    public boolean hasArrive() {
        return arrive != Stop.NONE;
    }

    public boolean hasDepart() {
        return depart != Stop.NONE;
    }
}
