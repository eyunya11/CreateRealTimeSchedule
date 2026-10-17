package io.github.eyunya11.createrealtimeschedule.network;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.client.ClientClock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = Createrealtimeschedule.MODID)
public final class ModNetwork {

    private static final String PROTOCOL_VERSION = "1";

    private ModNetwork() {}

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // Optional, so a client without this mod can still join a server that has it: such a client
        // simply never learns the offset, which only matters for drawing the clock.
        event.registrar(PROTOCOL_VERSION)
            .optional()
            .playToClient(ClockSyncPayload.TYPE, ClockSyncPayload.STREAM_CODEC, ClientClock::onSync);
    }
}
