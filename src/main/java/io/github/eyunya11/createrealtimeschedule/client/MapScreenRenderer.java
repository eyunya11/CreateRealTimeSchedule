package io.github.eyunya11.createrealtimeschedule.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.IntConsumer;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import io.github.eyunya11.createrealtimeschedule.map.LiveMap;
import io.github.eyunya11.createrealtimeschedule.map.MapProjection;
import io.github.eyunya11.createrealtimeschedule.map.MapProjection.Box;
import io.github.eyunya11.createrealtimeschedule.map.MarkLayout;
import io.github.eyunya11.createrealtimeschedule.map.MarkLayout.Rect;
import io.github.eyunya11.createrealtimeschedule.map.Polyline;
import io.github.eyunya11.createrealtimeschedule.map.ScreenLayout.Place;
import io.github.eyunya11.createrealtimeschedule.map.ScreenSettings;
import io.github.eyunya11.createrealtimeschedule.map.TagView;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Lamp;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.SignalSide;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Spot;
import io.github.eyunya11.createrealtimeschedule.network.MapViewPayload;
import io.github.eyunya11.createrealtimeschedule.screen.MapScreenBlock;
import io.github.eyunya11.createrealtimeschedule.screen.MapScreenBlockEntity;
import io.github.eyunya11.createrealtimeschedule.timetable.TypeColours;
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
 * the front of the panel, north up.
 *
 * <p>The track changes only when the track, the lines' tracks, the tags' colours or the settings do,
 * and is worked out then for each block. A stretch of track shown is drawn in the colours of its track
 * tags that are on, a dash each in turn for more than one, or light grey with no tag; track not shown,
 * by the lines or by the tags, is drawn faint, in no tag's colour, with nothing on it. Track is drawn as
 * lines mitred at their bends ({@link Polyline}).
 *
 * <p>The marks (the stations, pointing the way the trains come in; the signals, a mark on the track
 * pointing the way they hold the trains and a head on a post to the right of it; the speed-limit signs
 * on a post; and the stations' names) are worked out for the whole screen at once, again when the
 * server says what is live, so that they can be kept off one another ({@link MarkLayout}); each block
 * draws those whose place is on it. The trains are drawn as the server last said.
 *
 * <p>Sizes are in blocks of the screen, so that a screen shows the same however much of the world it
 * covers; the marks and the words grow with the square root of the screen's shorter side, so that a
 * large screen, seen from further off, can still be read.
 */
public class MapScreenRenderer implements BlockEntityRenderer<MapScreenBlockEntity> {

    /** Where the front of the panel is, from the middle of the block, towards the way it faces. */
    private static final float SURFACE = -0.5f + MapScreenBlock.THICKNESS / 16f;
    /** How far each layer is drawn in front of the one below it: little, so that what is on a thing looks to be on it. */
    private static final float LAYER = 0.0005f;
    private static final int POST_LAYER = 3;
    private static final int SIGN_LAYER = 4;
    private static final int STATION_LAYER = 7;
    private static final int SIGNAL_LAYER = 9;
    private static final int NAME_LAYER = 12;
    private static final int TRAIN_LAYER = 13;

    private static final int BACKGROUND = 0xFF161B22;
    private static final int TRACK = 0xFFC9D1D9;
    private static final int FAINT = 0xFF30363D;
    private static final int STATION = 0xFFFFFFFF;
    private static final int STATION_EDGE = 0xFF161B22;
    private static final int TRAIN = 0xFFFF7A1A;
    private static final int HEAD = 0xFF050608;
    private static final int HEAD_EDGE = 0xFF8B949E;
    private static final int POST = 0xFF8B949E;
    private static final int UNLIT = 0xFF484F58;
    private static final int SIGN = 0xFFFFFFFF;
    private static final int SIGN_EDGE = 0xFFE5483F;
    /** The colours of Create Train Next's release sign: black on off-white. */
    private static final int RELEASE = 0xFFECEBE6;
    private static final int RELEASE_BLACK = 0xFF161616;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int SIGN_TEXT = 0xFF161B22;

    private static final float TRACK_WIDTH = 0.022f;
    /** How long a dash of a stretch with more than one tag is, in blocks of the screen. */
    private static final double DASH = 0.08;
    private static final float TRAIN_WIDTH = 0.05f;
    /** The sizes of the marks on a screen of one block; a larger screen has them larger. */
    private static final float STATION_SIZE = 0.045f;
    private static final float POINTER_SIZE = 0.03f;
    private static final float LAMP_SIZE = 0.022f;
    private static final float SIGN_SIZE = 0.055f;
    private static final float POST_WIDTH = 0.007f;
    private static final float BESIDE = 0.075f;
    private static final float NAME_SCALE = 0.0055f;
    private static final float LIMIT_SCALE = 0.0035f;
    /** How far from the track a signal's head may be put, in {@link #BESIDE}s, nearest first. */
    private static final float[] HEAD_PLACES = {1, 1.6f, 2.2f, 2.8f, 3.4f};
    /** How far from the track a sign may be put, in {@link #BESIDE}s: to the right of the trains it holds, then to the left. */
    private static final float[] SIGN_PLACES = {1.4f, 2.2f, 3f, -1.4f, -2.2f};

    private final Font font;
    /** What each block drew of the track last, kept until the map, the lines' tracks or the settings change. */
    private final Map<MapScreenBlockEntity, Drawing> drawings = new WeakHashMap<>();
    /** The marks of the screens drawn lately, by what they were worked out from. */
    private final Map<Object, Marks> marks = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Object, Marks> eldest) {
            return size() > 16;
        }
    };

    public MapScreenRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
    }

    /** A label: its text, where it starts on the screen, its size, colour and layer. */
    private record Label(String text, float u, float v, float scale, int colour, boolean centred, int layer) {}

    /** The quads of the track of a tile (u, v of four corners, then a layer and a colour, each), and the tracks shown. */
    private record Drawing(Object key, float[] quads, Shown shown) {}

    /** The marks of a screen, in the screen's blocks from its bottom left, by the block that draws them ({@link #tileOf}). */
    private record Marks(Map<Integer, float[]> quads, Map<Integer, List<Label>> labels) {}

    /** The tracks a screen shows by their indexes, and the stretches of those not all shown with no tag. */
    private record Shown(Set<Integer> tracks, Map<Integer, List<TagView.Segment>> stretches) {

        /** Whether the place {@code at} on track {@code index} is on a stretch shown. */
        boolean at(int index, Spot at) {
            if (index < 0 || !tracks.contains(index))
                return false;
            List<TagView.Segment> segments = stretches.get(index);
            return segments == null || TagView.shownAt(segments, TagView.shareOf(MapClient.map().tracks().get(index).points(), at));
        }
    }

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
        MapClient.drawn(settings);
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
            quads(quads, matrix, drawing.quads(), 0, 0);
            Marks screen = marks(settings, place, dimension, drawing.shown());
            int tile = tileOf(place.column(), place.row(), place.width());
            float[] marked = screen.quads().get(tile);
            if (marked != null)
                quads(quads, matrix, marked, place.column(), place.row());
            trains(quads, matrix, settings, box, drawing.shown());
            for (Label label : screen.labels().getOrDefault(tile, List.of()))
                label(pose, buffers, label, place.column(), place.row());
        }
        pose.popPose();
    }

    private static int tileOf(int column, int row, int width) {
        return column + row * width;
    }

    /** The track of the tile, worked out again only when something it comes from has changed. */
    private Drawing drawing(MapScreenBlockEntity entity, ScreenSettings settings, Place place, Box box, int dimension) {
        MapViewPayload view = MapClient.view(settings);
        Object key = List.of(MapClient.version(), view == null ? 0L : view.revision(), settings, place, dimension,
            TrackTagClient.names(), TrackTagClient.colours());
        Drawing known = drawings.get(entity);
        if (known != null && known.key().equals(key))
            return known;

        TrackMap map = MapClient.map();
        Shown shown = new Shown(MapClient.shown(settings), MapClient.stretches(settings));
        Map<String, Integer> colours = tagColours();
        Quads out = new Quads();
        double scale = settings.scale();
        for (int index = 0; index < map.tracks().size(); index++) {
            TrackMap.Track track = map.tracks().get(index);
            if (track.dimension() != dimension)
                continue;
            if (!shown.tracks().contains(index)) {
                polyline(out, track.points(), box, scale, 1, FAINT);
                continue;
            }
            List<TagView.Segment> segments = shown.stretches().get(index);
            if (segments == null) {
                polyline(out, track.points(), box, scale, 2, TRACK);
                continue;
            }
            for (TagView.Segment segment : segments) {
                List<Spot> piece = TagView.piece(track.points(), segment.from(), segment.to());
                if (!segment.shown()) {
                    polyline(out, piece, box, scale, 1, FAINT);
                } else if (segment.tags().isEmpty()) {
                    polyline(out, piece, box, scale, 2, TRACK);
                } else {
                    List<List<Spot>> dashes = segment.tags().size() == 1 ? List.of(piece) : TagView.dashes(piece, DASH * scale);
                    for (int k = 0; k < dashes.size(); k++)
                        polyline(out, dashes.get(k), box, scale, 2, colours.getOrDefault(segment.tags().get(k % segment.tags().size()), TRACK));
                }
            }
        }
        Drawing drawing = new Drawing(key, out.toArray(), shown);
        drawings.put(entity, drawing);
        return drawing;
    }

    /** The line through {@code points}, cut to the tile, at {@code layer} in {@code colour}. */
    private static void polyline(Quads out, List<Spot> points, Box box, double scale, int layer, int colour) {
        for (List<Spot> run : Polyline.runs(points, box)) {
            List<Spot> onTile = new ArrayList<>(run.size());
            for (Spot point : run)
                onTile.add(new Spot(u(point.x(), box, scale), v(point.z(), box, scale)));
            List<double[]> corners = Polyline.outline(onTile, TRACK_WIDTH / 2);
            for (int i = 1; i < corners.size(); i++) {
                double[] a = corners.get(i - 1);
                double[] b = corners.get(i);
                out.add((float) a[0], (float) a[1], (float) b[0], (float) b[1], (float) b[2], (float) b[3], (float) a[2], (float) a[3],
                    layer, colour);
            }
        }
    }

    /** The colours of the world's track tags, by their names. */
    private static Map<String, Integer> tagColours() {
        Map<String, Integer> colours = new HashMap<>();
        for (Map.Entry<Integer, String> tag : TrackTagClient.names().entrySet()) {
            String colour = TrackTagClient.colours().get(tag.getKey());
            colours.put(tag.getValue(), colour != null && TypeColours.isColour(colour) ? TypeColours.argb(colour)
                : TypeColours.argb(TypeColours.byName(tag.getValue())));
        }
        return colours;
    }

    /** A thing put beside the track: where it may go, and how to draw it in the place it is given. */
    private record Pending(MarkLayout.Item item, IntConsumer draw) {}

    /**
     * The marks of a whole screen, worked out again only when something they come from has changed: the
     * stations and the signals' marks on the track first, which stay where they are, then the signals'
     * heads, the signs and the names, each put clear of those before it if it can be.
     */
    private Marks marks(ScreenSettings settings, Place place, int dimension, Shown shown) {
        MapViewPayload view = MapClient.view(settings);
        Object key = List.of(MapClient.version(), view == null ? 0L : view.revision(), settings, place.width(), place.height(), dimension,
            MapClient.liveVersion());
        Marks known = marks.get(key);
        if (known != null)
            return known;

        TrackMap map = MapClient.map();
        LiveMap live = MapClient.live();
        int width = place.width();
        int height = place.height();
        double scale = settings.scale();
        Box whole = settings.projection(width, height).whole();
        float grow = (float) Math.sqrt(Math.min(width, height));
        Map<Integer, Quads> quads = new HashMap<>();
        Map<Integer, List<Label>> labels = new HashMap<>();
        List<Rect> fixed = new ArrayList<>();
        List<Pending> pending = new ArrayList<>();

        // The stations, pointing the way the trains come in, and their names in the middle of their marks.
        Map<String, double[]> middles = new LinkedHashMap<>();
        for (TrackMap.StationMark station : map.stations()) {
            if (!shown.at(station.track(), station.at()) || map.tracks().get(station.track()).dimension() != dimension)
                continue;
            double[] sum = middles.computeIfAbsent(station.name(), unused -> new double[3]);
            sum[0] += station.at().x();
            sum[1] += station.at().z();
            sum[2]++;
            if (!settings.shows("stations") || !whole.contains(station.at().x(), station.at().z()))
                continue;
            float u = (float) ((station.at().x() - whole.minX()) / scale);
            float v = (float) (height - (station.at().z() - whole.minZ()) / scale);
            float size = STATION_SIZE * grow;
            Quads out = quads.computeIfAbsent(tileOf(u, v, width, height), unused -> new Quads());
            float du = (float) station.direction().x();
            float dv = (float) -station.direction().z();
            out.pointer(u, v, du, dv, size, STATION_LAYER, STATION_EDGE);
            out.pointer(u, v, du, dv, size * 0.72f, STATION_LAYER + 1, STATION);
            fixed.add(Rect.around(u, v, size * 1.2f, size * 1.2f));
        }

        if (settings.shows("signals"))
            for (LiveMap.Signal signal : live.signals()) {
                if (!shown.at(MapClient.indexOf(signal.track()), signal.at()) || !whole.contains(signal.at().x(), signal.at().z()))
                    continue;
                float u = (float) ((signal.at().x() - whole.minX()) / scale);
                float v = (float) (height - (signal.at().z() - whole.minZ()) / scale);
                for (SignalSide side : signal.sides())
                    signal(u, v, side, grow, width, height, quads, fixed, pending);
            }

        if (settings.shows("speedLimits"))
            for (LiveMap.Limit sign : live.limits()) {
                if (!shown.at(MapClient.indexOf(sign.track()), sign.at()) || !whole.contains(sign.at().x(), sign.at().z()))
                    continue;
                float u = (float) ((sign.at().x() - whole.minX()) / scale);
                float v = (float) (height - (sign.at().z() - whole.minZ()) / scale);
                sign(u, v, sign, grow, width, height, quads, labels, pending);
            }

        if (settings.shows("stationNames"))
            for (Map.Entry<String, double[]> entry : middles.entrySet()) {
                double x = entry.getValue()[0] / entry.getValue()[2];
                double z = entry.getValue()[1] / entry.getValue()[2];
                if (whole.contains(x, z))
                    name(entry.getKey(), (float) ((x - whole.minX()) / scale), (float) (height - (z - whole.minZ()) / scale), grow,
                        width, height, labels, pending);
            }

        int[] chosen = MarkLayout.place(fixed, pending.stream().map(Pending::item).toList());
        for (int i = 0; i < pending.size(); i++)
            if (chosen[i] >= 0)
                pending.get(i).draw().accept(chosen[i]);

        Map<Integer, float[]> drawn = new HashMap<>();
        quads.forEach((tile, out) -> drawn.put(tile, out.toArray()));
        Marks screen = new Marks(drawn, labels);
        marks.put(key, screen);
        return screen;
    }

    /** The block of the screen that draws a mark at ({@code u}, {@code v}) on it. */
    private static int tileOf(float u, float v, int width, int height) {
        int column = Math.max(0, Math.min(width - 1, (int) Math.floor(u)));
        int row = Math.max(0, Math.min(height - 1, (int) Math.floor(v)));
        return tileOf(column, row, width);
    }

    /**
     * One side of a signal at ({@code u}, {@code v}): a pointer on the track the way it holds the trains,
     * in the colour of its first lamp, and a head on a post to the right of the trains, its lamps along
     * the way they run, as far out as it has to go to be clear.
     */
    private static void signal(float u, float v, SignalSide side, float grow, int width, int height, Map<Integer, Quads> quads,
        List<Rect> fixed, List<Pending> pending) {
        float length = (float) Math.hypot(side.direction().x(), side.direction().z());
        if (length < 1e-6f)
            return;
        float du = (float) side.direction().x() / length;
        float dv = (float) -side.direction().z() / length;
        // To the right of the way the trains run, on the screen.
        float ru = dv;
        float rv = -du;
        int first = side.lamps().isEmpty() ? UNLIT : lamp(side.lamps().getFirst());
        float pointer = POINTER_SIZE * grow;
        Quads onTrack = quads.computeIfAbsent(tileOf(u, v, width, height), unused -> new Quads());
        onTrack.pointer(u, v, du, dv, pointer * 1.35f, SIGNAL_LAYER, HEAD);
        onTrack.pointer(u, v, du, dv, pointer, SIGNAL_LAYER + 1, first);
        fixed.add(Rect.around(u, v, pointer * 1.4f, pointer * 1.4f));

        int count = Math.max(1, side.lamps().size());
        float lamp = LAMP_SIZE * grow;
        float along = count * lamp * 2.6f;
        float across = lamp * 2.6f;
        float halfU = Math.abs(du) * along / 2 + Math.abs(dv) * across / 2;
        float halfV = Math.abs(dv) * along / 2 + Math.abs(du) * across / 2;
        List<Rect> places = new ArrayList<>();
        for (float away : HEAD_PLACES) {
            float reach = BESIDE * grow * away + across / 2;
            places.add(Rect.around(u + ru * reach, v + rv * reach, halfU, halfV));
        }
        pending.add(new Pending(new MarkLayout.Item(places, false), index -> {
            float reach = BESIDE * grow * HEAD_PLACES[index] + across / 2;
            float cu = u + ru * reach;
            float cv = v + rv * reach;
            Quads out = quads.computeIfAbsent(tileOf(cu, cv, width, height), unused -> new Quads());
            out.line(u + ru * pointer, v + rv * pointer, cu, cv, POST_WIDTH * grow, POST_LAYER, POST);
            float half = along / 2 - across / 2;
            out.line(cu - du * half, cv - dv * half, cu + du * half, cv + dv * half, across + lamp * 0.5f, SIGNAL_LAYER, HEAD_EDGE);
            out.line(cu - du * half, cv - dv * half, cu + du * half, cv + dv * half, across, SIGNAL_LAYER + 1, HEAD);
            for (int i = 0; i < side.lamps().size(); i++) {
                float at = (i - (side.lamps().size() - 1) / 2f) * lamp * 2.6f;
                out.square(cu + du * at, cv + dv * at, lamp, SIGNAL_LAYER + 2, lamp(side.lamps().get(i)));
            }
        }));
    }

    /**
     * A speed-limit sign at ({@code u}, {@code v}), on a post to the right of the trains it holds, or
     * further out or to their left to be clear: its limit in a red ring, or, for a release, black
     * triangles meeting in the middle of an off-white plate edged in black, as Create Train Next's sign.
     */
    private static void sign(float u, float v, LiveMap.Limit sign, float grow, int width, int height, Map<Integer, Quads> quads,
        Map<Integer, List<Label>> labels, List<Pending> pending) {
        float du = (float) sign.direction().x();
        float dv = (float) -sign.direction().z();
        float length = (float) Math.hypot(du, dv);
        float ru = length < 1e-6f ? 0 : dv / length;
        float rv = length < 1e-6f ? 1 : -du / length;
        float size = SIGN_SIZE * grow;
        List<Rect> places = new ArrayList<>();
        for (float away : SIGN_PLACES)
            places.add(Rect.around(u + ru * BESIDE * grow * away, v + rv * BESIDE * grow * away, size, size));
        pending.add(new Pending(new MarkLayout.Item(places, false), index -> {
            float cu = u + ru * BESIDE * grow * SIGN_PLACES[index];
            float cv = v + rv * BESIDE * grow * SIGN_PLACES[index];
            int tile = tileOf(cu, cv, width, height);
            Quads out = quads.computeIfAbsent(tile, unused -> new Quads());
            out.line(u, v, cu, cv, POST_WIDTH * grow, POST_LAYER, POST);
            if (sign.release()) {
                float inner = size * 0.8f;
                out.square(cu, cv, size, SIGN_LAYER, RELEASE_BLACK);
                out.square(cu, cv, inner, SIGN_LAYER + 1, RELEASE);
                out.add(cu - inner, cv + inner, cu + inner, cv + inner, cu, cv, cu, cv, SIGN_LAYER + 2, RELEASE_BLACK);
                out.add(cu, cv, cu, cv, cu + inner, cv - inner, cu - inner, cv - inner, SIGN_LAYER + 2, RELEASE_BLACK);
            } else {
                out.square(cu, cv, size, SIGN_LAYER, SIGN_EDGE);
                out.square(cu, cv, size * 0.78f, SIGN_LAYER + 1, SIGN);
                labels.computeIfAbsent(tile, unused -> new ArrayList<>())
                    .add(new Label(Integer.toString(sign.kmh()), cu, cv, LIMIT_SCALE * grow, SIGN_TEXT, true, SIGN_LAYER + 2));
            }
        }));
    }

    /** A station's name beside the middle ({@code u}, {@code v}) of its marks, wherever round it is clear; left out if nowhere is. */
    private void name(String name, float u, float v, float grow, int width, int height, Map<Integer, List<Label>> labels,
        List<Pending> pending) {
        float scale = NAME_SCALE * grow;
        float textWidth = font.width(name) * scale;
        float half = font.lineHeight * scale / 2;
        float gapU = STATION_SIZE * grow * 1.4f;
        float gapV = STATION_SIZE * grow * 1.6f;
        // Where the name starts, at the middle of its height: up and down on the right, on the left, then above and below.
        float[][] starts = {{u + gapU, v + gapV}, {u + gapU, v - gapV}, {u - gapU - textWidth, v + gapV}, {u - gapU - textWidth, v - gapV},
            {u - textWidth / 2, v + gapV * 1.6f}, {u - textWidth / 2, v - gapV * 1.6f}};
        List<Rect> places = new ArrayList<>();
        for (float[] start : starts)
            places.add(new Rect(start[0], start[1] - half, start[0] + textWidth, start[1] + half));
        pending.add(new Pending(new MarkLayout.Item(places, true), index -> labels
            .computeIfAbsent(tileOf(starts[index][0], starts[index][1], width, height), unused -> new ArrayList<>())
            .add(new Label(name, starts[index][0], starts[index][1], scale, TEXT, false, NAME_LAYER))));
    }

    /** The trains, as the server last said, on the stretches shown; a train only if a tag it runs on is on. */
    private void trains(VertexConsumer quads, Matrix4f matrix, ScreenSettings settings, Box box, Shown shown) {
        if (!settings.shows("trains"))
            return;
        double scale = settings.scale();
        Quads out = new Quads();
        for (LiveMap.Train train : MapClient.live().trains()) {
            if (!TagView.trainShown(train.tags(), settings.tagsOff()))
                continue;
            for (LiveMap.Carriage carriage : train.carriages()) {
                if (!shown.at(MapClient.indexOf(carriage.frontTrack()), carriage.front())
                    && !shown.at(MapClient.indexOf(carriage.backTrack()), carriage.back()))
                    continue;
                double[] cut = MapProjection.clip(carriage.front().x(), carriage.front().z(), carriage.back().x(), carriage.back().z(), box);
                if (cut != null)
                    out.line(u(cut[0], box, scale), v(cut[1], box, scale), u(cut[2], box, scale), v(cut[3], box, scale), TRAIN_WIDTH,
                        TRAIN_LAYER, TRAIN);
            }
        }
        quads(quads, matrix, out.toArray(), 0, 0);
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

    /** Quads gathered: four corners, a layer and a colour each. */
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

        /** A line of the given width, its ends squared off a half width past the points. */
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

        /** A square block with a point on its front, {@code half} each side of ({@code u}, {@code v}), pointing ({@code du}, {@code dv}). */
        void pointer(float u, float v, float du, float dv, float half, int layer, int colour) {
            float length = (float) Math.hypot(du, dv);
            if (length < 1e-6f) {
                square(u, v, half, layer, colour);
                return;
            }
            float au = du / length * half;
            float av = dv / length * half;
            // To the left of the way it points.
            float nu = -av;
            float nv = au;
            float shoulder = 0.3f;
            add(u - au + nu, v - av + nv, u + au * shoulder + nu, v + av * shoulder + nv, u + au * shoulder - nu, v + av * shoulder - nv,
                u - au - nu, v - av - nv, layer, colour);
            add(u + au * shoulder + nu, v + av * shoulder + nv, u + au * 1.2f, v + av * 1.2f, u + au * shoulder - nu, v + av * shoulder - nv,
                u + au * shoulder - nu, v + av * shoulder - nv, layer, colour);
        }

        float[] toArray() {
            return java.util.Arrays.copyOf(data, size);
        }
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

    private void label(PoseStack pose, MultiBufferSource buffers, Label label, float column, float row) {
        pose.pushPose();
        pose.translate(label.u() - column - 0.5f, label.v() - row - 0.5f, SURFACE + 0.002f + label.layer() * LAYER);
        pose.scale(label.scale(), -label.scale(), label.scale());
        float x = label.centred() ? -font.width(label.text()) / 2f : 0;
        font.drawInBatch(label.text(), x, -font.lineHeight / 2f + 1, label.colour(), false, pose.last().pose(), buffers,
            Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
        pose.popPose();
    }
}
