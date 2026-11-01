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
     * The ticks the set takes from where it stands to a station named {@code station}, held to the
     * track tag {@code tag} (empty for none), by the way it would be sent there; empty if there is no
     * way, as for a set that would have to run backwards and cannot.
     */
    OptionalLong ticksTo(String station, String tag);
}
