package io.github.eyunya11.createrealtimeschedule.network;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import io.netty.buffer.ByteBuf;

/**
 * Asks a client for the text on the player's clipboard, as {@code /rttimetable paste} does. The client
 * answers with a {@link TimetablePastePayload} carrying the same token, and the server takes no paste
 * it did not ask for.
 *
 * @param token what the answer has to carry
 */
public record ClipboardRequestPayload(long token) implements CustomPacketPayload {

    public static final Type<ClipboardRequestPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(Createrealtimeschedule.MODID, "clipboard_request"));

    public static final StreamCodec<ByteBuf, ClipboardRequestPayload> STREAM_CODEC =
        StreamCodec.composite(ByteBufCodecs.VAR_LONG, ClipboardRequestPayload::token, ClipboardRequestPayload::new);

    @Override
    public Type<ClipboardRequestPayload> type() {
        return TYPE;
    }
}
