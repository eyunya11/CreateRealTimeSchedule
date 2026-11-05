package io.github.eyunya11.createrealtimeschedule.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

import io.github.eyunya11.createrealtimeschedule.map.LiveMap;
import io.github.eyunya11.createrealtimeschedule.map.MapProjection;
import io.github.eyunya11.createrealtimeschedule.map.MapProjection.Box;
import io.github.eyunya11.createrealtimeschedule.map.MarkLayout;
import io.github.eyunya11.createrealtimeschedule.map.MarkLayout.Rect;
import io.github.eyunya11.createrealtimeschedule.map.Polyline;
import io.github.eyunya11.createrealtimeschedule.map.ScreenSettings;
import io.github.eyunya11.createrealtimeschedule.map.TagView;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Lamp;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.SignalSide;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Spot;
import io.github.eyunya11.createrealtimeschedule.network.MapViewPayload;
import net.minecraft.client.gui.Font;

/**
 * What a map screen shows, as quads and labels in the screen's blocks, for the blocks of a screen
 * ({@link MapScreenRenderer}) to draw.
 *
 * <p>A stretch of track shown is drawn in the colours of its track tags that are on, a dash each in
 * turn for more than one, or light grey with no tag; track not shown, by the lines or by the tags, is
 * drawn faint, in no tag's colour, with nothing on it. Track is drawn as lines mitred at their bends
 * ({@link Polyline}).
 *
 * <p>The marks (the stations, pointing the way the trains come in; the signals, a mark on the track
 * pointing the way they hold the trains and a head on a post to the right of it; the speed-limit signs
 * on a post; and the stations' names) are worked out for the whole screen at once, again when the
 * server says what is live, so that they can be kept off one another ({@link MarkLayout}). The trains
 * are drawn as the server last said.
 *
 * <p>Sizes are in blocks of the screen, so that a screen shows the same however much of the world it
 * covers; the marks and the words grow with the square root of the screen's shorter side, so that a
 * large screen, seen from further off, can still be read.
 */
final class MapPainter {

    /** The layers things are drawn in, each over those below it. */
    private static final int POST_LAYER = 3;
    private static final int SIGN_LAYER = 4;
    private static final int STATION_LAYER = 7;
    private static final int SIGNAL_LAYER = 9;
    private static final int NAME_LAYER = 12;
    private static final int TRAIN_LAYER = 13;

    static final int BACKGROUND = 0xFF161B22;
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
    private static final float STATION_SIZE = 0.03f;
    private static final float POINTER_SIZE = 0.018f;
    private static final float LAMP_SIZE = 0.022f;
    private static final float SIGN_SIZE = 0.028f;
    private static final float POST_WIDTH = 0.007f;
    private static final float BESIDE = 0.075f;
    private static final float NAME_SCALE = 0.0055f;
    private static final float LIMIT_SCALE = 0.0035f;
    /**
     * Where a signal's head or a sign may be put, best first: how far from the track, in {@link #BESIDE}s,
     * on the side of its block (positive) or the other, and how far along the track, in its own lengths,
     * forwards (positive) or back. The side of the block is tried all along before the other side, so
     * that a thing crowded off its place moves along the track rather than across it.
     */
    private static final float[][] BESIDE_PLACES = places(new float[] {1, 1.6f, 2.2f, 2.8f}, new float[] {0, 0.7f, -0.7f, 1.4f, -1.4f},
        new float[] {-1, -1.6f}, new float[] {0, 0.7f, -0.7f});
    /** How many lamps a signal's head has room for, lit or not, so that every head looks alike. */
    private static final int HEAD_LAMPS = 2;

