package io.github.eyunya11.createrealtimeschedule.client;

import com.mojang.blaze3d.platform.InputConstants;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/** The diagram's key, which no key is bound to at first, and its ticks on the client. */
@EventBusSubscriber(modid = Createrealtimeschedule.MODID, value = Dist.CLIENT)
public final class DiagramEvents {

    public static final KeyMapping OPEN = new KeyMapping("key.createrealtimeschedule.diagram", InputConstants.UNKNOWN.getValue(),
        "key.categories.createrealtimeschedule");

    private DiagramEvents() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        while (OPEN.consumeClick())
            if (Minecraft.getInstance().screen == null)
                DiagramClient.open("");
        DiagramClient.tick();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        DiagramClient.reset();
    }

    /** NeoForge posts this one on the mod's own bus, and finds that from the event. */
    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN);
    }
}
