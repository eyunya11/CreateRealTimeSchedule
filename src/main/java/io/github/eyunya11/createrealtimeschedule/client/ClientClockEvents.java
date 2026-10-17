package io.github.eyunya11.createrealtimeschedule.client;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

@EventBusSubscriber(modid = Createrealtimeschedule.MODID, value = Dist.CLIENT)
public final class ClientClockEvents {

    private ClientClockEvents() {}

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientClock.reset();
    }
}
