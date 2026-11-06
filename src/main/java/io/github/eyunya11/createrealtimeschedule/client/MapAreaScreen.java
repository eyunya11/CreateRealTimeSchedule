package io.github.eyunya11.createrealtimeschedule.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.VertexConsumer;

import io.github.eyunya11.createrealtimeschedule.map.AreaPick;
import io.github.eyunya11.createrealtimeschedule.map.MapProjection;
import io.github.eyunya11.createrealtimeschedule.map.MapProjection.Box;
import io.github.eyunya11.createrealtimeschedule.map.ScreenLayout.Place;
import io.github.eyunya11.createrealtimeschedule.map.ScreenSettings;
import io.github.eyunya11.createrealtimeschedule.network.MapViewPayload;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Where a map screen picks the part of the world it shows on a map: the screen's frame stays in the
 * middle, as the screen's blocks show it, and the map is dragged under it and zoomed about the mouse
 * with the wheel. The track round the frame is drawn darkened, so that one can see which way to go;
 * the marks and the trains only in it, as on the screen. Where the screen showed when this was opened
 * is outlined in dashes, until the frame is back on it.
 *
 * <p>As the map is dragged and zoomed, the middle snaps to whole blocks and the scale to round steps,
 * a step for every three notches of the wheel ({@link AreaPick}), so that the settings read well; with
 * Shift held neither does, and the wheel zooms a little each notch, for a place in between.
 *
 * <p>Nothing here is coloured but the map: the frames are white, a solid line for now and dashes for
 * before, and the words white for the values and grey for the rest, so that every colour on the map
 * means what it means on the screen. Done gives the middle and the scale to the settings, which send
 * them with the rest; Cancel and Escape go back to the settings as they were.
 */
public class MapAreaScreen extends Screen {

    private static final String KEY = "gui.createrealtimeschedule.map_screen.";
    /** The bar under the map, with the values over the buttons. */
    private static final int BAR = 52;
    /** Room round the frame, and over it for the hint. */
    private static final int MARGIN = 16;
    private static final int TOP = 20;
    /** The least size of a block of the screen, in the window's pixels, for which the lines between the blocks are drawn. */
    private static final int GRID_FROM = 16;

    private static final int DARKEN = 0x99000000;
    private static final int BAR_COLOUR = 0xF00D1117;
    private static final int FRAME = 0xFFFFFFFF;
    private static final int FRAME_BEFORE = 0x66FFFFFF;
    private static final int GRID = 0x30FFFFFF;
    private static final int VALUE = 0xFFFFFF;
    private static final int NOTE = 0xA0A0A0;

    private final MapScreenEditor parent;
    /** The lines, the tags and the kinds shown, which this does not change. */
    private final ScreenSettings base;
    private final MapProjection before;
    /** What the screen is to show, as it is dragged and zoomed. */
    private final AreaPick pick;
    private final MapPainter painter;

    /** Whether the map has been dragged or zoomed yet: the hint is shown until it has. */
    private boolean touched;
    private boolean dragging;

    /** The frame in the window: its top left, its size, and a block of the screen in the window's pixels. */
    private int frameLeft;
    private int frameTop;
    private int frameWidth;
    private int frameHeight;
    private double block;

    /**
     * The track round the window, by its layers, and the tracks shown; what they were worked out from,
     * and the part of the world the track was worked out for, larger than the window, so that it is
     * worked out again only when the scale changes or the window goes off it, not at each step of a drag.
     */
    private Object trackKey;
    private Box trackBox;
    private float[][] track;
    private MapPainter.Shown shown;
    /** The marks of the frame last drawn, and their quads and labels by their layers. */
    private MapPainter.Marks markedFrom;
    private float[][] marked;
    private List<List<MapPainter.Label>> labelled;

    MapAreaScreen(MapScreenEditor parent, ScreenSettings settings, Place place, Font font) {
        super(Component.translatableWithFallback(KEY + "area.title", "Where the screen shows"));
        this.parent = parent;
        this.base = settings;
        this.before = settings.projection(place.width(), place.height());
        this.pick = new AreaPick(before);
        this.painter = new MapPainter(font);
    }

