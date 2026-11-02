package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.OptionalLong;

/**
 * What {@link Recovery} needs to know of the track and the trains on it around one set before it holds
 * the set at a station, which only the game knows: put behind an interface so that it can be tested
 * without a game.
 */
public interface Reach extends RunTimes {

    /** How many stations of the name there are on the set's track network. */
    default int platforms(String station) {
        return platforms(station, "");
    }

    /**
     * How many stations of the name on the set's track network a train held to the track tag
     * {@code tag} can stand at: those where the track is open to it; all of them for no tag ({@code ""}).
     */
    int platforms(String station, String tag);

    /** How many trains other than the set stand at a station of the name now. */
    default int standing(String station) {
        return standing(station, "");
    }

    /** How many trains other than the set stand now at a station of the name that is open to the track tag {@code tag}. */
    int standing(String station, String tag);

    /**
     * Whether a train held to the track tag {@code other} could stand at a station of the name that is
     * open to {@code tag} as well, and so take a platform from a train of that tag. A train of no tag
     * ({@code ""}) can stand at any.
     */
    boolean shares(String station, String tag, String other);

    /**
     * The ticks the set takes from where it stands to a station named {@code station}, held to the
     * track tag {@code tag} (empty for none), by the way it would be sent there; empty if there is no
     * way, as for a set that would have to run backwards and cannot.
     */
    OptionalLong ticksTo(String station, String tag);
}
