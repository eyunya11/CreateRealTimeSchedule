package io.github.eyunya11.createrealtimeschedule.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour.RenderedTrackOverlayType;
import com.simibubi.create.foundation.blockEntity.renderer.SmartBlockEntityRenderer;

import io.github.eyunya11.createrealtimeschedule.tag.TrackTagBlockEntity;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTagEdgePoint;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

/**
 * Draws a track tag block's mark on the track it points at, with the arrow of a station's for the way
 * its section runs, and the name of its tag over the block while the player looks at it.
 */
public class TrackTagRenderer extends SmartBlockEntityRenderer<TrackTagBlockEntity> {

    public TrackTagRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(TrackTagBlockEntity entity, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light, int overlay) {
        super.renderSafe(entity, partialTicks, ms, buffer, light, overlay);
        String name = TrackTagClient.name(entity.tag());
        if (name != null)
            renderNameplateOnHover(entity, Component.literal(name), 1, ms, buffer, light);

        TrackTargetingBehaviour<TrackTagEdgePoint> target = entity.edgePoint;
        BlockPos track = target.getGlobalPosition();
        Level level = entity.getLevel();
        if (level == null || !(level.getBlockState(track).getBlock() instanceof ITrackBlock))
            return;
        BlockPos offset = track.subtract(entity.getBlockPos());
        ms.pushPose();
        ms.translate(offset.getX(), offset.getY(), offset.getZ());
        TrackTargetingBehaviour.render(level, track, target.getTargetDirection(), target.getTargetBezier(), ms, buffer, light, overlay,
            RenderedTrackOverlayType.STATION, 1);
        ms.popPose();
    }
}
