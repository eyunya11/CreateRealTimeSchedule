package io.github.eyunya11.createrealtimeschedule.operation;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.command.RtDiagramCommand;
import io.github.eyunya11.createrealtimeschedule.command.RtTimetableCommand;
import io.github.eyunya11.createrealtimeschedule.diagram.DiagramService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Hooks the operation into the game: its step each tick, its commands, and players leaving the diagram. */
@EventBusSubscriber(modid = Createrealtimeschedule.MODID)
public final class OperationEvents {

    private OperationEvents() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        OperationService.tick(event.getServer());
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            DiagramService.forget(player);
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        RtTimetableCommand.register(event.getDispatcher());
        RtDiagramCommand.register(event.getDispatcher());
    }
}
