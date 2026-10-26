package io.github.eyunya11.createrealtimeschedule.network;

import java.util.random.RandomGenerator;

import org.jetbrains.annotations.Nullable;

/**
 * The copy a client has asked the server for and not had yet: a token, good for a while. Text that
 * comes back with the token in time goes on the clipboard, once; any other does not, so that a server
 * cannot put anything on a player's clipboard but what the player has just asked it for. It has no
 * Minecraft types in it so that it can be tested without a game.
 */
public final class ClipboardRequests {

    private record Request(long token, long until) {}

    private final long timeout;
    private final RandomGenerator random;
    private @Nullable Request waiting;

    /**
     * @param timeout how long a request waits for its answer, in the units {@code now} is given in
     * @param random  where the tokens come from
     */
    public ClipboardRequests(long timeout, RandomGenerator random) {
        this.timeout = timeout;
        this.random = random;
    }

    /** Asks for a copy at {@code now}, in place of any asked for before; the token its answer must carry. */
    public long ask(long now) {
        long token = random.nextLong();
        waiting = new Request(token, now + timeout);
        return token;
    }

    /**
     * Whether an answer with {@code token} at {@code now} answers what was asked; if so, the request is
     * done with. A wrong token leaves the request waiting; one that has run out of time is forgotten.
     */
    public boolean take(long token, long now) {
        Request request = waiting;
        if (request == null)
            return false;
        if (now > request.until() || now < request.until() - timeout) {
            waiting = null;
            return false;
        }
        if (request.token() != token)
            return false;
        waiting = null;
        return true;
    }
}
