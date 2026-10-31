package io.github.eyunya11.createrealtimeschedule.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import io.github.eyunya11.createrealtimeschedule.network.TrackTagEditPayload;
import io.github.eyunya11.createrealtimeschedule.network.TrackTagEditPayload.Action;
import io.github.eyunya11.createrealtimeschedule.network.TrackTagOpenPayload;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTags;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The screen of a track tag block: the world's tags, to pick the block's from, make, rename and delete.
 * Each change is sent to the server, which answers with the block as it now is.
 */
public final class TrackTagScreen extends Screen {

    private static final String KEY = "createrealtimeschedule.track_tag.";
    private static final int WIDTH = 240;
    private static final int ROWS = 6;
    private static final int ROW_HEIGHT = 20;
    private static final int LIST_WIDTH = 150;
    private static final int BUTTON_WIDTH = 82;

    private final BlockPos pos;
    private int tag;
    private List<Component> problems;
    private @Nullable Component message;
    /** The tag picked in the list, for the buttons that act on one; at first the block's own. */
    private int selected;
    private int scroll;
    private String typed = "";
    private @Nullable Button rename;
    private @Nullable Button create;

    TrackTagScreen(TrackTagOpenPayload payload) {
        super(Component.translatableWithFallback(KEY + "title", "Track Tag"));
        this.pos = payload.pos();
        update(payload);
        this.selected = tag;
    }

    BlockPos pos() {
        return pos;
    }

    /** The block as the server now has it, after a change. */
    void update(TrackTagOpenPayload payload) {
        tag = payload.tag();
        problems = payload.problems();
        message = payload.message().orElse(null);
        if (minecraft != null)
            rebuildWidgets();
    }

    /** The list of tags has changed, perhaps by another player. */
    void listChanged() {
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
        List<Map.Entry<Integer, String>> tags = new ArrayList<>(TrackTagClient.names().entrySet());
        if (!TrackTagClient.names().containsKey(selected))
            selected = TrackTags.NONE;
        scroll = Mth.clamp(scroll, 0, Math.max(0, tags.size() - ROWS));
        int left = left();
        int y = top() + 30;
        for (int row = 0; row < ROWS && scroll + row < tags.size(); row++) {
            Map.Entry<Integer, String> entry = tags.get(scroll + row);
            int id = entry.getKey();
            Component label = Component.literal(entry.getValue());
            if (id == tag)
                label = label.copy().withStyle(ChatFormatting.GREEN);
            if (id == selected)
                label = Component.literal("> ").append(label).append(" <");
            addRenderableWidget(Button.builder(label, button -> {
                selected = id;
                rebuildWidgets();
            }).bounds(left, y + row * ROW_HEIGHT, LIST_WIDTH, ROW_HEIGHT - 2).build());
        }

        int right = left + WIDTH - BUTTON_WIDTH;
        boolean picked = selected != TrackTags.NONE;
        addRenderableWidget(action(Component.translatableWithFallback(KEY + "use", "Use"), right, y,
            picked && selected != tag, () -> send(Action.SET, selected)));
        addRenderableWidget(action(Component.translatableWithFallback(KEY + "none", "None"), right, y + ROW_HEIGHT,
            tag != TrackTags.NONE, () -> send(Action.SET, TrackTags.NONE)));
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
        setInitialFocus(box);
    }

    private static Button action(Component label, int x, int y, boolean active, Runnable onPress) {
        Button button = Button.builder(label, unused -> onPress.run()).bounds(x, y, BUTTON_WIDTH, ROW_HEIGHT - 2).build();
        button.active = active;
        return button;
    }

    private void send(Action action, int id) {
        PacketDistributor.sendToServer(new TrackTagEditPayload(pos, action, id, typed));
        if (action == Action.CREATE || action == Action.RENAME)
            typed = "";
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
        graphics.drawCenteredString(font, title, width / 2, top, 0xFFFFFF);
        String name = TrackTagClient.name(tag);
        Component current = tag == TrackTags.NONE ? Component.translatableWithFallback(KEY + "none", "None")
            : name == null ? Component.translatableWithFallback(KEY + "deleted_tag", "(a deleted tag)") : Component.literal(name);
        graphics.drawString(font, Component.translatableWithFallback(KEY + "current", "This block: %1$s", current), left, top + 14, 0xFFFFFF);
        int names = TrackTagClient.names().size();
        int y = top + 30;
        if (names == 0)
            graphics.drawString(font, Component.translatableWithFallback(KEY + "empty", "No tags yet: name one below"), left, y + 6, 0xAAAAAA);
        else if (names > ROWS)
            graphics.drawString(font, (scroll + 1) + "-" + Math.min(names, scroll + ROWS) + " / " + names, left + LIST_WIDTH + 4,
                y + 2 * ROW_HEIGHT + 6, 0xAAAAAA);
        int lineY = y + ROWS * ROW_HEIGHT + 6 + ROW_HEIGHT + 4;
        if (message != null)
            lineY = drawWrapped(graphics, message, left, lineY, 0xFFFFFF);
        for (Component problem : problems)
            lineY = drawWrapped(graphics, problem, left, lineY, 0xFF6060);
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
