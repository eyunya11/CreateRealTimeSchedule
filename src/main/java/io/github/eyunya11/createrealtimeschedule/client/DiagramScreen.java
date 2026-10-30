package io.github.eyunya11.createrealtimeschedule.client;

import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.vertex.VertexConsumer;

import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.diagram.DiagramModel;
import io.github.eyunya11.createrealtimeschedule.diagram.DiagramModel.PlannedTrain;
import io.github.eyunya11.createrealtimeschedule.diagram.DiagramModel.RanDuty;
import io.github.eyunya11.createrealtimeschedule.diagram.DiagramModel.Segment;
import io.github.eyunya11.createrealtimeschedule.diagram.DiagramModel.WaitMark;
import io.github.eyunya11.createrealtimeschedule.diagram.DiagramView;
import io.github.eyunya11.createrealtimeschedule.diagram.DutyTimes;
import io.github.eyunya11.createrealtimeschedule.diagram.LineAxis;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Line;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;

/**
 * The diagram of one line of the timetable being run: time across, the line's stations down, as far
 * apart as the track between them. The timetable's trains are thin lines, coloured by their type, and
 * deadheads are dashed; the duties of the service day are drawn over them, thick, as they really ran.
 * A train's wait for another is a dashed arrow, from where the train waited for leaves its station or
 * runs through it to where the waiting train leaves.
 *
 * <p>The wheel zooms the time, dragging moves it, the arrow keys go to the line before or after,
 * Page Up and Page Down to the service day before or back to today, and Home back to now.
 */
public final class DiagramScreen extends Screen {

    private static final String KEY = "screen.createrealtimeschedule.diagram.";

    /** How much time is shown at first, in seconds: two hours either side of now. */
    private static final double FIRST_SPAN = 4 * 3600;
    private static final int MARGIN = 6;
    private static final int TITLE_HEIGHT = 16;
    private static final int SCALE_HEIGHT = 22;
    /** The least room between two marks of the time scale, in screen units. */
    private static final int MIN_MARK_GAP = 36;
    private static final double DASH = 4;
    private static final double DASH_GAP = 3;
    /** The length of the strokes of a wait's arrow head, in screen units, and their angle to the arrow. */
    private static final double HEAD = 5;
    private static final double HEAD_COS = Math.cos(Math.toRadians(25));
    private static final double HEAD_SIN = Math.sin(Math.toRadians(25));

    private static final int PLOT_BACKGROUND = 0xE0101418;
    private static final int GRID = 0xFF2C3238;
    private static final int GRID_HOUR = 0xFF464E56;
    private static final int TEXT = 0xFFE0E0E0;
    private static final int TEXT_DIM = 0xFF909090;
    private static final int NOW = 0xFFFFE066;
    private static final int RAN = 0xFFFF5050;
    private static final int GIVEN_UP = 0xFFC070FF;
    /** The editor's accent, as its waits are drawn in the dark. */
    private static final int WAIT = 0xFF6EA2FF;
    private static final int DEADHEAD = 0xFF8A8A8A;
    private static final int UNTYPED = 0xFFB8C4D0;
    private static final int[] TYPE_COLOURS = {0xFF6FA8DC, 0xFF93C47D, 0xFFF6B26B, 0xFFE06666, 0xFF76A5AF, 0xFFFFD966, 0xFFC27BA0};

    private final DiagramView view = new DiagramView(0, FIRST_SPAN);
    private String line;
    /** The service day shown; Long.MIN_VALUE until the first snapshot has said which is today. */
    private long day = Long.MIN_VALUE;
    private boolean centred;
    private @Nullable DiagramModel model;
    private int modelVersion = -1;
    private String modelLine = "";
    private long modelDay;
    private long modelNow;

    DiagramScreen(String line) {
        super(Component.translatableWithFallback(KEY + "title", "Diagram"));
        this.line = line;
    }

    @Override
    public boolean isPauseScreen() {
        // The trains go on running while the diagram is open, even in a world of one's own.
        return false;
    }

    // ------------------------------------------------------------------ what is shown

    private @Nullable Line currentLine(Timetable timetable) {
        List<Line> lines = timetable.lines();
        if (lines.isEmpty())
            return null;
        Line found = timetable.line(line).orElse(lines.getFirst());
        line = found.id();
        DiagramClient.shown(line);
        return found;
    }

