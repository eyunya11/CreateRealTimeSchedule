package io.github.eyunya11.createrealtimeschedule.clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;

class RealClockTest {

    @Test
    void aDayIsTwentyFourRealHoursOfTicks() {
        assertEquals(1_728_000L, RealClock.TICKS_PER_DAY);
        assertEquals(72_000L, RealClock.TICKS_PER_HOUR);
        assertEquals(1_200L, RealClock.TICKS_PER_MINUTE);
        assertEquals(72L, RealClock.SKY_SCALE);
    }

    @Test
    void clockIsGameTimePlusOffset() {
        assertEquals(1_500L, RealClock.clockTicks(1_000L, 500L));
        assertEquals(500L, RealClock.clockTicks(1_000L, -500L));
    }

    @Test
    void dayAndTimeOfDaySplitTheClockAtMidnight() {
        assertEquals(0L, RealClock.day(0L));
        assertEquals(0L, RealClock.timeOfDay(0L));
        assertEquals(0L, RealClock.day(RealClock.TICKS_PER_DAY - 1));
        assertEquals(RealClock.TICKS_PER_DAY - 1, RealClock.timeOfDay(RealClock.TICKS_PER_DAY - 1));
        assertEquals(1L, RealClock.day(RealClock.TICKS_PER_DAY));
        assertEquals(0L, RealClock.timeOfDay(RealClock.TICKS_PER_DAY));
        assertEquals(41L, RealClock.day(41L * RealClock.TICKS_PER_DAY + 5));
        assertEquals(5L, RealClock.timeOfDay(41L * RealClock.TICKS_PER_DAY + 5));
    }

    @Test
    void aClockBeforeDayZeroStillSplitsCleanly() {
        assertEquals(-1L, RealClock.day(-1L));
        assertEquals(RealClock.TICKS_PER_DAY - 1, RealClock.timeOfDay(-1L));
    }

    @Test
    void ticksOfCountsFromMidnight() {
        assertEquals(0L, RealClock.ticksOf(0, 0, 0));
        assertEquals(20L, RealClock.ticksOf(0, 0, 1));
        assertEquals(1_200L, RealClock.ticksOf(0, 1, 0));
        assertEquals(72_000L, RealClock.ticksOf(1, 0, 0));
        assertEquals(RealClock.TICKS_PER_DAY - 20, RealClock.ticksOf(23, 59, 59));
    }

    @Test
    void ticksOfRejectsFieldsOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> RealClock.ticksOf(24, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> RealClock.ticksOf(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> RealClock.ticksOf(0, 60, 0));
        assertThrows(IllegalArgumentException.class, () -> RealClock.ticksOf(0, 0, 60));
    }

    @Test
    void parsesHourMinuteAndOptionalSecond() {
        assertEquals(RealClock.ticksOf(7, 30, 0), RealClock.parseTimeOfDay("7:30").getAsLong());
        assertEquals(RealClock.ticksOf(7, 30, 0), RealClock.parseTimeOfDay("07:30").getAsLong());
        assertEquals(RealClock.ticksOf(23, 59, 59), RealClock.parseTimeOfDay("23:59:59").getAsLong());
        assertEquals(RealClock.ticksOf(0, 0, 0), RealClock.parseTimeOfDay("0:00").getAsLong());
        assertEquals(RealClock.ticksOf(6, 5, 9), RealClock.parseTimeOfDay(" 6:05:09 ").getAsLong());
    }

    @Test
    void parseRefusesAnythingElse() {
        for (String bad : new String[] {"", "7", "7:5", "24:00", "7:60", "7:30:60", "07:30:5", "7.30", "a:bc", "7:30:15:01", "-1:30"})
            assertFalse(RealClock.parseTimeOfDay(bad).isPresent(), bad);
    }

    @Test
    void formatsAsHourMinuteSecond() {
        assertEquals("00:00:00", RealClock.format(0L));
        assertEquals("07:05:09", RealClock.format(RealClock.ticksOf(7, 5, 9)));
        assertEquals("23:59:59", RealClock.format(RealClock.ticksOf(23, 59, 59)));
        // A value past midnight wraps rather than printing hour 24.
        assertEquals("00:00:01", RealClock.format(RealClock.TICKS_PER_DAY + 20));
    }

