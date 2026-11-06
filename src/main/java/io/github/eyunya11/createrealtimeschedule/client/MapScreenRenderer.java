package io.github.eyunya11.createrealtimeschedule.client;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import io.github.eyunya11.createrealtimeschedule.map.MapProjection;
import io.github.eyunya11.createrealtimeschedule.map.MapProjection.Box;
import io.github.eyunya11.createrealtimeschedule.map.ScreenLayout.Place;
import io.github.eyunya11.createrealtimeschedule.map.ScreenSettings;
import io.github.eyunya11.createrealtimeschedule.network.MapViewPayload;
import io.github.eyunya11.createrealtimeschedule.screen.MapScreenBlock;
import io.github.eyunya11.createrealtimeschedule.screen.MapScreenBlockEntity;
import io.github.eyunya11.createrealtimeschedule.screen.ScreenGroup;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Draws a block's part of a map screen: the part of the world {@link MapProjection#tile} gives it, on
 * the front of the panel, north up, as {@link MapPainter} paints it.
 *
 * <p>The track changes only when the track, the lines' tracks, the tags' colours or the settings do,
 * and is worked out then for each block. The marks are worked out for the whole screen at once; each
 * block draws those whose place is on it.
 */
public class MapScreenRenderer implements BlockEntityRenderer<MapScreenBlockEntity> {

    /** Where the front of the panel is, from the middle of the block, towards the way it faces. */
    private static final float SURFACE = -0.5f + MapScreenBlock.THICKNESS / 16f;
    /** How far each layer is drawn in front of the one below it: little, so that what is on a thing looks to be on it. */
    private static final float LAYER = 0.0005f;

    private final Font font;
    private final MapPainter painter;
    /** What each block drew of the track last, kept until the map, the lines' tracks or the settings change. */
    private final Map<MapScreenBlockEntity, Drawing> drawings = new WeakHashMap<>();

    public MapScreenRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
        this.painter = new MapPainter(font);
    }

    /** The quads of the track of a tile (u, v of four corners, then a layer and a colour, each), and the tracks shown. */
    private record Drawing(Object key, float[] quads, MapPainter.Shown shown) {}

    @Override
    public int getViewDistance() {
        return 96;
    }

    /**
     * The whole screen: a block draws the marks whose middles are on it, which may reach over the blocks
     * beside it, so it is drawn while any of the screen is in sight, not only while it is.
     */
    @Override
    public AABB getRenderBoundingBox(MapScreenBlockEntity entity) {
        BlockState state = entity.getBlockState();
        if (!(state.getBlock() instanceof MapScreenBlock))
            return new AABB(entity.getBlockPos());
        Place place = entity.place();
        Direction right = ScreenGroup.right(state.getValue(HorizontalDirectionalBlock.FACING));
        BlockPos bottomLeft = entity.getBlockPos().relative(right, -place.column()).below(place.row());
        BlockPos topRight = bottomLeft.relative(right, place.width() - 1).above(place.height() - 1);
        return AABB.encapsulatingFullBlocks(bottomLeft, topRight);
    }

    @Override
    public void render(MapScreenBlockEntity entity, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        BlockState state = entity.getBlockState();
        if (!(state.getBlock() instanceof MapScreenBlock) || entity.getLevel() == null)
            return;
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        Place place = entity.place();
        ScreenSettings settings = entity.settings();
        MapClient.drawn(settings);
        Box box = settings.projection(place.width(), place.height()).tile(place.column(), place.row());

        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        // Then +z is the way the screen faces, +x to the right as one looks at it, and +y up.
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        Matrix4f matrix = pose.last().pose();
        VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
        quad(quads, matrix, 0, 0, 1, 0, 1, 1, 0, 1, 0, MapPainter.BACKGROUND);

        int dimension = MapClient.dimension(entity.getLevel().dimension().location().toString());
        if (dimension >= 0) {
            Drawing drawing = drawing(entity, settings, place, box, dimension);
            quads(quads, matrix, drawing.quads(), 0, 0);
            MapPainter.Marks screen = painter.marks(settings, place.width(), place.height(), dimension, drawing.shown());
            int tile = MapPainter.tileOf(place.column(), place.row(), place.width());
            float[] marked = screen.quads().get(tile);
            if (marked != null)
                quads(quads, matrix, marked, place.column(), place.row());
            quads(quads, matrix, MapPainter.trains(settings, box, drawing.shown()), 0, 0);
            for (MapPainter.Label label : screen.labels().getOrDefault(tile, List.of()))
                label(pose, buffers, label, place.column(), place.row());
        }
        pose.popPose();
    }

    /** The track of the tile, worked out again only when something it comes from has changed. */
    private Drawing drawing(MapScreenBlockEntity entity, ScreenSettings settings, Place place, Box box, int dimension) {
        MapViewPayload view = MapClient.view(settings);
        Object key = List.of(MapClient.version(), view == null ? 0L : view.revision(), settings, place, dimension,
            TrackTagClient.names(), TrackTagClient.colours());
        Drawing known = drawings.get(entity);
        if (known != null && known.key().equals(key))
            return known;
        MapPainter.Shown shown = MapPainter.Shown.of(settings);
        Drawing drawing = new Drawing(key, MapPainter.track(shown, box, settings.scale(), dimension), shown);
        drawings.put(entity, drawing);
        return drawing;
    }

    /** Draws quads, moved {@code column} and {@code row} blocks back so that the tile's bottom left is at 0, 0. */
    private static void quads(VertexConsumer out, Matrix4f matrix, float[] q, float column, float row) {
        for (int i = 0; i < q.length; i += 10)
            quad(out, matrix, q[i] - column, q[i + 1] - row, q[i + 2] - column, q[i + 3] - row, q[i + 4] - column, q[i + 5] - row,
                q[i + 6] - column, q[i + 7] - row, (int) q[i + 8], Float.floatToRawIntBits(q[i + 9]));
    }

    private static void quad(VertexConsumer out, Matrix4f matrix, float u1, float v1, float u2, float v2, float u3, float v3, float u4,
        float v4, int layer, int colour) {
        float z = SURFACE + 0.002f + layer * LAYER;
        out.addVertex(matrix, u1 - 0.5f, v1 - 0.5f, z).setColor(colour);
        out.addVertex(matrix, u2 - 0.5f, v2 - 0.5f, z).setColor(colour);
        out.addVertex(matrix, u3 - 0.5f, v3 - 0.5f, z).setColor(colour);
        out.addVertex(matrix, u4 - 0.5f, v4 - 0.5f, z).setColor(colour);
    }

    private void label(PoseStack pose, MultiBufferSource buffers, MapPainter.Label label, float column, float row) {
        pose.pushPose();
        pose.translate(label.u() - column - 0.5f, label.v() - row - 0.5f, SURFACE + 0.002f + label.layer() * LAYER);
        pose.scale(label.scale(), -label.scale(), label.scale());
        float x = label.centred() ? -font.width(label.text()) / 2f : 0;
        font.drawInBatch(label.text(), x, -font.lineHeight / 2f + 1, label.colour(), false, pose.last().pose(), buffers,
            Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
        pose.popPose();
    }
}
