package io.github.eyunya11.createrealtimeschedule.clock;

import java.time.Clock;
import java.time.LocalTime;

import io.github.eyunya11.createrealtimeschedule.network.ClockSyncPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The server's side of the clock: reading it, setting it, and telling clients about it.
 *
 * <p>Other code that needs the time (the wait condition, later) should come through {@link #now}
 * rather than adding the offset itself, so there is one place that knows where it is kept.
 */
public final class ClockService {

    private ClockService() {}

    /**
     * The world's clock data. A clock seen for the first time is started at the configured start
     * time, as of the game time it is first looked at.
     */
    public static RealClockData data(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        RealClockData data = overworld.getDataStorage().computeIfAbsent(RealClockData.FACTORY, RealClockData.NAME);
        initialize(data, overworld.getGameTime());
        return data;
    }

    /** Gives a clock that has never been started its start time, as of the given game time. */
    public static void initialize(RealClockData data, long gameTime) {
        if (!data.isInitialized())
            data.setOffset(RealClock.offsetFor(gameTime, RealClockConfig.startTimeTicks()));
    }

    /** The clock value now: ticks since the start of day 0, never reset by the sky or by sleeping. */
    public static long now(MinecraftServer server) {
        return RealClock.clockTicks(server.overworld().getGameTime(), data(server).offset());
    }

    /** Moves the clock to the given time of day on the day it is on, and tells every client. */
    public static void setTimeOfDay(MinecraftServer server, long timeOfDayTicks) {
        long gameTime = server.overworld().getGameTime();
        RealClockData data = data(server);
        long current = RealClock.clockTicks(gameTime, data.offset());
        data.setOffset(RealClock.offsetToSetTimeOfDay(gameTime, current, timeOfDayTicks));
        broadcast(server);
    }

    /**
     * Sets the clock to the time of day the given wall clock reads, in its own time zone.
     *
     * @return the time of day the clock was set to, in ticks
     */
    public static long alignToWallClock(MinecraftServer server, Clock wallClock) {
        long timeOfDay = RealClock.timeOfDay(LocalTime.now(wallClock));
        setTimeOfDay(server, timeOfDay);
        return timeOfDay;
    }

    public static void broadcast(MinecraftServer server) {
        PacketDistributor.sendToAllPlayers(new ClockSyncPayload(data(server).offset()));
    }

    public static void sendTo(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new ClockSyncPayload(data(player.server).offset()));
    }
}
