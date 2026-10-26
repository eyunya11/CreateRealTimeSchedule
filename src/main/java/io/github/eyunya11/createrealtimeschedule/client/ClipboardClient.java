package io.github.eyunya11.createrealtimeschedule.client;

import java.security.SecureRandom;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.eyunya11.createrealtimeschedule.network.ClipboardPayload;
import io.github.eyunya11.createrealtimeschedule.network.ClipboardRequestPayload;
import io.github.eyunya11.createrealtimeschedule.network.ClipboardRequests;
import io.github.eyunya11.createrealtimeschedule.network.TimetablePastePayload;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The client's side of the clipboard, which only its own player's commands use (see
 * {@link EditorCommand}): it sends the clipboard when the player pastes, and puts on it only the
 * text the server sends back for a copy the player has just asked for.
 */
public final class ClipboardClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How long a copy waits for the server's answer, in milliseconds. */
    private static final long COPY_TIMEOUT_MILLIS = 10_000;

    private static final ClipboardRequests COPIES = new ClipboardRequests(COPY_TIMEOUT_MILLIS, new SecureRandom());

    private ClipboardClient() {}

    /** Asks the server for text to put on the clipboard. */
    static void copy(ClipboardRequestPayload.What what) {
        PacketDistributor.sendToServer(new ClipboardRequestPayload(what, COPIES.ask(Util.getMillis())));
    }

    /** Sends the clipboard to the server, to load as the timetable. */
    static void paste() {
        String text = Minecraft.getInstance().keyboardHandler.getClipboard();
        boolean tooLong = text.length() > ClipboardPayload.MAX_LENGTH;
        PacketDistributor.sendToServer(new TimetablePastePayload(tooLong ? "" : text, tooLong));
    }

    /** The server's answer to a copy: on the clipboard if it answers the one asked for, and in time; dropped if not. */
    public static void onClipboard(ClipboardPayload payload) {
        if (!COPIES.take(payload.token(), Util.getMillis())) {
            LOGGER.warn("The server sent text for the clipboard that was not asked for, or too late, and it was dropped");
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.keyboardHandler.setClipboard(payload.text());
        if (minecraft.player != null)
            minecraft.player.displayClientMessage(payload.said(), false);
    }
}
