package io.github.eyunya11.createrealtimeschedule.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import io.github.eyunya11.createrealtimeschedule.network.TrackTagEditPayload;
import io.github.eyunya11.createrealtimeschedule.network.TrackTagEditPayload.Action;
import io.github.eyunya11.createrealtimeschedule.network.TrackTagOpenPayload;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTags;
import io.github.eyunya11.createrealtimeschedule.timetable.TypeColours;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The screen of a track tag block: the world's tags, to give the block or take off it, make, rename,
 * colour and delete. A block may have any number of tags. A tag's colour, which the map draws its
 * sections in, is picked from the same colours as a train's type in the editor, or written as
 * {@code #rrggbb}.
 * Each change is sent to the server, which answers with the block as it now is.
 */
public final class TrackTagScreen extends Screen {

    private static final String KEY = "createrealtimeschedule.track_tag.";
    private static final int WIDTH = 240;
    private static final int ROWS = 6;
    private static final int ROW_HEIGHT = 20;
    private static final int LIST_WIDTH = 150;
    private static final int BUTTON_WIDTH = 82;
    /** The side of a colour to pick, and the room each takes. */
    private static final int SWATCH = 12;
    private static final int SWATCH_STEP = 14;

    private final BlockPos pos;
    private List<Integer> tags = List.of();
    private boolean oneWay;
    private List<Component> problems;
    private @Nullable Component message;
    /** The tag picked in the list, for the buttons that act on one; at first the block's first. */
    private int selected;
    private int scroll;
    private String typed = "";
    /** The name last asked to be made or given, kept in the box until the list has it: a refused one stays to be put right. */
    private @Nullable String asked;
    private @Nullable Button rename;
    private @Nullable Button create;
    /** The colour being written for the picked tag, until it is one; null to show the tag's own. */
    private @Nullable String colourTyped;
    /** The colours to pick, as they are laid out now: their buttons, in the order of {@link TypeColours#TEMPLATE}. */
    private final List<Button> swatches = new ArrayList<>();

    TrackTagScreen(TrackTagOpenPayload payload) {
        super(Component.translatableWithFallback(KEY + "title", "Track Tag"));
        this.pos = payload.pos();
        update(payload);
        this.selected = tags.isEmpty() ? TrackTags.NONE : tags.get(0);
    }

    BlockPos pos() {
        return pos;
    }

    /** The block as the server now has it, after a change. */
    void update(TrackTagOpenPayload payload) {
        tags = payload.tags();
        oneWay = payload.oneWay();
        problems = payload.problems();
        message = payload.message().orElse(null);
        if (minecraft != null)
            rebuildWidgets();
    }

    /** The list of tags has changed, perhaps by another player, or to have the name asked for. */
    void listChanged() {
        if (asked != null && TrackTagClient.names().containsValue(asked)) {
            typed = "";
            asked = null;
        }
        rebuildWidgets();
    }

    private int left() {
        return (width - WIDTH) / 2;
    }

    private int top() {
        return Math.max(8, height / 2 - 120);
    }

    @Override
    protected void init() {
        List<Map.Entry<Integer, String>> names = new ArrayList<>(TrackTagClient.names().entrySet());
        if (!TrackTagClient.names().containsKey(selected))
            selected = TrackTags.NONE;
        scroll = Mth.clamp(scroll, 0, Math.max(0, names.size() - ROWS));
        int left = left();
        int y = top() + 30;
        for (int row = 0; row < ROWS && scroll + row < names.size(); row++) {
            Map.Entry<Integer, String> entry = names.get(scroll + row);
            int id = entry.getKey();
            Component label = Component.literal(entry.getValue());
            if (tags.contains(id))
                label = Component.literal("✔ ").append(label).withStyle(ChatFormatting.GREEN);
            if (id == selected)
                label = Component.literal("> ").append(label).append(" <");
            addRenderableWidget(Button.builder(label, button -> {
                if (selected != id)
                    colourTyped = null;
                selected = id;
                rebuildWidgets();
            }).bounds(left, y + row * ROW_HEIGHT, LIST_WIDTH, ROW_HEIGHT - 2).build());
        }

        int right = left + WIDTH - BUTTON_WIDTH;
        boolean picked = selected != TrackTags.NONE;
        Component toggle = tags.contains(selected) ? Component.translatableWithFallback(KEY + "remove", "Take Off")
            : Component.translatableWithFallback(KEY + "add", "Give");
        addRenderableWidget(action(toggle, right, y, picked, () -> send(Action.TOGGLE, selected)));
        addRenderableWidget(action(Component.translatableWithFallback(KEY + "clear", "Take All Off"), right, y + ROW_HEIGHT,
            !tags.isEmpty(), () -> send(Action.CLEAR, TrackTags.NONE)));
        rename = addRenderableWidget(action(Component.translatableWithFallback(KEY + "rename", "Rename"), right, y + 3 * ROW_HEIGHT,
            picked && !typed.isBlank(), () -> send(Action.RENAME, selected)));
        addRenderableWidget(action(Component.translatableWithFallback(KEY + "delete", "Delete"), right, y + 4 * ROW_HEIGHT,
            picked, () -> send(Action.DELETE, selected)));

        int boxY = y + ROWS * ROW_HEIGHT + 6;
        EditBox box = new EditBox(font, left + 1, boxY + 1, LIST_WIDTH - 2, ROW_HEIGHT - 4,
            Component.translatableWithFallback(KEY + "name", "Name"));
        box.setMaxLength(TrackTags.MAX_NAME_LENGTH);
        box.setValue(typed);
        box.setHint(Component.translatableWithFallback(KEY + "name", "Name").withStyle(ChatFormatting.DARK_GRAY));
        box.setResponder(value -> {
            typed = value;
            create.active = !value.isBlank();
            rename.active = selected != TrackTags.NONE && !value.isBlank();
        });
        addRenderableWidget(box);
        create = addRenderableWidget(action(Component.translatableWithFallback(KEY + "create", "Create"), right, boxY,
            !typed.isBlank(), () -> send(Action.CREATE, TrackTags.NONE)));

        int colourY = boxY + ROW_HEIGHT;
        swatches.clear();
        for (int i = 0; i < TypeColours.TEMPLATE.size(); i++) {
            String colour = TypeColours.TEMPLATE.get(i);
            Button swatch = Button.builder(Component.empty(), unused -> recolour(colour))
                .bounds(left + i * SWATCH_STEP, colourY + (ROW_HEIGHT - 2 - SWATCH) / 2, SWATCH, SWATCH)
                .tooltip(Tooltip.create(Component.literal(colour))).build();
            swatch.active = picked;
            swatches.add(addRenderableWidget(swatch));
        }
        int hexX = left + TypeColours.TEMPLATE.size() * SWATCH_STEP;
        EditBox hex = new EditBox(font, hexX + 1, colourY + 1, LIST_WIDTH - (hexX - left) - 2, ROW_HEIGHT - 4,
            Component.translatableWithFallback(KEY + "colour", "Colour"));
        hex.setMaxLength(7);
        String own = TrackTagClient.colours().get(selected);
        hex.setValue(colourTyped != null ? colourTyped : own != null ? own : "");
        hex.setHint(Component.literal("#rrggbb").withStyle(ChatFormatting.DARK_GRAY));
        hex.setEditable(picked);
        hex.setResponder(value -> {
            colourTyped = value;
            if (TypeColours.isColour(value) && !value.equalsIgnoreCase(TrackTagClient.colours().get(selected)))
                recolour(value);
        });
        addRenderableWidget(hex);
        addRenderableWidget(action(Component.translatableWithFallback(KEY + "default_colour", "Colour by Name"), right, colourY,
            picked, () -> recolour("")));
        setInitialFocus(box);
    }

    /** Asks for the picked tag to be drawn in {@code colour}; an empty one is the colour its name picks. */
    private void recolour(String colour) {
        colourTyped = null;
        PacketDistributor.sendToServer(new TrackTagEditPayload(pos, Action.COLOUR, selected, colour));
    }

    private static Button action(Component label, int x, int y, boolean active, Runnable onPress) {
        Button button = Button.builder(label, unused -> onPress.run()).bounds(x, y, BUTTON_WIDTH, ROW_HEIGHT - 2).build();
        button.active = active;
        return button;
    }

    private void send(Action action, int id) {
        PacketDistributor.sendToServer(new TrackTagEditPayload(pos, action, id, typed));
        if (action == Action.CREATE || action == Action.RENAME)
            asked = typed.strip();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int before = scroll;
        scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0, Math.max(0, TrackTagClient.names().size() - ROWS));
        if (scroll != before)
            rebuildWidgets();
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int left = left();
        int top = top();
        drawColours(graphics);
        Component kind = oneWay ? Component.translatableWithFallback(KEY + "one_way", "One way: for trains running the way it faces")
            : Component.translatableWithFallback(KEY + "both_ways", "Both ways: for trains running either way");
        graphics.drawCenteredString(font, Component.empty().append(title).append(" - ").append(kind), width / 2, top, 0xFFFFFF);
        Component current = tags.isEmpty() ? Component.translatableWithFallback(KEY + "none", "None") : Component.literal(TrackTagClient.names(tags));
        graphics.drawString(font, Component.translatableWithFallback(KEY + "current", "This block: %1$s", current), left, top + 14, 0xFFFFFF);
        int names = TrackTagClient.names().size();
        int y = top + 30;
        if (names == 0)
            graphics.drawString(font, Component.translatableWithFallback(KEY + "empty", "No tags yet: name one below"), left, y + 6, 0xAAAAAA);
        else if (names > ROWS)
            graphics.drawString(font, (scroll + 1) + "-" + Math.min(names, scroll + ROWS) + " / " + names, left + LIST_WIDTH + 4,
                y + 2 * ROW_HEIGHT + 6, 0xAAAAAA);
        int lineY = y + ROWS * ROW_HEIGHT + 6 + 2 * ROW_HEIGHT + 4;
        if (message != null)
            lineY = drawWrapped(graphics, message, left, lineY, 0xFFFFFF);
        for (Component problem : problems)
            lineY = drawWrapped(graphics, problem, left, lineY, 0xFF6060);
    }

    /**
     * Draws the colours over their buttons, the picked tag's own with a white frame, and each tag's
     * colour at the left of its row of the list.
     */
    private void drawColours(GuiGraphics graphics) {
        String own = TrackTagClient.colours().get(selected);
        for (int i = 0; i < swatches.size(); i++) {
            Button swatch = swatches.get(i);
            String colour = TypeColours.TEMPLATE.get(i);
            if (colour.equalsIgnoreCase(own))
                graphics.fill(swatch.getX() - 1, swatch.getY() - 1, swatch.getRight() + 1, swatch.getBottom() + 1, 0xFFFFFFFF);
            int argb = TypeColours.argb(colour);
            graphics.fill(swatch.getX() + 1, swatch.getY() + 1, swatch.getRight() - 1, swatch.getBottom() - 1,
                swatch.active ? argb : (argb & 0x00FFFFFF) | 0x60000000);
        }
        List<Map.Entry<Integer, String>> names = new ArrayList<>(TrackTagClient.names().entrySet());
        int y = top() + 30;
        for (int row = 0; row < ROWS && scroll + row < names.size(); row++) {
            String colour = TrackTagClient.colours().get(names.get(scroll + row).getKey());
            if (colour != null && TypeColours.isColour(colour))
                graphics.fill(left() + 3, y + row * ROW_HEIGHT + 3, left() + 7, y + row * ROW_HEIGHT + ROW_HEIGHT - 5, TypeColours.argb(colour));
        }
    }

    /** Draws {@code text} wrapped to the screen's width from {@code y}; the y below it. */
    private int drawWrapped(GuiGraphics graphics, Component text, int x, int y, int colour) {
        for (FormattedCharSequence line : font.split(text, WIDTH)) {
            graphics.drawString(font, line, x, y, colour);
            y += font.lineHeight + 1;
        }
        return y + 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
