package io.github.eyunya11.createrealtimeschedule.network;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.codec.NeoForgeStreamCodecs;

/**
 * A client asks the server for text to put on its player's clipboard, as {@code /rteditor copy}, a
 * command of the client, does. The server answers with a {@link ClipboardPayload} carrying the same
 * token, and the client takes no text it did not ask for.
 *
 * @param what  which text
 * @param token what the answer has to carry
 */
public record ClipboardRequestPayload(What what, long token) implements CustomPacketPayload {

    /** The texts a client can ask for. */
    public enum What {
        /** The timetable being run, as {@code /rteditor copy timetable} copies it. */
        TIMETABLE,
        /** The track network, as {@code /rtnetwork export} writes it. */
        NETWORK
    }

    public static final Type<ClipboardRequestPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(Createrealtimeschedule.MODID, "clipboard_request"));

    public static final StreamCodec<FriendlyByteBuf, ClipboardRequestPayload> STREAM_CODEC = StreamCodec.composite(
        NeoForgeStreamCodecs.enumCodec(What.class), ClipboardRequestPayload::what,
        ByteBufCodecs.VAR_LONG, ClipboardRequestPayload::token,
        ClipboardRequestPayload::new);

    @Override
    public Type<ClipboardRequestPayload> type() {
        return TYPE;
    }
}
