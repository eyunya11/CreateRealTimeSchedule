package io.github.eyunya11.createrealtimeschedule.diagram;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * At most one answer to each player in a given number of ticks, so that a client that asks for the
 * diagram far more often than once a second cannot make the server send it over and over. It has no
 * Minecraft types in it so that it can be tested without a game.
 */
public final class RequestLimit {

    private final long gap;
    private final Map<UUID, Long> last = new HashMap<>();

    /** A limit of one answer each {@code gap} ticks. */
    public RequestLimit(long gap) {
        this.gap = gap;
    }

    /**
     * Whether {@code player} may be answered at server tick {@code tick}, which counts it as answered
     * if so. A tick before the last answer, as when another world has been opened since, starts again.
     */
    public boolean allow(UUID player, long tick) {
        Long before = last.get(player);
        if (before != null && tick >= before && tick - before < gap)
            return false;
        last.put(player, tick);
        return true;
    }

    /** Forgets {@code player}, who has left. */
    public void forget(UUID player) {
        last.remove(player);
    }
}
