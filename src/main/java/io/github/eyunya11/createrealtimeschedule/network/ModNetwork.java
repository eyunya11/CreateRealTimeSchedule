package io.github.eyunya11.createrealtimeschedule.network;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.client.ClientClock;
import io.github.eyunya11.createrealtimeschedule.client.ClipboardClient;
import io.github.eyunya11.createrealtimeschedule.client.DiagramClient;
import io.github.eyunya11.createrealtimeschedule.command.RtTimetableCommand;
import io.github.eyunya11.createrealtimeschedule.diagram.DiagramService;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = Createrealtimeschedule.MODID)
public final class ModNetwork {

    /** 2 since the diagram's payloads came in, 3 since the clipboard's. */
    private static final String PROTOCOL_VERSION = "3";

    private ModNetwork() {}

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // Not optional: the real_time_of_day condition is saved in schedules and sent to clients, and a
        // client that does not know it cannot read the schedules that hold it. Leaving the channel
        // required keeps a client without this mod off a server that has it, with a message saying why.
        // The client handlers of the diagram and the clipboard are lambdas, so that the classes they
        // call, which use client classes, are loaded only when a payload comes, which is never on a
        // dedicated server.
        event.registrar(PROTOCOL_VERSION)
            .playToClient(ClockSyncPayload.TYPE, ClockSyncPayload.STREAM_CODEC, ClientClock::onSync)
            .playToClient(DiagramOpenPayload.TYPE, DiagramOpenPayload.STREAM_CODEC, (payload, context) -> DiagramClient.onOpen(payload))
            .playToClient(DiagramSnapshotPayload.TYPE, DiagramSnapshotPayload.STREAM_CODEC,
                (payload, context) -> DiagramClient.onSnapshot(payload))
            .playToServer(DiagramRequestPayload.TYPE, DiagramRequestPayload.STREAM_CODEC, DiagramService::onRequest)
            .playToClient(ClipboardPayload.TYPE, ClipboardPayload.STREAM_CODEC, (payload, context) -> ClipboardClient.onClipboard(payload))
            .playToClient(ClipboardRequestPayload.TYPE, ClipboardRequestPayload.STREAM_CODEC,
                (payload, context) -> ClipboardClient.onRequest(payload))
            .playToServer(TimetablePastePayload.TYPE, TimetablePastePayload.STREAM_CODEC, RtTimetableCommand::onPaste);
    }
}
