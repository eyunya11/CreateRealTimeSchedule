package io.github.eyunya11.createrealtimeschedule.screen;

import com.simibubi.create.AllCreativeModeTabs;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.ModRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Hooks the map screens into the game: Create's creative tab, and forgetting the players who leave. */
@EventBusSubscriber(modid = Createrealtimeschedule.MODID)
public final class MapScreenSetup {

    private MapScreenSetup() {}

    /** Beside Create's own blocks that show what the trains do. */
    @SubscribeEvent
    public static void onCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == AllCreativeModeTabs.BASE_CREATIVE_TAB.getKey())
            event.accept(ModRegistry.MAP_SCREEN_ITEM.get());
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            MapScreenService.forget(player);
    }
}