    private static long today(long now) {
        return RealClock.day(now);
    }

    /** The day to show first: the day before while a duty of it is still running past midnight, or else today. */
    private static long firstDay(long now) {
        long today = today(now);
        for (DutyTimes duty : DiagramClient.duties())
            if (duty.day() == today - 1 && duty.status() == DutyTimes.Status.RUNNING)
                return today - 1;
        return today;
    }

    private @Nullable DiagramModel model(Timetable timetable, LineAxis axis, long now) {
        // Made again when a snapshot comes, or the line or the day changes; and its duties that ran, when a second has gone by
        // for a set standing until now.
        if (model == null || modelVersion != DiagramClient.version() || !modelLine.equals(axis.line()) || modelDay != day) {
            model = DiagramModel.of(timetable, DiagramClient.calls(), axis, day, DiagramClient.duties(), now);
            modelVersion = DiagramClient.version();
            modelLine = axis.line();
            modelDay = day;
            modelNow = now;
        } else if (now - modelNow >= RealClock.TICKS_PER_SECOND) {
            model = model.at(timetable, DiagramClient.calls(), DiagramClient.duties(), now);
            modelNow = now;
        }
        return model;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        Timetable timetable = DiagramClient.timetable();
        String message = null;
        if (!DiagramClient.received())
            message = text("waiting", "Asking the server for the diagram...");
        else if (DiagramClient.tooLarge())
            message = text("too_large", "The timetable is too large to show");
        else if (timetable == null)
            message = text("none", "No timetable is being run");
        Line shown = timetable == null ? null : currentLine(timetable);
        LineAxis axis = shown == null ? null : DiagramClient.axis(shown.id());
        if (message == null && axis == null)
            message = text("no_axis", "The server has not sent the stations of this line");
        if (message != null) {
            graphics.drawCenteredString(font, message, width / 2, height / 2 - font.lineHeight / 2, TEXT);
            return;
        }

        long now = DiagramClient.now();
        if (day == Long.MIN_VALUE)
            day = firstDay(now);
        double nowSecond = DiagramModel.clockSeconds(now, day);
        if (!centred) {
            view.centreOn(nowSecond);
            centred = true;
        }

        int labelWidth = 0;
        for (String station : axis.stations())
            labelWidth = Math.max(labelWidth, font.width(station));
        int left = MARGIN + labelWidth + MARGIN;
        int top = MARGIN + TITLE_HEIGHT;
        int right = width - MARGIN;
        int bottom = height - MARGIN - SCALE_HEIGHT;
        view.place(left, top, right - left, bottom - top);

        drawTitle(graphics, timetable, shown, now);
        graphics.fill(left, top, right, bottom, PLOT_BACKGROUND);
        drawStations(graphics, axis, left, right);
        drawScale(graphics, top, bottom);

        DiagramModel diagram = model(timetable, axis, now);
        graphics.enableScissor(left, top, right, bottom);
        double length = axis.length();
        for (PlannedTrain train : diagram.planned())
            for (Segment segment : train.segments())
                drawSegment(graphics, segment, length, 1, plannedColour(train), train.deadhead());
        for (RanDuty duty : diagram.ran())
            for (Segment segment : duty.segments())
                drawSegment(graphics, segment, length, 2, duty.times().status() == DutyTimes.Status.GIVEN_UP ? GIVEN_UP : RAN, false);
        for (WaitMark wait : diagram.waits())
            drawWait(graphics, wait, length);
        if (nowSecond >= view.start() && nowSecond <= view.end())
            quad(graphics, view.x(nowSecond), top, view.x(nowSecond), bottom, 1, NOW);
        graphics.flush();
        for (PlannedTrain train : diagram.planned()) {
            double x = view.x(train.labelSecond());
            if (x >= left && x < right)
                graphics.drawString(font, train.number(), (int) x + 2, (int) view.y(train.labelPosition(), length) - font.lineHeight,
                    plannedColour(train));
        }
        // Draw what is batched while the scissor still cuts it to the plot.
        graphics.flush();
        graphics.disableScissor();
    }

