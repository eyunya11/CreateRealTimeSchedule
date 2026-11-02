package io.github.eyunya11.createrealtimeschedule.client;

import java.lang.reflect.Field;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.simibubi.create.content.trains.track.BezierTrackPointLocation;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour.RenderedTrackOverlayType;
import com.simibubi.create.content.trains.track.TrackTargetingBlockItem;
import com.simibubi.create.content.trains.track.TrackTargetingClient;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTagBlock;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTagItem;
import net.createmod.catnip.render.DefaultSuperRenderTypeBuffer;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * The mark shown on the track while a player holding a tag block's item picks the place for it. Create
 * shows a station's mark for every kind of point but its signals and observers, and has no way to
 * change that; so this takes the place Create picked from its {@link TrackTargetingClient} by
 * reflection, leaves Create with none to show, and shows the block's own mark there: a signal's for a
 * one-way block, a two-sided signal's for one for both ways, as on the placed block
 * ({@link TrackTagRenderer}). If Create's fields cannot be got at, Create's mark is left as it is.
 */
@EventBusSubscriber(modid = Createrealtimeschedule.MODID, value = Dist.CLIENT)
public final class TrackTagPreview {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final @Nullable Field HOVERED = field("lastHovered");
    private static final @Nullable Field DIRECTION = field("lastDirection");
    private static final @Nullable Field BEZIER = field("lastHoveredBezierSegment");
    private static final @Nullable Field RESULT = field("lastResult");
    private static final @Nullable Field LOCATION = field("lastLocation");

    /** The place picked, as Create last worked it out, or null for none. */
    private record Picked(BlockPos track, boolean direction, @Nullable BezierTrackPointLocation bezier, boolean refused) {
    }

    private static @Nullable Picked picked;

    private TrackTagPreview() {}

    private static @Nullable Field field(String name) {
        try {
            Field field = TrackTargetingClient.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Cannot read Create's place for a block on the track; a tag block's item shows a station's mark", e);
            return null;
        }
    }

    /** The tag block that the player's main hand item places, or null. */
    private static @Nullable TrackTagBlock held() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !(minecraft.player.getMainHandItem().getItem() instanceof TrackTagItem item))
            return null;
        return item.getBlock() instanceof TrackTagBlock block ? block : null;
    }

    /** After Create's tick has worked out the place, which it only does when the place it points at changes. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTick(ClientTickEvent.Post event) {
        // With another item in hand, Create works out nothing, or a place for its own kind of point.
        if (HOVERED == null || DIRECTION == null || BEZIER == null || RESULT == null || LOCATION == null || held() == null)
            return;
        try {
            BlockPos track = (BlockPos) HOVERED.get(null);
            if (track == null) {
                picked = null;
                return;
            }
            boolean direction = DIRECTION.getBoolean(null);
            BezierTrackPointLocation bezier = (BezierTrackPointLocation) BEZIER.get(null);
            if (LOCATION.get(null) != null) {
                TrackTargetingBlockItem.OverlapResult result = (TrackTargetingBlockItem.OverlapResult) RESULT.get(null);
                picked = new Picked(track, direction, bezier, result == null || result.feedback != null);
                LOCATION.set(null, null);
            } else if (picked != null && !(picked.track().equals(track) && picked.direction() == direction
                && Objects.equals(picked.bezier(), bezier))) {
                // Create pointed somewhere else, where there is no place for the block.
                picked = null;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            picked = null;
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        Picked shown = picked;
        TrackTagBlock block = held();
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || shown == null || shown.refused() || block == null
            || minecraft.level == null)
            return;
        PoseStack ms = event.getPoseStack();
        SuperRenderTypeBuffer buffer = DefaultSuperRenderTypeBuffer.getInstance();
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        ms.pushPose();
        ms.translate(shown.track().getX() - camera.x, shown.track().getY() - camera.y, shown.track().getZ() - camera.z);
        TrackTargetingBehaviour.render(minecraft.level, shown.track(),
            shown.direction() ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE, shown.bezier(), ms, buffer,
            LevelRenderer.getLightColor(minecraft.level, shown.track()), OverlayTexture.NO_OVERLAY,
            block.oneWay() ? RenderedTrackOverlayType.SIGNAL : RenderedTrackOverlayType.DUAL_SIGNAL, 1.0625f);
        ms.popPose();
        buffer.draw();
    }
}
