package io.github.eyunya11.createrealtimeschedule.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import io.github.eyunya11.createrealtimeschedule.map.LiveMap;
import io.github.eyunya11.createrealtimeschedule.map.MapProjection;
import io.github.eyunya11.createrealtimeschedule.map.MapProjection.Box;
import io.github.eyunya11.createrealtimeschedule.map.ScreenLayout.Place;
import io.github.eyunya11.createrealtimeschedule.map.ScreenSettings;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Lamp;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.SignalSide;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Spot;
import io.github.eyunya11.createrealtimeschedule.network.MapViewPayload;
import io.github.eyunya11.createrealtimeschedule.screen.MapScreenBlock;
import io.github.eyunya11.createrealtimeschedule.screen.MapScreenBlockEntity;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws a block's part of a map screen: the part of the world {@link MapProjection#tile} gives it, on
 * the front of the panel, north up. The track, the stations and the signs change only when the track,
 * the lines' tracks or the settings do, and are worked out then; the signals and the trains are drawn
 * as the server last said. Sizes are in blocks of the screen, so that a screen shows the same however
 * much of the world it covers.
 */
public class MapScreenRenderer implements BlockEntityRenderer<MapScreenBlockEntity> {

    /** Where the front of the panel is, from the middle of the block, towards the way it faces. */
    private static final float SURFACE = -0.5f + MapScreenBlock.THICKNESS / 16f;
    /** How far each layer is drawn in front of the one below it. */
    private static final float LAYER = 0.0015f;

    private static final int BACKGROUND = 0xFF161B22;
    private static final int TRACK = 0xFFC9D1D9;
    private static final int STATION = 0xFFFFFFFF;
    private static final int STATION_EDGE = 0xFF161B22;
    private static final int TRAIN = 0xFFFF7A1A;
    private static final int HEAD = 0xFF050608;
    private static final int SIGN = 0xFFFFFFFF;
    private static final int SIGN_EDGE = 0xFFE5483F;
    private static final int RELEASE_EDGE = 0xFF8B949E;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int SIGN_TEXT = 0xFF161B22;

    private static final float TRACK_WIDTH = 0.022f;
    private static final float TRAIN_WIDTH = 0.05f;
    private static final float STATION_SIZE = 0.045f;
    private static final float LAMP_SIZE = 0.022f;
    private static final float SIGN_SIZE = 0.055f;
    private static final float BESIDE = 0.075f;
    private static final float NAME_SCALE = 0.0055f;
    private static final float LIMIT_SCALE = 0.0035f;

    private final Font font;
    /** What each block drew last, kept until the map, the lines' tracks or the settings change. */
    private final Map<MapScreenBlockEntity, Drawing> drawings = new WeakHashMap<>();

