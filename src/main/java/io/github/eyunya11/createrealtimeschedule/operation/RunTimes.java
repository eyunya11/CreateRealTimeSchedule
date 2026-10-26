package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.OptionalLong;

/**
 * How long the track takes from one station to another, which only the game knows: put behind an
 * interface so that {@link Forecast} and {@link Recovery} can be tested without a game.
 */
@FunctionalInterface
public interface RunTimes {

    /** Nothing is known of the track: the timetable's own running times are taken. */
    RunTimes NONE = (from, to) -> OptionalLong.empty();

    /**
     * The least ticks a train takes on the track from a station named {@code from} to one named
     * {@code to}, with nothing in its way; empty if that is not known.
     */
    OptionalLong ticks(String from, String to);
}
