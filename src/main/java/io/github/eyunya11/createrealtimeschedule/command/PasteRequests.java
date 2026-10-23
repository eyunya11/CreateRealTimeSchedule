package io.github.eyunya11.createrealtimeschedule.command;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * The pastes the server has asked clients for and not had yet: a token for each player, good for a
 * while. A paste that comes back with the token in time is taken, once; any other is not, so that a
 * client cannot load a timetable without a command of an operator having asked it to. It has no
 * Minecraft types in it so that it can be tested without a game.
 */
public final class PasteRequests {

    private record Request(long token, long until) {}

    private final long timeout;
    private final RandomGenerator random;
    private final Map<UUID, Request> waiting = new HashMap<>();

    /**
     * @param timeout how many ticks a request waits for its answer
     * @param random  where the tokens come from
     */
    public PasteRequests(long timeout, RandomGenerator random) {
        this.timeout = timeout;
        this.random = random;
    }

    /** Asks {@code player} for a paste at tick {@code now}, in place of any it was asked for before; the token its answer must carry. */
    public long ask(UUID player, long now) {
        long token = random.nextLong();
        waiting.put(player, new Request(token, now + timeout));
        return token;
    }

    /**
     * Whether a paste from {@code player} with {@code token} at tick {@code now} answers what it was
     * asked; if so, the request is done with. A wrong token leaves the request waiting; one that has
     * run out of time is forgotten.
     */
    public boolean take(UUID player, long token, long now) {
        Request request = waiting.get(player);
        if (request == null)
            return false;
        if (now > request.until() || now < request.until() - timeout) {
            waiting.remove(player);
            return false;
        }
        if (request.token() != token)
            return false;
        waiting.remove(player);
        return true;
    }
}
