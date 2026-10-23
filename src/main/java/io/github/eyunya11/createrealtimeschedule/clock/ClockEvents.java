package io.github.eyunya11.createrealtimeschedule.clock;

import java.time.Clock;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.ModSettings;
import io.github.eyunya11.createrealtimeschedule.command.RtClockCommand;
import io.github.eyunya11.createrealtimeschedule.command.RtNetworkCommand;
import io.github.eyunya11.createrealtimeschedule.command.RtTimetableCommand;
import io.github.eyunya11.createrealtimeschedule.operation.OperationService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Hooks the clock into the game: its start, its players, the sky and the operation each tick, and the commands. */
@EventBusSubscriber(modid = Createrealtimeschedule.MODID)
public final class ClockEvents {

    private ClockEvents() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        // Reading it creates the clock on the first start of a world.
        ClockService.data(server);
        if (ModSettings.alignOnStart())
            ClockService.alignToWallClock(server, Clock.systemDefaultZone());
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            ClockService.sendTo(player);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        SkySync.apply(event.getServer(), ModSettings.syncSky());
        OperationService.tick(event.getServer());
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        RtClockCommand.register(event.getDispatcher());
        RtNetworkCommand.register(event.getDispatcher());
        RtTimetableCommand.register(event.getDispatcher());
    }
}
