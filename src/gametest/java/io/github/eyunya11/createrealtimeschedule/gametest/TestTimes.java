package io.github.eyunya11.createrealtimeschedule.gametest;

import io.github.eyunya11.createrealtimeschedule.clock.RealClock;

/** Clock values for the game tests to write and to read in a failure message. */
final class TestTimes {

    private TestTimes() {}

    /** A clock value: the given day of the clock, at the given time. */
    static long clock(long day, int hour, int minute, int second) {
        return day * RealClock.TICKS_PER_DAY + RealClock.ticksOf(hour, minute, second);
    }

    /** A clock value as a person reads it: the day and the time. */
    static String show(long clockTicks) {
        return RealClock.show(clockTicks);
    }
}
