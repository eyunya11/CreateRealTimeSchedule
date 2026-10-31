package io.github.eyunya11.createrealtimeschedule.tag;

import com.simibubi.create.AllCreativeModeTabs;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.ModRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Hooks the track tags into the game: Create's kinds of edge point, its creative tab, and the clients' lists. */
@EventBusSubscriber(modid = Createrealtimeschedule.MODID)
public final class TrackTagSetup {

    private TrackTagSetup() {}

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(TrackTagEdgePoint::register);
    }

    /** Beside Create's own blocks that are placed on the track. */
    @SubscribeEvent
    public static void onCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == AllCreativeModeTabs.BASE_CREATIVE_TAB.getKey())
            event.accept(ModRegistry.TRACK_TAG_ITEM.get());
    }

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            PacketDistributor.sendToPlayer(player, TrackTagService.list(player.server));
    }
}