    private void drawTitle(GuiGraphics graphics, Timetable timetable, Line shown, long now) {
        String dayText = day == today(now) ? text("today", "today") : text("day_before", "the day before");
        String title = (timetable.name().isEmpty() ? "" : timetable.name() + " - ") + shown.name() + " ("
            + (timetable.lines().indexOf(shown) + 1) + "/" + timetable.lines().size() + "), " + dayText + ", "
            + text("now", "now") + " " + RealClock.format(RealClock.timeOfDay(now));
        graphics.drawString(font, title, MARGIN, MARGIN, TEXT);
        String help = text("help", "Wheel: zoom   Drag: move   Left/Right: line   PgUp/PgDn: day   Home: now");
        graphics.drawString(font, help, width - MARGIN - font.width(help), height - MARGIN - font.lineHeight, TEXT_DIM);
    }

    private void drawStations(GuiGraphics graphics, LineAxis axis, int left, int right) {
        double length = axis.length();
        for (int row = 0; row < axis.stations().size(); row++) {
            int y = (int) Math.round(view.y(axis.position(row), length));
            graphics.fill(left, y, right, y + 1, GRID_HOUR);
            String station = axis.stations().get(row);
            graphics.drawString(font, station, left - MARGIN - font.width(station), y - font.lineHeight / 2, TEXT);
        }
    }

    private void drawScale(GuiGraphics graphics, int top, int bottom) {
        int step = view.step(MIN_MARK_GAP);
        double first = Math.ceil(view.start() / step) * step;
        for (double second = first; second <= view.end(); second += step) {
            int x = (int) Math.round(view.x(second));
            boolean hour = second % 3600 == 0;
            graphics.fill(x, top, x + 1, bottom, hour ? GRID_HOUR : GRID);
            String label = clockText(second);
            graphics.drawString(font, label, x - font.width(label) / 2, bottom + 3, hour ? TEXT : TEXT_DIM);
        }
    }

    /** {@code HH:MM} of seconds from 00:00 of the service day, going on past 24:00 and back before 00:00. */
    private static String clockText(double second) {
        long minutes = Math.round(second / 60);
        long hour = Math.floorDiv(minutes, 60);
        return String.format(Locale.ROOT, "%02d:%02d", hour, Math.floorMod(minutes, 60));
    }

    /** A planned train's colour: its type's, grey for a deadhead, and either faded into the plot when it is cancelled. */
    private static int plannedColour(PlannedTrain train) {
        int colour = train.deadhead() ? DEADHEAD : typeColour(train.type());
        return train.cancelled() ? faded(colour) : colour;
    }

