package io.github.eyunya11.createrealtimeschedule.clock;

import java.time.LocalTime;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The arithmetic of the real-time clock, with no Minecraft types in it so that it can be tested
 * without a game.
 *
 * <p>The clock is {@code gameTime + offset}: twenty ticks to a second and 86,400 seconds to a day,
 * which is 72 times a vanilla day. Everything the game does in ticks (trains included) therefore
 * moves the clock by the same amount, and pausing or slowing the server pauses or slows both.
 * {@code offset} is the one thing that is saved and the one thing a command changes.
 *
 * <p>A <em>clock value</em> is an absolute count of ticks that includes the day, so a time that is
 * waited for can be stored as a single long and still mean the same thing after midnight.
 */
public final class RealClock {

    public static final int TICKS_PER_SECOND = 20;
    public static final long TICKS_PER_MINUTE = 60L * TICKS_PER_SECOND;
    public static final long TICKS_PER_HOUR = 60L * TICKS_PER_MINUTE;
    public static final long TICKS_PER_DAY = 24L * TICKS_PER_HOUR;

    /** The length of a vanilla day, which the sky is stretched from. */
    public static final long VANILLA_TICKS_PER_DAY = 24_000L;
    /** How many clock ticks the sky takes to move one vanilla tick: 72. */
    public static final long SKY_SCALE = TICKS_PER_DAY / VANILLA_TICKS_PER_DAY;
    /** Vanilla day time 0 is 06:00, so 00:00 is 18,000. */
    public static final long SKY_OFFSET = 18_000L;

    private static final Pattern TIME_OF_DAY = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");

    private RealClock() {}

    /** The clock value at the given game time. */
    public static long clockTicks(long gameTime, long offset) {
        return gameTime + offset;
    }

    /** Which day the clock value falls on, counting from day 0. */
    public static long day(long clockTicks) {
        return Math.floorDiv(clockTicks, TICKS_PER_DAY);
    }

    /** Ticks since 00:00 on the clock value's day, from 0 up to {@link #TICKS_PER_DAY} - 1. */
    public static long timeOfDay(long clockTicks) {
        return Math.floorMod(clockTicks, TICKS_PER_DAY);
    }

    /** The time of day in ticks, for a wall clock reading. */
    public static long timeOfDay(LocalTime time) {
        return (long) time.toSecondOfDay() * TICKS_PER_SECOND + time.getNano() / 50_000_000L;
    }

    /**
     * Ticks since 00:00 for the given time.
     *
     * @throws IllegalArgumentException if a field is out of range
     */
    public static long ticksOf(int hour, int minute, int second) {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59 || second < 0 || second > 59)
            throw new IllegalArgumentException("not a time of day: " + hour + ":" + minute + ":" + second);
        return hour * TICKS_PER_HOUR + minute * TICKS_PER_MINUTE + second * (long) TICKS_PER_SECOND;
    }

    /** Reads {@code H:MM} or {@code H:MM:SS}; empty if the text is anything else. */
    public static OptionalLong parseTimeOfDay(String text) {
        Matcher matcher = TIME_OF_DAY.matcher(text.trim());
        if (!matcher.matches())
            return OptionalLong.empty();
        int hour = Integer.parseInt(matcher.group(1));
        int minute = Integer.parseInt(matcher.group(2));
        int second = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        if (hour > 23 || minute > 59 || second > 59)
            return OptionalLong.empty();
        return OptionalLong.of(ticksOf(hour, minute, second));
    }

    /** {@code HH:MM:SS} for a time of day. */
    public static String format(long timeOfDayTicks) {
        long seconds = Math.floorMod(timeOfDayTicks, TICKS_PER_DAY) / TICKS_PER_SECOND;
        return String.format("%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    /** A clock value as a person reads it: the day of the clock and the time of day, such as {@code day 3 08:10:00}. */
    public static String show(long clockTicks) {
        return "day " + day(clockTicks) + " " + format(clockTicks);
    }

    /** The offset that makes the clock read {@code clockTicks} at {@code gameTime}. */
    public static long offsetFor(long gameTime, long clockTicks) {
        return clockTicks - gameTime;
    }

    /**
     * The offset that moves the clock to {@code targetTimeOfDay} on the day it is on now. Setting
     * the time never changes the day, so a clock set back to 07:00 at 09:00 goes back two hours.
     */
    public static long offsetToSetTimeOfDay(long gameTime, long currentClockTicks, long targetTimeOfDay) {
        return offsetFor(gameTime, day(currentClockTicks) * TICKS_PER_DAY + targetTimeOfDay);
    }

    /**
     * The vanilla day time that puts the sky where the clock says it is: 12:00 is noon (6,000),
     * 00:00 is midnight (18,000), and the day count carries over so the moon changes phase once a
     * real day.
     */
    public static long skyDayTime(long clockTicks) {
        return Math.floorDiv(clockTicks, SKY_SCALE) + SKY_OFFSET;
    }

    /**
     * The "repeat every" choices of the time-of-day condition, in the order its screen lists them:
     * daily, then every 12, 6, 4, 3, 2 and 1 hour, then every 45, 30 and 15 minutes. Create's own
     * condition counts the same choices in vanilla day time; here they are real time. Every one
     * divides a day, so a slot falls on the same time of day every day.
     */
    private static final long[] ROTATIONS = {
        TICKS_PER_DAY, 12 * TICKS_PER_HOUR, 6 * TICKS_PER_HOUR, 4 * TICKS_PER_HOUR, 3 * TICKS_PER_HOUR,
        2 * TICKS_PER_HOUR, TICKS_PER_HOUR, 45 * TICKS_PER_MINUTE, 30 * TICKS_PER_MINUTE, 15 * TICKS_PER_MINUTE
    };

    /** How many "repeat every" choices there are. */
    public static final int ROTATION_CHOICES = ROTATIONS.length;

    /** The length of a "repeat every" choice in ticks; a stored value out of range reads as daily. */
    public static long rotationTicks(int index) {
        return index >= 0 && index < ROTATIONS.length ? ROTATIONS[index] : ROTATIONS[0];
    }

    /**
     * The first slot strictly after {@code after}. A slot is a clock value whose time of day is
     * {@code timeOfDay}, repeating every {@code rotation} ticks, so with a rotation of a day and a
     * time of 08:10 the slots are every day's 08:10.
     *
     * <p>Strictly after is what keeps a stop that asks for the time the train has just left on from
     * reading its own departure as "already past": it books the next slot instead.
     */
    public static long nextSlotAfter(long after, long timeOfDay, long rotation) {
        long next = after + 1;
        return next + Math.floorMod(timeOfDay - next, rotation);
    }

    /** The slot nearest to {@code moment}; one exactly halfway between two is the later of them. */
    public static long nearestSlot(long moment, long timeOfDay, long rotation) {
        long earlier = moment - Math.floorMod(moment - timeOfDay, rotation);
        long later = earlier + rotation;
        return moment - earlier < later - moment ? earlier : later;
    }
}
