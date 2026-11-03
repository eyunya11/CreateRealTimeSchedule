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
import io.github.eyunya11.createrealtimeschedule.timetable.TypeColours;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
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
    /** The most lines listed, which fit the screen; the others keep what they had. */
    private static final int MOST_LINES = 12;

    private final MapScreenBlockEntity entity;
    private final List<MapScreenOpenPayload.Line> lines;
    private final ScreenSettings settings;
    private final Place place;

    private EditBox x;
    private EditBox z;
    private EditBox scale;
    private Button fit;
    private final Map<String, Checkbox> lineBoxes = new LinkedHashMap<>();
    private final Map<String, Checkbox> layerBoxes = new LinkedHashMap<>();
    /** The track tags listed, "no tag" first, by their names; those that do not fit keep what they had. */
    private final Map<String, Checkbox> tagBoxes = new LinkedHashMap<>();
    /** Where the tags are listed from, and how many there are to list. */
    private int tagsTop;
    private int tagCount;

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
        int right = width / 2 + 10;
        int top = 40;
        x = number(left + 90, top, settings.centreX(), "x");
        z = number(left + 90, top + 24, settings.centreZ(), "z");
        scale = number(left + 90, top + 48, settings.scale(), "scale");
        fit = addRenderableWidget(Button.builder(Component.translatableWithFallback(KEY + "fit", "Fit all track"), button -> fit())
            .bounds(left, top + 76, 150, 20).build());
        fit.active = MapClient.view(chosen()) != null;

        int y = top + 120;
        for (String layer : ScreenSettings.SCREEN_LAYERS) {
            Checkbox box = Checkbox.builder(Component.translatableWithFallback(KEY + "layer." + layer, layer), font).pos(left, y)
                .selected(settings.shows(layer)).build();
            layerBoxes.put(layer, addRenderableWidget(box));
            y += 22;
        }

        y = top + 12;
        for (MapScreenOpenPayload.Line line : lines.subList(0, Math.min(MOST_LINES, lines.size()))) {
            Checkbox box = Checkbox.builder(Component.literal(line.name()), font).pos(right, y).selected(settings.lines().contains(line.id()))
                .build();
            lineBoxes.put(line.id(), addRenderableWidget(box));
            y += 22;
        }

        // The track tags under the lines, as many as fit above the buttons.
        tagsTop = y + (lines.size() > MOST_LINES ? 12 : 0) + 6;
        List<String> tags = new ArrayList<>();
        tags.add(TagView.NO_TAG);
        tags.addAll(TrackTagClient.names().values());
        tagCount = tags.size();
        Map<String, Integer> colours = new LinkedHashMap<>();
        TrackTagClient.names().forEach((id, name) -> {
            String colour = TrackTagClient.colours().get(id);
            colours.put(name, colour != null && TypeColours.isColour(colour) ? TypeColours.argb(colour) : TypeColours.argb(TypeColours.byName(name)));
        });
        y = tagsTop + 12;
        for (String tag : tags) {
            if (y + 20 > height - 36)
                break;
            Component label = tag.equals(TagView.NO_TAG) ? Component.translatableWithFallback(KEY + "no_tag", "No tag")
                : Component.literal(tag).withColor(colours.get(tag) & 0xFFFFFF);
            Checkbox box = Checkbox.builder(label, font).pos(right, y).selected(!settings.tagsOff().contains(tag)).build();
            tagBoxes.put(tag, addRenderableWidget(box));
            y += 22;
        }

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> done()).bounds(width / 2 - 104, height - 30, 100, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose()).bounds(width / 2 + 4, height - 30, 100, 20).build());
    }

    private EditBox number(int left, int top, double value, String name) {
        EditBox box = new EditBox(font, left, top, 70, 18, Component.translatableWithFallback(KEY + name, name));
        box.setValue(show(value));
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
        int right = width / 2 + 10;
        int top = 40;
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        graphics.drawString(font, Component.translatableWithFallback(KEY + "size", "%1$s × %2$s blocks", place.width(), place.height()),
            left, top - 14, 0xA0A0A0);
        graphics.drawString(font, Component.translatableWithFallback(KEY + "x", "Middle X"), left, top + 5, 0xFFFFFF);
        graphics.drawString(font, Component.translatableWithFallback(KEY + "z", "Middle Z"), left, top + 29, 0xFFFFFF);
        graphics.drawString(font, Component.translatableWithFallback(KEY + "scale", "Blocks per block"), left, top + 53, 0xFFFFFF);
        graphics.drawString(font, Component.translatableWithFallback(KEY + "layers", "Show"), left, top + 106, 0xA0A0A0);
        graphics.drawString(font, lines.isEmpty() ? Component.translatableWithFallback(KEY + "no_timetable", "No timetable is run: all the track")
            : Component.translatableWithFallback(KEY + "lines", "Lines (none: all the track)"), right, top - 2, 0xA0A0A0);
        if (lines.size() > MOST_LINES)
            graphics.drawString(font, Component.translatableWithFallback(KEY + "more_lines", "and %1$s more", lines.size() - MOST_LINES),
                right, top + 12 + MOST_LINES * 22, 0xA0A0A0);
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
