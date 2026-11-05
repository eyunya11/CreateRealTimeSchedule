package io.github.eyunya11.createrealtimeschedule.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.eyunya11.createrealtimeschedule.map.MapProjection;
import io.github.eyunya11.createrealtimeschedule.map.ScreenLayout.Place;
import io.github.eyunya11.createrealtimeschedule.map.ScreenSettings;
import io.github.eyunya11.createrealtimeschedule.map.TagView;
import io.github.eyunya11.createrealtimeschedule.network.MapScreenEditPayload;
import io.github.eyunya11.createrealtimeschedule.network.MapScreenOpenPayload;
import io.github.eyunya11.createrealtimeschedule.screen.MapScreenBlockEntity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The settings of a map screen: the world's X and Z in the middle of it, how many blocks of the world
 * a block of it shows, the lines whose track it shows (none for all), and what it shows on the track.
 * "Fit all" puts on it all the track of the dimension the lines and the tags as ticked show (all of it
 * if they show none). Done sends them to the server, which gives them to every block of the screen.
 */
public class MapScreenEditor extends Screen {

    private static final String KEY = "gui.createrealtimeschedule.map_screen.";
    /** The most lines listed; those that do not fit, or are past these, keep what they had. */
    private static final int MOST_LINES = 12;
    /** How far apart the rows of boxes to tick are. */
    private static final int ROW = 20;
    /** Where the left column starts, and the kinds to show under it. */
    private static final int TOP = 40;
    private static final int LAYERS_TOP = TOP + 106;
    /** The colour of the text in a box, as Minecraft's own, and of a value the server would not take. */
    private static final int TEXT = 0xE0E0E0;
    private static final int WRONG = 0xFF5555;

    private final MapScreenBlockEntity entity;
    private final List<MapScreenOpenPayload.Line> lines;
    /** The settings to start from: the screen's, or, when the window is resized, those ticked so far. */
    private ScreenSettings settings;
    private final Place place;

    private EditBox x;
    private EditBox z;
    private EditBox scale;
    private Button fit;
    private Button done;
    private final Map<String, Checkbox> lineBoxes = new LinkedHashMap<>();
    private final Map<String, Checkbox> layerBoxes = new LinkedHashMap<>();
    /** The track tags listed, "no tag" first, by their names; those that do not fit keep what they had. */
    private final Map<String, Checkbox> tagBoxes = new LinkedHashMap<>();
    /** Where the tags are listed from, and how many there are to list. */
    private int tagsTop;
    private int tagCount;
    /** Where it says how many lines are not listed. */
    private int moreLinesTop;
    /** Where the right column, of the lines and the tags, starts. */
    private int right;

    public MapScreenEditor(MapScreenBlockEntity entity, List<MapScreenOpenPayload.Line> lines) {
        super(Component.translatableWithFallback(KEY + "title", "Map screen"));
        this.entity = entity;
        this.lines = lines;
        this.settings = entity.settings();
        this.place = entity.place();
    }

