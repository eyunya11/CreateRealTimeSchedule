package io.github.eyunya11.createrealtimeschedule.diagram;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;

import io.github.eyunya11.createrealtimeschedule.clock.ClockService;
import io.github.eyunya11.createrealtimeschedule.network.DiagramRequestPayload;
import io.github.eyunya11.createrealtimeschedule.network.DiagramSnapshotPayload;
import io.github.eyunya11.createrealtimeschedule.operation.DutyRun;
import io.github.eyunya11.createrealtimeschedule.operation.OperationData;
import io.github.eyunya11.createrealtimeschedule.operation.TrackFacts;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The server's side of the diagram: what a client that asks is sent. Unlike the rest of the package it
 * reads the game, for the track and the trains.
 */
public final class DiagramService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * The least time between two answers to one player, in ticks: half the second a client waits
     * between asks, so that one asking on time is always answered.
     */
    private static final int ANSWER_GAP = 10;

    private static final RequestLimit LIMIT = new RequestLimit(ANSWER_GAP);

    private DiagramService() {}

    /** Answers a client's {@link DiagramRequestPayload}, unless it asked again too soon. */
    public static void onRequest(DiagramRequestPayload request, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && LIMIT.allow(player.getUUID(), player.server.getTickCount()))
            PacketDistributor.sendToPlayer(player, snapshot(player.server, request.revision(), request.timesRevision()));
    }

    /** Forgets a player who has left, for the limit on answers. */
    public static void forget(ServerPlayer player) {
        LIMIT.forget(player.getUUID());
    }

    /**
     * What to send a client that has the timetable of {@code revision} and the duties' times of
     * {@code timesRevision}: what it has already is left out.
     */
    public static DiagramSnapshotPayload snapshot(MinecraftServer server, int revision, int timesRevision) {
        OperationData data = OperationData.get(server);
        Timetable timetable = data.timetable();
        DiagramSnapshotPayload.Content content = null;
        if (revision != data.revision()) {
            if (timetable == null) {
                content = new DiagramSnapshotPayload.Content("", false, List.of());
            } else if (data.json().length() > DiagramSnapshotPayload.MAX_JSON_LENGTH) {
                LOGGER.warn("The timetable is {} characters long, more than the diagram takes ({})", data.json().length(),
                    DiagramSnapshotPayload.MAX_JSON_LENGTH);
                content = new DiagramSnapshotPayload.Content("", true, List.of());
            } else {
                content = new DiagramSnapshotPayload.Content(data.json(), false, List.copyOf(axes(data, timetable).values()));
            }
        }
        List<DutyTimes> duties = timesRevision == data.timesRevision() ? null : duties(data);
        return new DiagramSnapshotPayload(data.revision(), content, data.timesRevision(), duties, ClockService.now(server));
    }

    /**
     * The axis of each line of the timetable, worked out from the track the first time it is asked for
     * ({@link TrackFacts}).
     */
    public static Map<String, LineAxis> axes(OperationData data, Timetable timetable) {
        return TrackFacts.axes(data, timetable);
    }

    /** The times of the duties running, and of those that stopped running that the server keeps. */
    public static List<DutyTimes> duties(OperationData data) {
        List<DutyTimes> duties = new ArrayList<>();
        for (OperationData.Record record : data.history())
            duties.add(DutyTimes.of(record.run(), record.train(), record.status()));
        for (Map.Entry<UUID, DutyRun> entry : data.runs().entrySet()) {
            Train train = Create.RAILWAYS.trains.get(entry.getKey());
            duties.add(DutyTimes.of(entry.getValue(), train == null ? "" : train.name.getString(), DutyTimes.Status.RUNNING));
        }
        return duties;
    }
}