    public MapScreenRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
    }

    /** A label: its text, where it starts on the tile, its size and colour. */
    private record Label(String text, float u, float v, float scale, int colour, boolean centred) {}

    /** The quads of a tile (u, v of four corners, then a colour, each), its labels, and the keys of the tracks shown. */
    private record Drawing(Object key, float[] quads, List<Label> labels, Set<String> shownKeys) {}

    @Override
    public int getViewDistance() {
        return 96;
    }

    @Override
    public void render(MapScreenBlockEntity entity, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        BlockState state = entity.getBlockState();
        if (!(state.getBlock() instanceof MapScreenBlock) || entity.getLevel() == null)
            return;
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        Place place = entity.place();
        ScreenSettings settings = entity.settings();
        MapClient.drawn(settings.lines());
        Box box = settings.projection(place.width(), place.height()).tile(place.column(), place.row());

        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        // Then +z is the way the screen faces, +x to the right as one looks at it, and +y up.
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        Matrix4f matrix = pose.last().pose();
        VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
        quad(quads, matrix, 0, 0, 1, 0, 1, 1, 0, 1, 0, BACKGROUND);

        int dimension = MapClient.dimension(entity.getLevel().dimension().location().toString());
        if (dimension >= 0) {
            Drawing drawing = drawing(entity, settings, place, box, dimension);
            float[] q = drawing.quads();
            for (int i = 0; i < q.length; i += 10)
                quad(quads, matrix, q[i], q[i + 1], q[i + 2], q[i + 3], q[i + 4], q[i + 5], q[i + 6], q[i + 7], (int) q[i + 8],
                    Float.floatToRawIntBits(q[i + 9]));
            List<Label> labels = new ArrayList<>(drawing.labels());
            live(quads, matrix, settings, box, drawing.shownKeys());
            for (Label label : labels)
                label(pose, buffers, label);
        }
        pose.popPose();
    }

    /** The track, stations and signs of the tile, worked out again only when something they come from has changed. */
    private Drawing drawing(MapScreenBlockEntity entity, ScreenSettings settings, Place place, Box box, int dimension) {
        MapViewPayload view = MapClient.view(settings.lines());
        Object key = List.of(MapClient.version(), view == null ? 0L : view.revision(), settings, place, dimension);
        Drawing known = drawings.get(entity);
        if (known != null && known.key().equals(key))
            return known;

        TrackMap map = MapClient.map();
        Set<Integer> shown = MapClient.shown(settings.lines());
        Set<String> shownKeys = new HashSet<>();
        Quads out = new Quads();
        double scale = settings.scale();
        for (int index : shown) {
            TrackMap.Track track = map.tracks().get(index);
            if (track.dimension() != dimension)
                continue;
            shownKeys.add(track.key());
            List<Spot> points = track.points();
            for (int i = 1; i < points.size(); i++) {
                double[] cut = MapProjection.clip(points.get(i - 1).x(), points.get(i - 1).z(), points.get(i).x(), points.get(i).z(), box);
                if (cut != null)
                    out.line(u(cut[0], box, scale), v(cut[1], box, scale), u(cut[2], box, scale), v(cut[3], box, scale), TRACK_WIDTH, 1, TRACK);
            }
        }

        List<Label> labels = new ArrayList<>();
        if (settings.shows("speedLimits"))
            for (TrackMap.SpeedMark sign : map.speedLimits())
                if (shown.contains(sign.track()) && box.contains(sign.at().x(), sign.at().z())) {
                    float[] at = beside(sign.at(), sign.direction(), box, scale, BESIDE * 1.4f);
                    out.square(at[0], at[1], SIGN_SIZE, 3, sign.release() ? RELEASE_EDGE : SIGN_EDGE);
                    out.square(at[0], at[1], SIGN_SIZE * 0.78f, 4, SIGN);
                    if (sign.release())
                        out.line(at[0] - SIGN_SIZE * 0.6f, at[1] - SIGN_SIZE * 0.6f, at[0] + SIGN_SIZE * 0.6f, at[1] + SIGN_SIZE * 0.6f,
                            0.008f, 5, RELEASE_EDGE);
                    else
                        labels.add(new Label(Integer.toString(sign.kmh()), at[0], at[1], LIMIT_SCALE, SIGN_TEXT, true));
                }

        if (settings.shows("stations") || settings.shows("stationNames")) {
            // A name once, in the middle of its markers on the tracks shown, by the tile that has that middle.
            Map<String, double[]> middles = new LinkedHashMap<>();
            for (TrackMap.StationMark station : map.stations()) {
                if (!shown.contains(station.track()) || map.tracks().get(station.track()).dimension() != dimension)
                    continue;
                double[] sum = middles.computeIfAbsent(station.name(), unused -> new double[3]);
                sum[0] += station.at().x();
                sum[1] += station.at().z();
                sum[2]++;
                if (settings.shows("stations") && box.contains(station.at().x(), station.at().z())) {
                    float u = u(station.at().x(), box, scale);
                    float v = v(station.at().z(), box, scale);
                    out.square(u, v, STATION_SIZE, 6, STATION_EDGE);
                    out.square(u, v, STATION_SIZE * 0.7f, 7, STATION);
                }
            }
            if (settings.shows("stationNames"))
                for (Map.Entry<String, double[]> entry : middles.entrySet()) {
                    double x = entry.getValue()[0] / entry.getValue()[2];
                    double z = entry.getValue()[1] / entry.getValue()[2];
                    if (box.contains(x, z))
                        labels.add(new Label(entry.getKey(), u(x, box, scale) + STATION_SIZE * 1.4f, v(z, box, scale) + STATION_SIZE * 1.6f,
                            NAME_SCALE, TEXT, false));
                }
        }
        Drawing drawing = new Drawing(key, out.toArray(), labels, shownKeys);
        drawings.put(entity, drawing);
        return drawing;
    }

    /** The signals and the trains, as the server last said, on the tracks shown. */
    private void live(VertexConsumer quads, Matrix4f matrix, ScreenSettings settings, Box box, Set<String> shownKeys) {
        LiveMap live = MapClient.live();
        double scale = settings.scale();
        Quads out = new Quads();
        if (settings.shows("signals"))
            for (LiveMap.Signal signal : live.signals()) {
                if (!shownKeys.contains(signal.track()) || !box.contains(signal.at().x(), signal.at().z()))
                    continue;
                for (SignalSide side : signal.sides()) {
                    float[] at = beside(signal.at(), side.direction(), box, scale, BESIDE);
                    // Along the way the trains run, on the screen: z is down it.
                    float du = (float) side.direction().x();
                    float dv = (float) -side.direction().z();
                    int count = side.lamps().size();
                    float reach = (count - 1) * LAMP_SIZE * 1.3f;
                    out.line(at[0] - du * reach, at[1] - dv * reach, at[0] + du * reach, at[1] + dv * reach, LAMP_SIZE * 1.6f, 8, HEAD);
                    for (int i = 0; i < count; i++) {
                        float along = (i - (count - 1) / 2f) * LAMP_SIZE * 2.6f;
                        out.square(at[0] + du * along, at[1] + dv * along, LAMP_SIZE, 9, lamp(side.lamps().get(i)));
                    }
                }
            }
        if (settings.shows("trains"))
            for (LiveMap.Train train : live.trains())
                for (LiveMap.Carriage carriage : train.carriages()) {
                    if (!shownKeys.contains(carriage.frontTrack()) && !shownKeys.contains(carriage.backTrack()))
                        continue;
                    double[] cut = MapProjection.clip(carriage.front().x(), carriage.front().z(), carriage.back().x(), carriage.back().z(), box);
                    if (cut != null)
                        out.line(u(cut[0], box, scale), v(cut[1], box, scale), u(cut[2], box, scale), v(cut[3], box, scale), TRAIN_WIDTH,
                            10, TRAIN);
                }
        float[] q = out.toArray();
        for (int i = 0; i < q.length; i += 10)
            quad(quads, matrix, q[i], q[i + 1], q[i + 2], q[i + 3], q[i + 4], q[i + 5], q[i + 6], q[i + 7], (int) q[i + 8],
                Float.floatToRawIntBits(q[i + 9]));
    }

    private static int lamp(Lamp lamp) {
        return switch (lamp) {
            case RED -> 0xFFFF4D40;
            case YELLOW -> 0xFFFFD23F;
            case GREEN -> 0xFF3DDC84;
        };
    }

    /** Where on the tile the world's x is, from its left: 0 to 1. */
    private static float u(double x, Box box, double scale) {
        return (float) ((x - box.minX()) / scale);
    }

    /** Where on the tile the world's z is, from its bottom: north is up. */
    private static float v(double z, Box box, double scale) {
        return (float) (1 - (z - box.minZ()) / scale);
    }

    /** A thing beside the track, to the right of the trains running {@code direction}, as Create puts signals. */
    private static float[] beside(Spot at, Spot direction, Box box, double scale, float away) {
        // To the right of (dx, dz) in the world is (-dz, dx); on the screen z is down.
        return new float[] {u(at.x(), box, scale) - (float) direction.z() * away, v(at.z(), box, scale) - (float) direction.x() * away};
    }

    /** Quads in tile coordinates, gathered: four corners, a layer and a colour each. */
    private static final class Quads {
        private float[] data = new float[160];
        private int size;

        void add(float u1, float v1, float u2, float v2, float u3, float v3, float u4, float v4, int layer, int colour) {
            if (size + 10 > data.length)
                data = java.util.Arrays.copyOf(data, data.length * 2);
            float[] d = data;
            d[size++] = u1;
            d[size++] = v1;
            d[size++] = u2;
            d[size++] = v2;
            d[size++] = u3;
            d[size++] = v3;
            d[size++] = u4;
            d[size++] = v4;
            d[size++] = layer;
            d[size++] = Float.intBitsToFloat(colour);
        }

        /** A line of the given width, its ends squared off a half width past the points so that lines meet. */
        void line(float u1, float v1, float u2, float v2, float width, int layer, int colour) {
            float du = u2 - u1;
            float dv = v2 - v1;
            float length = (float) Math.hypot(du, dv);
            if (length < 1e-6f)
                return;
            float half = width / 2;
            float au = du / length * half;
            float av = dv / length * half;
            float nu = -av;
            float nv = au;
            add(u1 - au + nu, v1 - av + nv, u2 + au + nu, v2 + av + nv, u2 + au - nu, v2 + av - nv, u1 - au - nu, v1 - av - nv, layer, colour);
        }

        void square(float u, float v, float half, int layer, int colour) {
            add(u - half, v - half, u + half, v - half, u + half, v + half, u - half, v + half, layer, colour);
        }

        float[] toArray() {
            return java.util.Arrays.copyOf(data, size);
        }
    }

    private static void quad(VertexConsumer out, Matrix4f matrix, float u1, float v1, float u2, float v2, float u3, float v3, float u4,
        float v4, int layer, int colour) {
        float z = SURFACE + 0.002f + layer * LAYER;
        out.addVertex(matrix, u1 - 0.5f, v1 - 0.5f, z).setColor(colour);
        out.addVertex(matrix, u2 - 0.5f, v2 - 0.5f, z).setColor(colour);
        out.addVertex(matrix, u3 - 0.5f, v3 - 0.5f, z).setColor(colour);
        out.addVertex(matrix, u4 - 0.5f, v4 - 0.5f, z).setColor(colour);
    }

    private void label(PoseStack pose, MultiBufferSource buffers, Label label) {
        pose.pushPose();
        pose.translate(label.u() - 0.5f, label.v() - 0.5f, SURFACE + 0.002f + 12 * LAYER);
        pose.scale(label.scale(), -label.scale(), label.scale());
        float x = label.centred() ? -font.width(label.text()) / 2f : 0;
        font.drawInBatch(label.text(), x, -font.lineHeight / 2f + 1, label.colour(), false, pose.last().pose(), buffers,
            Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
        pose.popPose();
    }
}