    @Override
    protected void init() {
        int left = width / 2 - 160;
        // Above the Done and Cancel buttons.
        int bottom = height - 36;
        // Again, as when the window is resized: what was typed and ticked stays.
        String[] typed = null;
        if (x != null) {
            settings = chosen();
            typed = new String[] {x.getValue(), z.getValue(), scale.getValue()};
        }
        lineBoxes.clear();
        layerBoxes.clear();
        tagBoxes.clear();
        x = number(left + 90, TOP, typed != null ? typed[0] : show(settings.centreX()), "x");
        z = number(left + 90, TOP + 22, typed != null ? typed[1] : show(settings.centreZ()), "z");
        scale = number(left + 90, TOP + 44, typed != null ? typed[2] : show(settings.scale()), "scale");
        fit = addRenderableWidget(Button.builder(Component.translatableWithFallback(KEY + "fit", "Fit all track"), button -> fit())
            .bounds(left, TOP + 68, 150, 20).build());
        fit.active = MapClient.view(chosen()) != null;

        // The kinds to show, in two columns if one does not fit above the buttons.
        List<String> layers = ScreenSettings.SCREEN_LAYERS;
        int perColumn = LAYERS_TOP + layers.size() * ROW > bottom ? (layers.size() + 1) / 2 : layers.size();
        int column = left;
        int widest = 0;
        for (int i = 0; i < layers.size(); i++) {
            if (i > 0 && i % perColumn == 0) {
                column += widest + 8;
                widest = 0;
            }
            String layer = layers.get(i);
            Checkbox box = Checkbox.builder(Component.translatableWithFallback(KEY + "layer." + layer, layer), font)
                .pos(column, LAYERS_TOP + i % perColumn * ROW).selected(settings.shows(layer)).build();
            widest = Math.max(widest, box.getWidth());
            layerBoxes.put(layer, addRenderableWidget(box));
        }
        // The right column, clear of the kinds to show.
        right = Math.max(width / 2 + 10, column + widest + 10);

        // The lines, as many as fit with room under them for the tags' heading and one tag.
        int y = TOP + 12;
        for (MapScreenOpenPayload.Line line : lines.subList(0, Math.min(MOST_LINES, lines.size()))) {
            if (y + ROW > bottom - 12 - ROW)
                break;
            Checkbox box = Checkbox.builder(Component.literal(line.name()), font).pos(right, y).selected(settings.lines().contains(line.id()))
                .build();
            lineBoxes.put(line.id(), addRenderableWidget(box));
            y += ROW;
        }
        moreLinesTop = y;

        // The track tags under the lines, as many as fit above the buttons.
        tagsTop = y + (lineBoxes.size() < lines.size() ? 12 : 0) + 4;
        List<String> tags = new ArrayList<>();
        tags.add(TagView.NO_TAG);
        tags.addAll(TrackTagClient.names().values());
        tagCount = tags.size();
        Map<String, Integer> colours = TrackTagClient.argbs();
        y = tagsTop + 12;
        for (String tag : tags) {
            if (y + ROW > bottom)
                break;
            Component label = tag.equals(TagView.NO_TAG) ? Component.translatableWithFallback(KEY + "no_tag", "No tag")
                : Component.literal(tag).withColor(colours.get(tag) & 0xFFFFFF);
            Checkbox box = Checkbox.builder(label, font).pos(right, y).selected(!settings.tagsOff().contains(tag)).build();
            tagBoxes.put(tag, addRenderableWidget(box));
            y += ROW;
        }

        done = addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> done()).bounds(width / 2 - 104, height - 30, 100, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose()).bounds(width / 2 + 4, height - 30, 100, 20).build());
        checkCentre();
    }

    /**
     * Marks a middle out of the world in red, and holds Done back with a tooltip saying why, until it is
     * put right: the server would not take it, and nothing would seem to happen.
     */
    private void checkCentre() {
        boolean xIn = ScreenSettings.isCentre(read(x, settings.centreX()));
        boolean zIn = ScreenSettings.isCentre(read(z, settings.centreZ()));
        x.setTextColor(xIn ? TEXT : WRONG);
        z.setTextColor(zIn ? TEXT : WRONG);
        done.active = xIn && zIn;
        if (done.active && done.getTooltip() != null)
            done.setTooltip(null);
        else if (!done.active && done.getTooltip() == null)
            done.setTooltip(Tooltip.create(Component.translatableWithFallback(KEY + "centre_out",
                "The middle's X and Z must be from %1$s to %2$s", show(-ScreenSettings.MAX_CENTRE), show(ScreenSettings.MAX_CENTRE))));
    }

    private EditBox number(int left, int top, String value, String name) {
        EditBox box = new EditBox(font, left, top, 70, 18, Component.translatableWithFallback(KEY + name, name));
        box.setValue(value);
        box.setFilter(text -> text.matches("-?[0-9]*\\.?[0-9]*"));
        return addRenderableWidget(box);
    }

    private static String show(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.2f", value);
    }

    private static double read(EditBox box, double fallback) {
        try {
            double value = Double.parseDouble(box.getValue());
            return Double.isFinite(value) ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * The track the lines and the tags as ticked now show is asked about while the settings are open,
     * so that "Fit all" can fit it; the button waits for the answer.
     */
    @Override
    public void tick() {
        super.tick();
        ScreenSettings chosen = chosen();
        MapClient.drawn(chosen);
        fit.active = MapClient.view(chosen) != null;
        checkCentre();
    }

    /** Fills in the middle and the scale that put on the screen all the track the lines and the tags as ticked now show. */
    private void fit() {
        if (minecraft == null || minecraft.level == null)
            return;
        double[] fit = MapProjection.fit(MapClient.shownBounds(MapClient.dimension(minecraft.level.dimension().location().toString()), chosen()),
            place.width(), place.height());
        if (fit == null)
            return;
        x.setValue(show(Math.round(fit[0])));
        z.setValue(show(Math.round(fit[1])));
        scale.setValue(show(fit[2]));
    }

    /** The settings as they are now, but for the middle and the scale. */
    private ScreenSettings chosen() {
        List<String> chosen = new ArrayList<>();
        for (MapScreenOpenPayload.Line line : lines) {
            Checkbox box = lineBoxes.get(line.id());
            if (box != null ? box.selected() : settings.lines().contains(line.id()))
                chosen.add(line.id());
        }
        // Lines the timetable no longer has are dropped: they show nothing.
        Set<String> hidden = new LinkedHashSet<>();
        layerBoxes.forEach((layer, box) -> {
            if (!box.selected())
                hidden.add(layer);
        });
        // A tag not listed, or one the world no longer has, keeps what it had.
        Set<String> tagsOff = new LinkedHashSet<>(settings.tagsOff());
        tagBoxes.forEach((tag, box) -> {
            if (box.selected())
                tagsOff.remove(tag);
            else
                tagsOff.add(tag);
        });
        return new ScreenSettings(settings.centreX(), settings.centreZ(), settings.scale(), chosen, hidden, tagsOff, settings.stamp());
    }

    private void done() {
        ScreenSettings chosen = chosen();
        PacketDistributor.sendToServer(new MapScreenEditPayload(entity.getBlockPos(), read(x, settings.centreX()), read(z, settings.centreZ()),
            MapProjection.clampScale(read(scale, settings.scale())), chosen.lines(), new ArrayList<>(chosen.hidden()),
            new ArrayList<>(chosen.tagsOff())));
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int left = width / 2 - 160;
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        graphics.drawString(font, Component.translatableWithFallback(KEY + "size", "%1$s × %2$s blocks", place.width(), place.height()),
            left, TOP - 14, 0xA0A0A0);
        graphics.drawString(font, Component.translatableWithFallback(KEY + "x", "Middle X"), left, TOP + 5, 0xFFFFFF);
        graphics.drawString(font, Component.translatableWithFallback(KEY + "z", "Middle Z"), left, TOP + 27, 0xFFFFFF);
        graphics.drawString(font, Component.translatableWithFallback(KEY + "scale", "Blocks per block"), left, TOP + 49, 0xFFFFFF);
        graphics.drawString(font, Component.translatableWithFallback(KEY + "layers", "Show"), left, LAYERS_TOP - 12, 0xA0A0A0);
        graphics.drawString(font, lines.isEmpty() ? Component.translatableWithFallback(KEY + "no_timetable", "No timetable is run: all the track")
            : Component.translatableWithFallback(KEY + "lines", "Lines (none: all the track)"), right, TOP - 2, 0xA0A0A0);
        if (lineBoxes.size() < lines.size())
            graphics.drawString(font, Component.translatableWithFallback(KEY + "more_lines", "and %1$s more", lines.size() - lineBoxes.size()),
                right, moreLinesTop + 2, 0xA0A0A0);
        Component tags = Component.translatableWithFallback(KEY + "tags", "Track tags (off: grey)");
        if (tagBoxes.size() < tagCount)
            tags = Component.empty().append(tags).append(" ")
                .append(Component.translatableWithFallback(KEY + "more_tags", "and %1$s more", tagCount - tagBoxes.size()));
        graphics.drawString(font, tags, right, tagsTop, 0xA0A0A0);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
