package io.github.eyunya11.createrealtimeschedule.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import io.github.eyunya11.createrealtimeschedule.diagram.DiagramModel;
import io.github.eyunya11.createrealtimeschedule.diagram.DutyTimes;
import io.github.eyunya11.createrealtimeschedule.diagram.LineAxis;
import io.github.eyunya11.createrealtimeschedule.network.DiagramOpenPayload;
import io.github.eyunya11.createrealtimeschedule.network.DiagramRequestPayload;
import io.github.eyunya11.createrealtimeschedule.network.DiagramSnapshotPayload;
import io.github.eyunya11.createrealtimeschedule.operation.Call;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.TimetableJson;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The client's copy of what the diagram draws, as the server last sent it. While the diagram is open
 * it asks the server again once a second; when it is closed it asks nothing.
 */
public final class DiagramClient {

    /** How often the open diagram asks the server, in ticks. */
    private static final int REQUEST_TICKS = 20;

    private static boolean received;
    private static int revision = -1;
    private static int timesRevision = -1;
    private static @Nullable Timetable timetable;
    /** The calls of each duty of the timetable, worked out once for each revision of it. */
    private static Map<String, List<Call>> calls = Map.of();
    private static boolean tooLarge;
    private static Map<String, LineAxis> axes = Map.of();
    private static List<DutyTimes> duties = List.of();
    private static long serverNow;
    /** The level's game time when the last snapshot came, from which the server's clock is run on. */
    private static long receivedAt;
    /** Goes up with every snapshot, so that the screen knows to draw it again. */
    private static int version;
    private static String lastLine = "";
    private static int ticks;

    private DiagramClient() {}

    public static void onOpen(DiagramOpenPayload payload) {
        open(payload.line());
    }

    public static void onSnapshot(DiagramSnapshotPayload payload) {
        DiagramSnapshotPayload.Content content = payload.content();
        if (content != null) {
            timetable = null;
            if (!content.json().isEmpty()) {
                TimetableJson.Result result = TimetableJson.parse(content.json());
                if (result.ok())
                    timetable = result.timetable();
            }
            calls = timetable == null ? Map.of() : DiagramModel.calls(timetable);
            tooLarge = content.tooLarge();
            Map<String, LineAxis> byLine = new LinkedHashMap<>();
            for (LineAxis axis : content.axes())
                byLine.put(axis.line(), axis);
            axes = byLine;
        }
        revision = payload.revision();
        if (payload.duties() != null)
            duties = payload.duties();
        timesRevision = payload.timesRevision();
        serverNow = payload.now();
        Minecraft minecraft = Minecraft.getInstance();
        receivedAt = minecraft.level == null ? 0 : minecraft.level.getGameTime();
        received = true;
        version++;
    }

    /** Opens the diagram at the line of id {@code line}, or at the one shown last if that is empty. */
    public static void open(String line) {
        Minecraft.getInstance().setScreen(new DiagramScreen(line.isEmpty() ? lastLine : line));
        request();
    }

    private static void request() {
        PacketDistributor.sendToServer(new DiagramRequestPayload(revision, timesRevision));
    }

    /** Once a client tick: asks the server again while the diagram is open. */
    public static void tick() {
        if (Minecraft.getInstance().screen instanceof DiagramScreen && ++ticks % REQUEST_TICKS == 0)
            request();
    }

    /** Forgets everything, for when the connection ends: the next server counts its revisions from the start. */
    public static void reset() {
        received = false;
        revision = -1;
        timesRevision = -1;
        timetable = null;
        calls = Map.of();
        tooLarge = false;
        axes = Map.of();
        duties = List.of();
        serverNow = 0;
        version++;
    }

    public static boolean received() {
        return received;
    }

    public static @Nullable Timetable timetable() {
        return timetable;
    }

    /** The calls of each duty of {@link #timetable()}, by its id. */
    public static Map<String, List<Call>> calls() {
        return calls;
    }

    public static boolean tooLarge() {
        return tooLarge;
    }

    public static @Nullable LineAxis axis(String line) {
        return axes.get(line);
    }

    public static List<DutyTimes> duties() {
        return duties;
    }

    /**
     * The clock now: the client's own ({@link ClientClock}), which the server sends whenever it changes,
     * or before the server has sent it, the server's as the last snapshot said, run on by the game
     * ticks since.
     */
    public static long now() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null)
            return serverNow;
        return ClientClock.now(minecraft.level).orElse(serverNow + Math.max(0, minecraft.level.getGameTime() - receivedAt));
    }

    public static int version() {
        return version;
    }

    static void shown(String line) {
        lastLine = line;
    }
}
