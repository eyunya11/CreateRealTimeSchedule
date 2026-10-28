package io.github.eyunya11.createrealtimeschedule.operation;

/**
 * Whether the track has changed since what the operation keeps of it was worked out ({@link TrackFacts}),
 * going by a fingerprint of the track that is taken now and then ({@code CreateNetwork.fingerprint}).
 * The facts are to be worked out again once the track has stayed as it is for a while, and not while a
 * player is still laying it: each time would read the whole network. A track that is changed and then
 * put back as it was needs nothing. It has no Minecraft types in it so that it can be tested without a
 * game.
 */
public final class TrackWatch {

    private final int settleTicks;
    /** The fingerprint of the track the facts were worked out from. */
    private long workedOut;
    /** The fingerprint the track had when it was last looked at, and the tick from which it has had it. */
    private long seen;
    private int seenSince;

    /** @param settleTicks how long the track has to stay as it is before the facts are worked out again, in ticks */
    public TrackWatch(int settleTicks) {
        this.settleTicks = settleTicks;
    }

    /** The facts have been worked out from the track as fingerprint {@code print} has it. */
    public void workedOut(long print) {
        workedOut = print;
        seen = print;
    }

    /**
     * Whether the facts are to be worked out again, with the track as fingerprint {@code print} has it
     * at {@code tick}: it is not as it was when they were, and it has stayed as it is now for the
     * settling time.
     */
    public boolean settledOnAChange(long print, int tick) {
        if (print != seen) {
            seen = print;
            seenSince = tick;
        }
        return print != workedOut && tick - seenSince >= settleTicks;
    }
}
