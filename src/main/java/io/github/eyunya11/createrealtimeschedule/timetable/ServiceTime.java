package io.github.eyunya11.createrealtimeschedule.timetable;

import java.util.Locale;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A time as a timetable writes it, with no Minecraft types in it so that it can be tested without a
 * game.
 *
 * <p>It is a count of seconds from 00:00 of the day the timetable starts on, and it runs on past
 * midnight the way printed timetables do: a train that leaves at ten past midnight the next day
 * leaves at {@code 24:10}. That keeps the stops of one train in order as plain numbers, without a
 * date. The last time that can be written is {@code 47:59:59}.
 */
public final class ServiceTime {

    /** 48:00:00 in seconds: the first time that cannot be written. */
    public static final int LIMIT = 48 * 3600;

    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");

    private ServiceTime() {}

    /** Reads {@code H:MM}, {@code HH:MM} or {@code HH:MM:SS}; empty if the text is anything else. */
    public static OptionalInt parse(String text) {
        Matcher matcher = TIME.matcher(text);
        if (!matcher.matches())
            return OptionalInt.empty();
        int hour = Integer.parseInt(matcher.group(1));
        int minute = Integer.parseInt(matcher.group(2));
        int second = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        if (hour > 47 || minute > 59 || second > 59)
            return OptionalInt.empty();
        return OptionalInt.of(hour * 3600 + minute * 60 + second);
    }

    /**
     * Like {@link #parse}, for a text that is known to be a time.
     *
     * @throws IllegalArgumentException if it is not
     */
    public static int of(String text) {
        return parse(text).orElseThrow(() -> new IllegalArgumentException("not a time: " + text));
    }

    /**
     * Like {@link #format}, but a count of seconds that is no time of the service day comes out as
     * {@code -5 s} instead of an exception: for a message, and for text that has to be written even
     * though it is wrong, which no reader takes for a time.
     */
    public static String show(int seconds) {
        return seconds >= 0 && seconds < LIMIT ? format(seconds) : seconds + " s";
    }

    /**
     * {@code HH:MM}, or {@code HH:MM:SS} when there are seconds.
     *
     * @throws IllegalArgumentException if the seconds are not a time the timetable can write
     */
    public static String format(int seconds) {
        if (seconds < 0 || seconds >= LIMIT)
            throw new IllegalArgumentException("not a time of the service day: " + seconds + " s");
        int hour = seconds / 3600;
        int minute = seconds / 60 % 60;
        int second = seconds % 60;
        return second == 0
            ? String.format(Locale.ROOT, "%02d:%02d", hour, minute)
            : String.format(Locale.ROOT, "%02d:%02d:%02d", hour, minute, second);
    }
}
