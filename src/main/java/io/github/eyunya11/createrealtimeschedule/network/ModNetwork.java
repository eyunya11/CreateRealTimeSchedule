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
        // Not optional: the real_time_of_day condition is saved in schedules and sent to clients, and a
        // client that does not know it cannot read the schedules that hold it. Leaving the channel
        // required keeps a client without this mod off a server that has it, with a message saying why.
        event.registrar(PROTOCOL_VERSION)
            .playToClient(ClockSyncPayload.TYPE, ClockSyncPayload.STREAM_CODEC, ClientClock::onSync);
    }
}
