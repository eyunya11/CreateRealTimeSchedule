package io.github.eyunya11.createrealtimeschedule.command;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.eyunya11.createrealtimeschedule.network.ClipboardPayload;
import io.github.eyunya11.createrealtimeschedule.network.ClipboardRequestPayload;
import io.github.eyunya11.createrealtimeschedule.network.TimetablePastePayload;
import io.github.eyunya11.createrealtimeschedule.operation.OperationData;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The server's side of the clipboard: {@code /rteditor copy} and {@code /rteditor paste}, which are commands
 * of the client, so that the clipboard is touched only
 * when its own player asks. The client asks for text to copy, and the server answers with it; the
 * client sends its clipboard to paste. The server asks nothing of a client, and no command of the
 * server's (not even one run as another player) reaches a clipboard.
 *
 * <p>The server does for a client only what its player may do: what {@code /rttimetable load} and
 * {@code /rtnetwork export} need, permission level 2.
 */
public final class ClipboardService {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String KEY = "commands.createrealtimeschedule.clipboard.";

    private ClipboardService() {}

    /** Text to put on a clipboard, and what to tell the player once it is there. */
    public record Copy(String text, Component said) {}

    /** A client asks for text to copy: the server answers with it, or tells the player why not. */
    public static void onRequest(ClipboardRequestPayload request, IPayloadContext context) {
        CommandSourceStack source = allowed(context);
        if (source == null || !(context.player() instanceof ServerPlayer player))
            return;
        Copy copy = copy(source, request.what());
        if (copy != null)
            PacketDistributor.sendToPlayer(player, new ClipboardPayload(request.token(), copy.text(), copy.said()));
    }

    /**
     * The text to copy for {@code what}, at most {@link ClipboardPayload#MAX_LENGTH} characters; null
     * after telling {@code source} why there is none.
     */
    public static @Nullable Copy copy(CommandSourceStack source, ClipboardRequestPayload.What what) {
        Copy copy = switch (what) {
            case TIMETABLE -> timetable(source);
            case NETWORK -> network(source);
        };
        if (copy != null && copy.text().length() > ClipboardPayload.MAX_LENGTH) {
            source.sendFailure(Component.translatableWithFallback(KEY + "too_large",
                "That is %1$s characters, more than the %2$s the clipboard is sent", copy.text().length(), ClipboardPayload.MAX_LENGTH));
            return null;
        }
        return copy;
    }

    private static @Nullable Copy timetable(CommandSourceStack source) {
        OperationData data = OperationData.get(source.getServer());
        Timetable timetable = data.timetable();
        if (timetable == null) {
            source.sendFailure(Component.translatableWithFallback("commands.createrealtimeschedule.rttimetable.none", "No timetable is being run"));
            return null;
        }
        return new Copy(data.json(), Component.translatableWithFallback("commands.createrealtimeschedule.rttimetable.copied",
            "Copied the timetable %1$s to your clipboard", RtTimetableCommand.name(timetable)));
    }

    private static @Nullable Copy network(CommandSourceStack source) {
        RtNetworkCommand.Written written = RtNetworkCommand.write(source);
        if (written == null)
            return null;
        return new Copy(written.json(), Component.translatableWithFallback("commands.createrealtimeschedule.rtnetwork.copied",
            "Copied %1$s stations and %2$s legs to your clipboard", written.stations(), written.legs()));
    }

    /** A client sends its player's clipboard: it is loaded as {@code /rttimetable load} loads the file. */
    public static void onPaste(TimetablePastePayload paste, IPayloadContext context) {
        CommandSourceStack source = allowed(context);
        if (source == null)
            return;
        Component from = Component.translatableWithFallback("commands.createrealtimeschedule.rttimetable.clipboard", "your clipboard");
        if (paste.tooLong()) {
            source.sendFailure(Component.translatableWithFallback("commands.createrealtimeschedule.rttimetable.paste_too_long",
                "%1$s holds more than the %2$s characters a timetable is sent in", from, ClipboardPayload.MAX_LENGTH));
            return;
        }
        if (paste.text().isBlank()) {
            source.sendFailure(Component.translatableWithFallback("commands.createrealtimeschedule.rttimetable.paste_empty",
                "There is no text in %1$s", from));
            return;
        }
        RtTimetableCommand.loadText(source, paste.text(), from);
    }

    /** The commands of the player who sent the payload, if the player may load timetables; null if not. */
    private static @Nullable CommandSourceStack allowed(IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player))
            return null;
        CommandSourceStack source = player.createCommandSourceStack();
        if (!source.hasPermission(2)) {
            LOGGER.warn("{} may not load timetables or export the network, and what its client sent was dropped", player.getName().getString());
            return null;
        }
        return source;
    }
}
