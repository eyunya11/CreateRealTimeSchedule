package io.github.eyunya11.createrealtimeschedule.schedule;

import java.util.OptionalLong;

import org.jetbrains.annotations.Nullable;

import io.github.eyunya11.createrealtimeschedule.clock.RealClock;

/**
 * When a train leaves a stop that waits on {@code real_time_of_day}, with no Minecraft types in it
 * so that it can be tested without a game.
 *
 * <p>Every stop has a <em>slot</em> T, the clock value the train is meant to leave at, and the train
 * leaves at {@code D = max(T, A + M)}: when it arrives early (A) it waits for the slot, and when it
 * arrives late it stops for the minimum stop M and goes. A late train therefore gets its dwell
 * shortened by the lateness and no more than that.
 *
 * <p>T is chained off the slot the train used at its last such stop: the first slot after that one
 * that matches this stop's time. This is what lets a train that is late stay late rather than
 * skip ahead to the next slot, and what lets a line that asks for the same time of day twice in a
 * round book the second one a day later rather than leaving at once.
 */
public final class DeparturePlanner {

    /**
     * What is remembered about a train between stops: the slot it booked at its last stop, and the
     * facts needed to book without double counting.
     *
     * @param slot      the slot booked at the last stop
     * @param stamp     the clock value at which that stop was booked
     * @param base      the slot that stop chained off, valid only if {@code hasBase}
     * @param hasBase   whether that stop chained off an earlier slot at all
     * @param signature identifies the schedule the slot was booked under; a different schedule
     *                  starts the chain again
     */
    public record SlotRecord(long slot, long stamp, long base, boolean hasBase, int signature) {}

    private DeparturePlanner() {}

    /**
     * The slot to chain off at a stop the train reached at {@code arrival}, if there is one.
     *
     * <p>Several conditions of one stop that are tried side by side (the "or" columns of the
     * schedule screen) all start in the same tick. They have to chain off the same slot, the one
     * from before this stop, rather than each off the one the column before it booked, which is
     * what the stamp is for.
     */
    public static OptionalLong previousSlot(@Nullable SlotRecord record, int signature, long arrival) {
        if (record == null || record.signature() != signature)
            return OptionalLong.empty();
        if (record.stamp() == arrival)
            return record.hasBase() ? OptionalLong.of(record.base()) : OptionalLong.empty();
        return OptionalLong.of(record.slot());
    }

    /**
     * The slot for a stop at {@code arrival} that asks for {@code timeOfDay}, repeating every
     * {@code rotation} ticks.
     *
     * <p>With a slot to chain off, the train takes the first one after it. Without one (the first
     * stop of a schedule) it takes the slot nearest to its arrival, and a slot exactly halfway
     * takes the later. A chained slot more than a whole rotation away from the arrival, past or to
     * come, no longer means anything for this train: it is a rotation behind everyone else, or the
     * clock has been moved under it. It starts again from the nearest slot, so that a train does not
     * run at the minimum stop for good, and a clock set back does not hold a train for hours.
     */
    public static long chooseSlot(OptionalLong previous, long arrival, long timeOfDay, long rotation) {
        if (previous.isPresent()) {
            long chained = RealClock.nextSlotAfter(previous.getAsLong(), timeOfDay, rotation);
            if (arrival - chained < rotation && chained - arrival <= rotation)
                return chained;
        }
        return RealClock.nearestSlot(arrival, timeOfDay, rotation);
    }

    /** When the train leaves: at the slot, or the minimum stop after arriving if that is later. */
    public static long departure(long slot, long arrival, long minimumStop) {
        return Math.max(slot, arrival + minimumStop);
    }

    /** What to remember after booking {@code slot} at {@code arrival}, chained off {@code previous}. */
    public static SlotRecord remember(long slot, long arrival, OptionalLong previous, int signature) {
        return new SlotRecord(slot, arrival, previous.orElse(0L), previous.isPresent(), signature);
    }
}