    @Override
    protected void init() {
        int areaWidth = Math.max(1, width - MARGIN * 2);
        int areaHeight = Math.max(1, height - BAR - TOP - 8);
        block = AreaPick.block(areaWidth, areaHeight, projection().width(), projection().height());
        frameWidth = (int) Math.round(block * projection().width());
        frameHeight = (int) Math.round(block * projection().height());
        frameLeft = (width - frameWidth) / 2;
        frameTop = TOP + (areaHeight - frameHeight) / 2;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> done()).bounds(width / 2 - 104, height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose()).bounds(width / 2 + 4, height - 28, 100, 20).build());
    }

    /** What the screen would show now. */
    private MapProjection projection() {
        return pick.shown();
    }

    /** The settings as they are now. */
    private ScreenSettings settings() {
        MapProjection projection = projection();
        return new ScreenSettings(projection.centreX(), projection.centreZ(), projection.scale(), base.lines(), base.hidden(), base.tagsOff(),
            base.stamp());
    }

    @Override
    public void tick() {
        super.tick();
        MapClient.drawn(settings());
    }

    private void done() {
        parent.placed(projection().centreX(), projection().centreZ(), projection().scale());
        onClose();
    }

    @Override
    public void onClose() {
        if (minecraft != null)
            minecraft.setScreen(parent);
    }

    private boolean onMap(double mouseY) {
        return mouseY < height - BAR;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button))
            return true;
        dragging = button == 0 && onMap(mouseY);
        if (dragging)
            pick.press();
        return dragging;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!dragging)
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        pick.drag(-dragX / block, -dragY / block, hasShiftDown());
        touched = true;
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0)
            dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /**
     * Up zooms in and down out, about the place under the mouse: a step every three notches, or a little
     * each notch with Shift held.
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0 || !onMap(mouseY))
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        double right = (mouseX - (frameLeft + frameWidth / 2.0)) / block;
        double down = (mouseY - (frameTop + frameHeight / 2.0)) / block;
        pick.wheel(scrollY, right, down, hasShiftDown());
        touched = true;
        return true;
    }

    /** The map, under the window's widgets. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, MapPainter.BACKGROUND);
        // Each part drawn before the next is begun, as the window draws its kinds of things in no order.
        graphics.flush();
        int dimension = minecraft == null || minecraft.level == null ? -1
            : MapClient.dimension(minecraft.level.dimension().location().toString());
        ScreenSettings settings = settings();
        if (dimension >= 0) {
            // The track over the whole window, as the frame's blocks show it.
            double scale = projection().scale();
            double centreU = frameLeft + frameWidth / 2.0;
            double centreV = frameTop + frameHeight / 2.0;
            Box window = new Box(projection().centreX() - centreU / block * scale, projection().centreZ() - centreV / block * scale,
                projection().centreX() + (width - centreU) / block * scale, projection().centreZ() + (height - centreV) / block * scale);
            MapViewPayload view = MapClient.view(settings);
            // The lines and the tags are not in it, as they do not change here.
            Object key = List.of(MapClient.version(), view == null ? 0L : view.revision(), scale, dimension, TrackTagClient.names(),
                TrackTagClient.colours());
            if (!key.equals(trackKey)) {
                trackKey = key;
                shown = MapPainter.Shown.of(settings);
                trackBox = null;
            }
            if (trackBox == null || !covers(trackBox, window)) {
                // As far again round the window each way, so that a drag is drawn from it for a while.
                double wide = window.maxX() - window.minX();
                double high = window.maxZ() - window.minZ();
                trackBox = new Box(window.minX() - wide, window.minZ() - high, window.maxX() + wide, window.maxZ() + high);
                track = layered(List.of(MapPainter.track(shown, trackBox, scale, dimension)));
            }
            double left = (trackBox.minX() - window.minX()) / scale * block;
            double bottom = (trackBox.maxZ() - window.minZ()) / scale * block;
            for (float[] layer : track)
                quads(graphics, layer, left, bottom);
            graphics.flush();
        }

        // Round the frame, darkened.
        int right = frameLeft + frameWidth;
        int bottom = frameTop + frameHeight;
        graphics.fill(0, 0, width, frameTop, DARKEN);
        graphics.fill(0, bottom, width, height, DARKEN);
        graphics.fill(0, frameTop, frameLeft, bottom, DARKEN);
        graphics.fill(right, frameTop, width, bottom, DARKEN);
        graphics.flush();

        // In the frame, the marks and the trains, as on the screen: a layer after another, the words of each
        // with it, so that what is over what is as on the screen's blocks.
        if (dimension >= 0) {
            graphics.enableScissor(frameLeft, frameTop, right, bottom);
            MapPainter.Marks marks = painter.marks(settings, projection().width(), projection().height(), dimension, shown);
            if (marks != markedFrom) {
                markedFrom = marks;
                marked = layered(marks.quads().values());
                labelled = new ArrayList<>();
                for (int layer = 0; layer < MapPainter.LAYERS; layer++)
                    labelled.add(new ArrayList<>());
                for (List<MapPainter.Label> labels : marks.labels().values())
                    for (MapPainter.Label label : labels)
                        labelled.get(layerOf(label.layer())).add(label);
            }
            float[][] trains = layered(List.of(MapPainter.trains(settings, projection().whole(), shown)));
            for (int layer = 0; layer < MapPainter.LAYERS; layer++) {
                quads(graphics, marked[layer], frameLeft, bottom);
                quads(graphics, trains[layer], frameLeft, bottom);
                if (labelled.get(layer).isEmpty())
                    continue;
                graphics.flush();
                for (MapPainter.Label label : labelled.get(layer))
                    label(graphics, label, frameLeft, bottom);
                graphics.flush();
            }
            graphics.flush();
            graphics.disableScissor();
        } else {
            graphics.drawCenteredString(font, Component.translatableWithFallback(KEY + "area.no_track", "No track in this dimension"),
                frameLeft + frameWidth / 2, frameTop + frameHeight / 2 - font.lineHeight / 2, NOTE);
        }

        // Where the screen showed before, and the frame with the lines between its blocks.
        frameBefore(graphics);
        if (block >= GRID_FROM) {
            for (int column = 1; column < projection().width(); column++) {
                int x = frameLeft + (int) Math.round(column * block);
                graphics.fill(x, frameTop, x + 1, bottom, GRID);
            }
            for (int row = 1; row < projection().height(); row++) {
                int y = frameTop + (int) Math.round(row * block);
                graphics.fill(frameLeft, y, right, y + 1, GRID);
            }
        }
        graphics.renderOutline(frameLeft - 1, frameTop - 1, frameWidth + 2, frameHeight + 2, FRAME);

        if (!touched)
            graphics.drawCenteredString(font,
                Component.translatableWithFallback(KEY + "area.hint", "Drag to move, scroll to zoom, Shift for fine steps"), width / 2,
                (TOP - font.lineHeight) / 2 + 1, NOTE);
        graphics.fill(0, height - BAR, width, height, BAR_COLOUR);
    }

    /** The outline of where the screen showed when this was opened, in dashes, unless the frame is on it. */
    private void frameBefore(GuiGraphics graphics) {
        Box was = before.whole();
        Box now = projection().whole();
        double per = block / projection().scale();
        long left = Math.round(frameLeft + (was.minX() - now.minX()) * per) - 1;
        long top = Math.round(frameTop + (was.minZ() - now.minZ()) * per) - 1;
        long right = Math.round(frameLeft + (was.maxX() - now.minX()) * per);
        long bottom = Math.round(frameTop + (was.maxZ() - now.minZ()) * per);
        if (left == frameLeft - 1 && top == frameTop - 1 && right == frameLeft + frameWidth && bottom == frameTop + frameHeight)
            return;
        if (right < 0 || left >= width || bottom < 0 || top >= height)
            return;
        // Only the dashes in the window, however far off the frame is.
        int fromX = (int) Math.max(left, 0);
        int toX = (int) Math.min(right, width);
        int fromY = (int) Math.max(top, 0);
        int toY = (int) Math.min(bottom, height);
        for (long x = left + (fromX - left) / 6 * 6; x < toX; x += 6) {
            if (top >= 0 && top < height)
                graphics.fill((int) x, (int) top, (int) Math.min(x + 3, right), (int) top + 1, FRAME_BEFORE);
            if (bottom >= 0 && bottom < height)
                graphics.fill((int) x, (int) bottom, (int) Math.min(x + 3, right + 1), (int) bottom + 1, FRAME_BEFORE);
        }
        for (long y = top + (fromY - top) / 6 * 6; y < toY; y += 6) {
            if (left >= 0 && left < width)
                graphics.fill((int) left, (int) y, (int) left + 1, (int) Math.min(y + 3, bottom), FRAME_BEFORE);
            if (right >= 0 && right < width)
                graphics.fill((int) right, (int) y, (int) right + 1, (int) Math.min(y + 3, bottom), FRAME_BEFORE);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        MapProjection projection = projection();
        MutableComponent scale = value(projection.scale());
        if (projection.scale() >= MapProjection.MAX_SCALE)
            scale.append(Component.literal(" ").append(Component.translatableWithFallback(KEY + "area.most", "(most)").withColor(NOTE)));
        else if (projection.scale() <= MapProjection.MIN_SCALE)
            scale.append(Component.literal(" ").append(Component.translatableWithFallback(KEY + "area.least", "(least)").withColor(NOTE)));
        graphics.drawCenteredString(font, Component.translatableWithFallback(KEY + "area.at", "Middle X %1$s, Z %2$s, %3$s blocks per block",
            value(projection.centreX()), value(projection.centreZ()), scale), width / 2, height - BAR + 8, NOTE);
    }

    private static MutableComponent value(double value) {
        return Component.literal(MapScreenEditor.show(value)).withColor(VALUE);
    }

    /** Whether {@code outer} has all of {@code inner} in it. */
    private static boolean covers(Box outer, Box inner) {
        return outer.minX() <= inner.minX() && outer.minZ() <= inner.minZ() && outer.maxX() >= inner.maxX() && outer.maxZ() >= inner.maxZ();
    }

    /** {@code layer} as one of {@link MapPainter#LAYERS}. */
    private static int layerOf(float layer) {
        return Math.max(0, Math.min(MapPainter.LAYERS - 1, (int) layer));
    }

    /** The quads of {@code all} by their layers, each layer's in the order they come in, so that they can be drawn a layer at a time. */
    private static float[][] layered(Collection<float[]> all) {
        int[] sizes = new int[MapPainter.LAYERS];
        for (float[] q : all)
            for (int i = 0; i < q.length; i += 10)
                sizes[layerOf(q[i + 8])] += 10;
        float[][] layers = new float[MapPainter.LAYERS][];
        for (int layer = 0; layer < layers.length; layer++)
            layers[layer] = new float[sizes[layer]];
        int[] filled = new int[MapPainter.LAYERS];
        for (float[] q : all)
            for (int i = 0; i < q.length; i += 10) {
                int layer = layerOf(q[i + 8]);
                System.arraycopy(q, i, layers[layer], filled[layer], 10);
                filled[layer] += 10;
            }
        return layers;
    }

    /** Draws quads in a screen's blocks, from the place {@code left}, {@code bottom} in the window, in the order they come in. */
    private void quads(GuiGraphics graphics, float[] q, double left, double bottom) {
        if (q.length == 0)
            return;
        Matrix4f matrix = graphics.pose().last().pose();
        VertexConsumer out = graphics.bufferSource().getBuffer(RenderType.debugQuads());
        for (int i = 0; i < q.length; i += 10) {
            int colour = Float.floatToRawIntBits(q[i + 9]);
            for (int corner = 0; corner < 4; corner++) {
                float x = (float) (left + q[i + corner * 2] * block);
                float y = (float) (bottom - q[i + corner * 2 + 1] * block);
                out.addVertex(matrix, x, y, 0).setColor(colour);
            }
        }
    }

    private void label(GuiGraphics graphics, MapPainter.Label label, double left, double bottom) {
        graphics.pose().pushPose();
        graphics.pose().translate(left + label.u() * block, bottom - label.v() * block, 0);
        float size = (float) (label.scale() * block);
        graphics.pose().scale(size, size, 1);
        float x = label.centred() ? -font.width(label.text()) / 2f : 0;
        font.drawInBatch(label.text(), x, -font.lineHeight / 2f + 1, label.colour(), false, graphics.pose().last().pose(),
            graphics.bufferSource(), Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        graphics.pose().popPose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
