package io.github.eyunya11.createrealtimeschedule.operation;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.command.RtTimetableCommand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Hooks the operation into the game: its step each tick, and its command. */
@EventBusSubscriber(modid = Createrealtimeschedule.MODID)
public final class OperationEvents {

    private OperationEvents() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        OperationService.tick(event.getServer());
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        RtTimetableCommand.register(event.getDispatcher());
    }
}