    @Test
    void parseAndFormatAgree() {
        for (String text : new String[] {"00:00:00", "06:00:00", "12:34:56", "23:59:59"})
            assertEquals(text, RealClock.format(RealClock.parseTimeOfDay(text).getAsLong()));
    }

    @Test
    void wallClockReadingBecomesTicksWithFiftyMillisecondsToTheTick() {
        assertEquals(RealClock.ticksOf(3, 4, 5), RealClock.timeOfDay(LocalTime.of(3, 4, 5)));
        assertEquals(RealClock.ticksOf(3, 4, 5) + 10, RealClock.timeOfDay(LocalTime.of(3, 4, 5, 500_000_000)));
        assertEquals(RealClock.ticksOf(3, 4, 5) + 1, RealClock.timeOfDay(LocalTime.of(3, 4, 5, 50_000_000)));
        assertEquals(RealClock.ticksOf(3, 4, 5), RealClock.timeOfDay(LocalTime.of(3, 4, 5, 49_999_999)));
    }

    @Test
    void offsetForPutsTheClockWhereAskedAtTheGivenGameTime() {
        long gameTime = 123_456L;
        long wanted = RealClock.ticksOf(6, 0, 0);
        assertEquals(wanted, RealClock.clockTicks(gameTime, RealClock.offsetFor(gameTime, wanted)));
    }

    @Test
    void settingTheTimeKeepsTheDay() {
        long gameTime = 5_000_000L;
        long now = 3L * RealClock.TICKS_PER_DAY + RealClock.ticksOf(9, 0, 0);
        long offset = RealClock.offsetToSetTimeOfDay(gameTime, now, RealClock.ticksOf(7, 0, 0));
        long after = RealClock.clockTicks(gameTime, offset);
        assertEquals(3L, RealClock.day(after));
        assertEquals(RealClock.ticksOf(7, 0, 0), RealClock.timeOfDay(after));
    }

    @Test
    void skyIsWhereTheClockSaysItIs() {
        // Vanilla day time 0 is sunrise, 6,000 noon, 12,000 sunset and 18,000 midnight.
        assertEquals(18_000L, RealClock.skyDayTime(RealClock.ticksOf(0, 0, 0)));
        assertEquals(0L, RealClock.skyDayTime(RealClock.ticksOf(6, 0, 0)) % 24_000L);
        assertEquals(6_000L, RealClock.skyDayTime(RealClock.ticksOf(12, 0, 0)) % 24_000L);
        assertEquals(12_000L, RealClock.skyDayTime(RealClock.ticksOf(18, 0, 0)) % 24_000L);
    }

    @Test
    void anHourOfClockIsAThousandTicksOfSky() {
        long noon = RealClock.ticksOf(12, 0, 0);
        assertEquals(1_000L, RealClock.skyDayTime(noon + RealClock.TICKS_PER_HOUR) - RealClock.skyDayTime(noon));
        assertEquals(24_000L, RealClock.skyDayTime(noon + RealClock.TICKS_PER_DAY) - RealClock.skyDayTime(noon));
    }

    @Test
    void skyNeverRunsBackwardsAndMovesOneTickEveryThreePointSixSeconds() {
        long previous = RealClock.skyDayTime(0L);
        long ticksAtPrevious = 0L;
        for (long clock = 1; clock <= 10 * RealClock.SKY_SCALE; clock++) {
            long sky = RealClock.skyDayTime(clock);
            assertTrue(sky >= previous, "went back at " + clock);
            if (sky != previous) {
                assertEquals(RealClock.SKY_SCALE, clock - ticksAtPrevious);
                ticksAtPrevious = clock;
                previous = sky;
            }
        }
    }

    @Test
    void skyKeepsMovingAcrossDayZero() {
        assertEquals(18_000L - 1, RealClock.skyDayTime(-1L));
        assertEquals(18_000L - 1, RealClock.skyDayTime(-RealClock.SKY_SCALE));
        assertEquals(18_000L - 2, RealClock.skyDayTime(-RealClock.SKY_SCALE - 1));
    }
}
