package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.OptionalLong;

/**
 * What {@link Recovery} needs to know of the track and the trains on it around one set before it holds
 * the set at a station, which only the game knows: put behind an interface so that it can be tested
 * without a game.
 */
public interface Reach extends RunTimes {

    /** How many stations of the name there are on the set's track network. */
    int platforms(String station);

    /** How many trains other than the set stand at a station of the name now. */
    int standing(String station);

    /**
     * The ticks the set takes from where it stands to a station named {@code station}, by the way
     * Create would send it there; empty if Create finds no way, as for a set that would have to run
     * backwards and cannot.
     */
    OptionalLong ticksTo(String station);
}
