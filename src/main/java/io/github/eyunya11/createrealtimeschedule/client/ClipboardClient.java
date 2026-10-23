package io.github.eyunya11.createrealtimeschedule.client;

import io.github.eyunya11.createrealtimeschedule.network.ClipboardPayload;
import io.github.eyunya11.createrealtimeschedule.network.ClipboardRequestPayload;
import io.github.eyunya11.createrealtimeschedule.network.TimetablePastePayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

/** The client's side of the clipboard commands: it puts what the server sends on the clipboard, and sends it what is there when asked. */
public final class ClipboardClient {

    private ClipboardClient() {}

    public static void onClipboard(ClipboardPayload payload) {
        Minecraft.getInstance().keyboardHandler.setClipboard(payload.text());
    }

    public static void onRequest(ClipboardRequestPayload request) {
        String text = Minecraft.getInstance().keyboardHandler.getClipboard();
        boolean tooLong = text.length() > ClipboardPayload.MAX_LENGTH;
        PacketDistributor.sendToServer(new TimetablePastePayload(request.token(), tooLong ? "" : text, tooLong));
    }
}
