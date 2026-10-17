package io.github.eyunya11.createrealtimeschedule.client;

import java.util.OptionalLong;

import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.network.ClockSyncPayload;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The client's copy of the clock: just the offset the server last sent.
 *
 * <p>Deliberately free of client-only classes, so the payload handler that points at it can be
 * registered on a dedicated server without loading anything it does not have.
 */
public final class ClientClock {

    private static volatile boolean known;
    private static volatile long offset;

    private ClientClock() {}

    public static void onSync(ClockSyncPayload payload, IPayloadContext context) {
        offset = payload.offset();
        known = true;
    }

    /** Forgets the offset, for when the connection ends. */
    public static void reset() {
        known = false;
        offset = 0L;
    }

    /** The clock value in the given level, or empty before the server has said what the offset is. */
    public static OptionalLong now(Level level) {
        return known ? OptionalLong.of(RealClock.clockTicks(level.getGameTime(), offset)) : OptionalLong.empty();
    }
}