    private final Font font;
    /** The marks of the screens drawn lately, by what they were worked out from. */
    private final Map<Object, Marks> marks = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Object, Marks> eldest) {
            return size() > 16;
        }
    };

    MapPainter(Font font) {
        this.font = font;
    }

    /** Each way out on the block's side with each shift along, then each way out on the other side with each of its shifts. */
    private static float[][] places(float[] out, float[] along, float[] otherOut, float[] otherAlong) {
        List<float[]> places = new ArrayList<>();
        for (float away : out)
            for (float shift : along)
                places.add(new float[] {away, shift});
        for (float away : otherOut)
            for (float shift : otherAlong)
                places.add(new float[] {away, shift});
        return places.toArray(float[][]::new);
    }

    /**
     * The middle of a thing put beside the track at ({@code u}, {@code v}), for each of {@link #BESIDE_PLACES}:
     * {@code out} from the track, towards (ru, rv), at least {@code clear} besides, and {@code length}
     * along (du, dv) for each length shifted.
     */
    private static float[][] middles(float u, float v, float du, float dv, float ru, float rv, float out, float clear, float length) {
        float[][] middles = new float[BESIDE_PLACES.length][];
        for (int i = 0; i < BESIDE_PLACES.length; i++) {
            float away = BESIDE_PLACES[i][0];
            float reach = out * away + Math.signum(away) * clear;
            float shift = BESIDE_PLACES[i][1] * length;
            middles[i] = new float[] {u + ru * reach + du * shift, v + rv * reach + dv * shift};
        }
        return middles;
    }

    /** The post from beside the track at ({@code u}, {@code v}), {@code from} off it, to {@code middle}. */
    private static Rect post(float u, float v, float[] middle, float from, float width) {
        float lu = middle[0] - u;
        float lv = middle[1] - v;
        float length = (float) Math.hypot(lu, lv);
        float start = Math.min(from, length);
        return Rect.line(u + lu / length * start, v + lv / length * start, middle[0], middle[1], width / 2);
    }

    /** A label: its text, where it starts on the screen, its size, colour and layer. */
    record Label(String text, float u, float v, float scale, int colour, boolean centred, int layer) {}

    /** The marks of a screen, in the screen's blocks from its bottom left, by the block that draws them ({@link #tileOf}). */
    record Marks(Map<Integer, float[]> quads, Map<Integer, List<Label>> labels) {}

    /** The tracks a screen shows by their indexes, and the stretches of those not all shown with no tag. */
    record Shown(Set<Integer> tracks, Map<Integer, List<TagView.Segment>> stretches) {

        /** What a screen of {@code settings} shows, as the server last said. */
        static Shown of(ScreenSettings settings) {
            return new Shown(MapClient.shown(settings), MapClient.stretches(settings));
        }

        /** Whether the place {@code at} on track {@code index} is on a stretch shown. */
        boolean at(int index, Spot at) {
            if (index < 0 || !tracks.contains(index))
                return false;
            List<TagView.Segment> segments = stretches.get(index);
            return segments == null || TagView.shownAt(segments, TagView.shareOf(MapClient.map().tracks().get(index).points(), at));
        }
    }

    /** The block of a screen {@code width} blocks wide at {@code column} from the left and {@code row} from the bottom. */
    static int tileOf(int column, int row, int width) {
        return column + row * width;
    }

    /**
     * The track of {@code dimension} in {@code box}, a part of the world {@code scale} blocks of it to a
     * block of the screen: quads in the screen's blocks from the bottom left of the box.
     */
    static float[] track(Shown shown, Box box, double scale, int dimension) {
        TrackMap map = MapClient.map();
        Map<String, Integer> colours = TrackTagClient.argbs();
        Quads out = new Quads();
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
        return out.toArray();
    }

    /** The line through {@code points}, cut to the box, at {@code layer} in {@code colour}. */
    private static void polyline(Quads out, List<Spot> points, Box box, double scale, int layer, int colour) {
        for (List<Spot> run : Polyline.runs(points, box)) {
            List<Spot> onScreen = new ArrayList<>(run.size());
            for (Spot point : run)
                onScreen.add(new Spot(u(point.x(), box, scale), v(point.z(), box, scale)));
            List<double[]> corners = Polyline.outline(onScreen, TRACK_WIDTH / 2);
            for (int i = 1; i < corners.size(); i++) {
                double[] a = corners.get(i - 1);
                double[] b = corners.get(i);
                out.add((float) a[0], (float) a[1], (float) b[0], (float) b[1], (float) b[2], (float) b[3], (float) a[2], (float) a[3],
                    layer, colour);
            }
        }
    }

    /** A thing put beside the track: where it may go, and how to draw it in the place it is given. */
    private record Pending(MarkLayout.Item item, IntConsumer draw) {}

    /**
     * The marks of a whole screen of {@code width} by {@code height} blocks, worked out again only when
     * something they come from has changed: the stations and the signals' marks on the track first, which
     * stay where they are, then the signals' heads, the signs and the names, each put clear of those
     * before it if it can be.
     */
    Marks marks(ScreenSettings settings, int width, int height, int dimension, Shown shown) {
        MapViewPayload view = MapClient.view(settings);
        Object key = List.of(MapClient.version(), view == null ? 0L : view.revision(), settings, width, height, dimension,
            MapClient.liveVersion());
        Marks known = marks.get(key);
        if (known != null)
            return known;

        TrackMap map = MapClient.map();
        LiveMap live = MapClient.live();
        double scale = settings.scale();
        Box whole = settings.projection(width, height).whole();
        float grow = (float) Math.sqrt(Math.min(width, height));
        Map<Integer, Quads> quads = new HashMap<>();
        Map<Integer, List<Label>> labels = new HashMap<>();
        List<Rect> fixed = new ArrayList<>();
        List<Pending> pending = new ArrayList<>();

        // The track, drawn or faint: nothing is put over it.
        for (TrackMap.Track track : map.tracks()) {
            if (track.dimension() != dimension)
                continue;
            for (List<Spot> run : Polyline.runs(track.points(), whole))
                for (int i = 1; i < run.size(); i++)
                    fixed.add(Rect.line((run.get(i - 1).x() - whole.minX()) / scale, height - (run.get(i - 1).z() - whole.minZ()) / scale,
                        (run.get(i).x() - whole.minX()) / scale, height - (run.get(i).z() - whole.minZ()) / scale, TRACK_WIDTH / 2));
        }

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
                    signal(u, v, side, settings.shows("signalHeads"), grow, width, height, quads, fixed, pending);
            }

        if (settings.shows("speedLimits"))
            for (LiveMap.Limit sign : live.limits()) {
                if (!shown.at(MapClient.indexOf(sign.track()), sign.at()) || !whole.contains(sign.at().x(), sign.at().z()))
                    continue;
                float u = (float) ((sign.at().x() - whole.minX()) / scale);
                float v = (float) (height - (sign.at().z() - whole.minZ()) / scale);
                sign(u, v, sign, grow, width, height, quads, labels, fixed, pending);
            }

        if (settings.shows("stationNames"))
            for (Map.Entry<String, double[]> entry : middles.entrySet()) {
                double x = entry.getValue()[0] / entry.getValue()[2];
                double z = entry.getValue()[1] / entry.getValue()[2];
                if (whole.contains(x, z))
                    name(entry.getKey(), (float) ((x - whole.minX()) / scale), (float) (height - (z - whole.minZ()) / scale), grow,
                        width, height, labels, pending);
            }

        int[] chosen = MarkLayout.place(fixed, pending.stream().map(Pending::item).toList(), width, height);
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
     * One side of a signal at ({@code u}, {@code v}): a small triangle on the track pointing the way it
     * holds the trains, in the colour of its first lamp, and, if {@code head}, a head on a post, on the
     * side of the track its block is on if there is room, with room for {@link #HEAD_LAMPS} lamps along
     * the way the trains run, those not lit grey.
     */
    private static void signal(float u, float v, SignalSide side, boolean head, float grow, int width, int height, Map<Integer, Quads> quads,
        List<Rect> fixed, List<Pending> pending) {
        float length = (float) Math.hypot(side.direction().x(), side.direction().z());
        if (length < 1e-6f)
            return;
        float du = (float) side.direction().x() / length;
        float dv = (float) -side.direction().z() / length;
        // Towards the side of the signal's block: to the right of the way the trains run, on the screen, or the left.
        float ru = side.left() ? -dv : dv;
        float rv = side.left() ? du : -du;
        int first = side.lamps().isEmpty() ? UNLIT : lamp(side.lamps().getFirst());
        float pointer = POINTER_SIZE * grow;
        Quads onTrack = quads.computeIfAbsent(tileOf(u, v, width, height), unused -> new Quads());
        onTrack.triangle(u, v, du, dv, pointer * 1.4f, SIGNAL_LAYER, HEAD);
        onTrack.triangle(u, v, du, dv, pointer, SIGNAL_LAYER + 1, first);
        fixed.add(Rect.around(u, v, pointer * 1.2f, pointer * 1.2f));
        if (!head)
            return;

        int slots = Math.max(HEAD_LAMPS, side.lamps().size());
        float lamp = LAMP_SIZE * grow;
        float edge = lamp * 0.25f;
        float along = slots * lamp * 2.6f;
        float across = lamp * 2.6f;
        float[][] middles = middles(u, v, du, dv, ru, rv, BESIDE * grow, across / 2, along);
        // The post from clear of the mark on the track, which reaches 1.2 pointers each way.
        float postFrom = pointer * 1.8f;
        List<Rect> places = new ArrayList<>();
        List<Rect> posts = new ArrayList<>();
        for (float[] middle : middles) {
            places.add(Rect.along(middle[0], middle[1], du, dv, along / 2 + edge, across / 2 + edge));
            posts.add(post(u, v, middle, postFrom, POST_WIDTH * grow));
        }
        pending.add(new Pending(new MarkLayout.Item(places, posts, false), index -> {
            float cu = middles[index][0];
            float cv = middles[index][1];
            Quads out = quads.computeIfAbsent(tileOf(cu, cv, width, height), unused -> new Quads());
            out.line(u, v, cu, cv, POST_WIDTH * grow, POST_LAYER, POST);
            float half = along / 2 - across / 2;
            out.line(cu - du * half, cv - dv * half, cu + du * half, cv + dv * half, across + edge * 2, SIGNAL_LAYER, HEAD_EDGE);
            out.line(cu - du * half, cv - dv * half, cu + du * half, cv + dv * half, across, SIGNAL_LAYER + 1, HEAD);
            for (int i = 0; i < slots; i++) {
                float at = (i - (slots - 1) / 2f) * lamp * 2.6f;
                out.square(cu + du * at, cv + dv * at, lamp, SIGNAL_LAYER + 2, i < side.lamps().size() ? lamp(side.lamps().get(i)) : UNLIT);
            }
        }));
    }

    /**
     * A speed-limit sign at ({@code u}, {@code v}): an open arrow on the track pointing the way of the
     * trains it holds, as a sign holds one way only (red for a limit, black on white for a release; open,
     * so that it is not taken for a signal's triangle), and on
     * a post on the side of the track its block is on if there is room, its limit on a plate edged in red
     * as wide as the number needs, or, for a release, black triangles meeting in the middle of an
     * off-white plate edged in black, as Create Train Next's sign.
     */
    private void sign(float u, float v, LiveMap.Limit sign, float grow, int width, int height, Map<Integer, Quads> quads,
        Map<Integer, List<Label>> labels, List<Rect> fixed, List<Pending> pending) {
        float du = (float) sign.direction().x();
        float dv = (float) -sign.direction().z();
        float length = (float) Math.hypot(du, dv);
        float ru = length < 1e-6f ? 0 : (sign.left() ? -dv : dv) / length;
        float rv = length < 1e-6f ? 1 : (sign.left() ? du : -du) / length;
        float pointer = POINTER_SIZE * grow;
        Quads onTrack = quads.computeIfAbsent(tileOf(u, v, width, height), unused -> new Quads());
        float stroke = pointer * 0.35f;
        onTrack.chevron(u, v, du, dv, pointer, stroke * 2.2f, SIGN_LAYER, sign.release() ? RELEASE : HEAD);
        onTrack.chevron(u, v, du, dv, pointer, stroke, SIGN_LAYER + 1, sign.release() ? RELEASE_BLACK : SIGN_EDGE);
        fixed.add(Rect.around(u, v, pointer * 1.2f, pointer * 1.2f));
        String text = Integer.toString(sign.kmh());
        float textScale = LIMIT_SCALE * grow;
        float halfV = SIGN_SIZE * grow;
        float halfU = sign.release() ? halfV : Math.max(halfV, font.width(text) * textScale / 2 / 0.78f + halfV * 0.1f);
        float[][] middles = middles(u, v, length < 1e-6f ? 0 : du / length, length < 1e-6f ? 0 : dv / length, ru, rv, BESIDE * grow,
            Math.max(halfU, halfV), Math.max(halfU, halfV) * 2);
        // The post from clear of the sign's mark on the track, which reaches 1.2 pointers each way.
        float postFrom = pointer * 1.8f;
        List<Rect> places = new ArrayList<>();
        List<Rect> posts = new ArrayList<>();
        for (float[] middle : middles) {
            places.add(Rect.around(middle[0], middle[1], halfU, halfV));
            posts.add(post(u, v, middle, postFrom, POST_WIDTH * grow));
        }
        pending.add(new Pending(new MarkLayout.Item(places, posts, false), index -> {
            float cu = middles[index][0];
            float cv = middles[index][1];
            int tile = tileOf(cu, cv, width, height);
            Quads out = quads.computeIfAbsent(tile, unused -> new Quads());
            out.line(u, v, cu, cv, POST_WIDTH * grow, POST_LAYER, POST);
            if (sign.release()) {
                float inner = halfV * 0.8f;
                out.square(cu, cv, halfV, SIGN_LAYER, RELEASE_BLACK);
                out.square(cu, cv, inner, SIGN_LAYER + 1, RELEASE);
                out.add(cu - inner, cv + inner, cu + inner, cv + inner, cu, cv, cu, cv, SIGN_LAYER + 2, RELEASE_BLACK);
                out.add(cu, cv, cu, cv, cu + inner, cv - inner, cu - inner, cv - inner, SIGN_LAYER + 2, RELEASE_BLACK);
            } else {
                float rim = halfV * 0.22f;
                out.box(cu, cv, halfU, halfV, SIGN_LAYER, SIGN_EDGE);
                out.box(cu, cv, halfU - rim, halfV - rim, SIGN_LAYER + 1, SIGN);
                labels.computeIfAbsent(tile, unused -> new ArrayList<>()).add(new Label(text, cu, cv, textScale, SIGN_TEXT, true, SIGN_LAYER + 2));
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
            places.add(Rect.around(start[0] + textWidth / 2, start[1], textWidth / 2, half));
        pending.add(new Pending(new MarkLayout.Item(places, true), index -> labels
            .computeIfAbsent(tileOf(starts[index][0], starts[index][1], width, height), unused -> new ArrayList<>())
            .add(new Label(name, starts[index][0], starts[index][1], scale, TEXT, false, NAME_LAYER))));
    }

    /**
     * The trains in {@code box}, as the server last said, on the stretches shown; a train only if a tag
     * it runs on is on. Quads in the screen's blocks from the bottom left of the box.
     */
    static float[] trains(ScreenSettings settings, Box box, Shown shown) {
        Quads out = new Quads();
        if (!settings.shows("trains"))
            return out.toArray();
        double scale = settings.scale();
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
        return out.toArray();
    }

    private static int lamp(Lamp lamp) {
        return switch (lamp) {
            case RED -> 0xFFFF4D40;
            case YELLOW -> 0xFFFFD23F;
            case GREEN -> 0xFF3DDC84;
        };
    }

    /** Where in the box the world's x is, from its left, in blocks of the screen. */
    private static float u(double x, Box box, double scale) {
        return (float) ((x - box.minX()) / scale);
    }

    /** Where in the box the world's z is, from its bottom, in blocks of the screen: north is up. */
    private static float v(double z, Box box, double scale) {
        return (float) ((box.maxZ() - z) / scale);
    }

    /** Quads gathered: four corners, a layer and a colour each. */
    static final class Quads {
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
            box(u, v, half, half, layer, colour);
        }

        void box(float u, float v, float halfU, float halfV, int layer, int colour) {
            add(u - halfU, v - halfV, u + halfU, v - halfV, u + halfU, v + halfV, u - halfU, v + halfV, layer, colour);
        }

        /** An open arrow, two strokes {@code width} wide meeting in a point, {@code half} each side of ({@code u}, {@code v}), pointing ({@code du}, {@code dv}). */
        void chevron(float u, float v, float du, float dv, float half, float width, int layer, int colour) {
            float length = (float) Math.hypot(du, dv);
            if (length < 1e-6f)
                return;
            float au = du / length * half;
            float av = dv / length * half;
            float tipU = u + au * 0.6f;
            float tipV = v + av * 0.6f;
            line(u - au * 0.6f - av, v - av * 0.6f + au, tipU, tipV, width, layer, colour);
            line(u - au * 0.6f + av, v - av * 0.6f - au, tipU, tipV, width, layer, colour);
        }

        /** A triangle {@code half} each side of ({@code u}, {@code v}), pointing ({@code du}, {@code dv}). */
        void triangle(float u, float v, float du, float dv, float half, int layer, int colour) {
            float length = (float) Math.hypot(du, dv);
            if (length < 1e-6f)
                return;
            float au = du / length * half;
            float av = dv / length * half;
            add(u + au, v + av, u - au * 0.7f - av * 0.9f, v - av * 0.7f + au * 0.9f, u - au * 0.7f + av * 0.9f, v - av * 0.7f - au * 0.9f,
                u + au, v + av, layer, colour);
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
}