    /** {@code colour} a third of the way from the plot's background to itself. */
    private static int faded(int colour) {
        int faded = 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            int from = PLOT_BACKGROUND >> shift & 0xFF;
            int to = colour >> shift & 0xFF;
            faded |= (from + (to - from) / 3) << shift;
        }
        return faded;
    }

    private static int typeColour(String type) {
        return type.isEmpty() ? UNTYPED : TYPE_COLOURS[Math.floorMod(type.hashCode(), TYPE_COLOURS.length)];
    }

    /** Draws {@code segment}; false if none of it is in the time shown. */
    private boolean drawSegment(GuiGraphics graphics, Segment segment, double length, double thickness, int colour, boolean dashed) {
        double[] cut = view.clip(segment, length);
        if (cut == null)
            return false;
        if (!dashed) {
            quad(graphics, cut[0], cut[1], cut[2], cut[3], thickness, colour);
            return true;
        }
        double dx = cut[2] - cut[0];
        double dy = cut[3] - cut[1];
        double along = Math.hypot(dx, dy);
        if (along < DASH) {
            quad(graphics, cut[0], cut[1], cut[2], cut[3], thickness, colour);
            return true;
        }
        // Dashes count from the segment's own start, not from where the view cuts it, so that they stay
        // where they are while the view is moved.
        double cutOff = Math.hypot(cut[0] - view.x(segment.fromSecond()), cut[1] - view.y(segment.fromPosition(), length));
        for (double start = -(cutOff % (DASH + DASH_GAP)); start < along; start += DASH + DASH_GAP) {
            double from = Math.max(0, start);
            double to = Math.min(along, start + DASH);
            if (to <= from)
                continue;
            quad(graphics, cut[0] + dx * from / along, cut[1] + dy * from / along, cut[0] + dx * to / along, cut[1] + dy * to / along,
                thickness, colour);
        }
        return true;
    }

    /** A wait as a dashed arrow, with its head where the waiting train leaves; faded when there is no wait on the day. */
    private void drawWait(GuiGraphics graphics, WaitMark wait, double length) {
        Segment arrow = wait.arrow();
        if (Double.isNaN(arrow.fromPosition()) || Double.isNaN(arrow.toPosition()))
            return;
        int colour = wait.cancelled() ? faded(WAIT) : WAIT;
        if (!drawSegment(graphics, arrow, length, 1, colour, true))
            return;
        double x1 = view.x(arrow.fromSecond());
        double y1 = view.y(arrow.fromPosition(), length);
        double x2 = view.x(arrow.toSecond());
        double y2 = view.y(arrow.toPosition(), length);
        double along = Math.hypot(x2 - x1, y2 - y1);
        if (along < 1e-6)
            return;
        // The head: two strokes back from the end, turned either way from the arrow. Off the plot, the scissor cuts it.
        double backX = -(x2 - x1) / along;
        double backY = -(y2 - y1) / along;
        for (int side = -1; side <= 1; side += 2) {
            double sin = side * HEAD_SIN;
            quad(graphics, x2, y2, x2 + (backX * HEAD_COS - backY * sin) * HEAD, y2 + (backX * sin + backY * HEAD_COS) * HEAD, 1, colour);
        }
    }

    /**
     * A line from one point to another, {@code thickness} wide, as a thin quad: {@code GuiGraphics.fill}
     * only fills rectangles along the axes. The corners go round the same way as {@code fill}'s, so
     * that the quad is not culled.
     */
    private static void quad(GuiGraphics graphics, double x1, double y1, double x2, double y2, double thickness, int colour) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double along = Math.hypot(dx, dy);
        double nx;
        double ny;
        if (along < 1e-6) {
            nx = thickness / 2;
            ny = 0;
            x2 = x1 + 1e-3;
        } else {
            nx = -dy / along * thickness / 2;
            ny = dx / along * thickness / 2;
        }
        // Lengthen it by half the thickness at either end, so that the segments of a line meet.
        double ex = along < 1e-6 ? 0 : dx / along * thickness / 2;
        double ey = along < 1e-6 ? thickness / 2 : dy / along * thickness / 2;
        float[] xs = {(float) (x1 - ex + nx), (float) (x1 - ex - nx), (float) (x2 + ex - nx), (float) (x2 + ex + nx)};
        float[] ys = {(float) (y1 - ey + ny), (float) (y1 - ey - ny), (float) (y2 + ey - ny), (float) (y2 + ey + ny)};
        double area = 0;
        for (int i = 0; i < 4; i++)
            area += xs[i] * ys[(i + 1) % 4] - xs[(i + 1) % 4] * ys[i];
        Matrix4f pose = graphics.pose().last().pose();
        VertexConsumer consumer = graphics.bufferSource().getBuffer(RenderType.gui());
        // fill goes top left, bottom left, bottom right, top right: a negative area with y down.
        for (int k = 0; k < 4; k++) {
            int i = area < 0 ? k : 3 - k;
            consumer.addVertex(pose, xs[i], ys[i], 0).setColor(colour);
        }
    }

    private static String text(String key, String fallback) {
        return Component.translatableWithFallback(KEY + key, fallback).getString();
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0)
            return false;
        view.zoom(scrollY > 0 ? 0.8 : 1.25, mouseX);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        view.pan(dragX);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        Timetable timetable = DiagramClient.timetable();
        long now = DiagramClient.now();
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT -> {
                if (timetable != null && !timetable.lines().isEmpty()) {
                    List<Line> lines = timetable.lines();
                    int at = Math.max(0, lines.indexOf(timetable.line(line).orElse(lines.getFirst())));
                    line = lines.get(Math.floorMod(at + (keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 1), lines.size())).id();
                }
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_UP, GLFW.GLFW_KEY_PAGE_DOWN -> {
                long wanted = keyCode == GLFW.GLFW_KEY_PAGE_UP ? today(now) - 1 : today(now);
                if (day != Long.MIN_VALUE && wanted != day) {
                    // Keep the same time of day in view.
                    view.centreOn((view.start() + view.end()) / 2 + (day - wanted) * 24.0 * 3600);
                    day = wanted;
                }
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                if (day != Long.MIN_VALUE)
                    view.centreOn(DiagramModel.clockSeconds(now, day));
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }
}
