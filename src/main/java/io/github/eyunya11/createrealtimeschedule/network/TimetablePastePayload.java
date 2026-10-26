package io.github.eyunya11.createrealtimeschedule.network;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import io.netty.buffer.ByteBuf;

/**
 * The text on a player's clipboard, which the client sends when its player runs
 * {@code /rteditor paste}, a command of the client, for the server to load as the timetable. The
 * server never asks for it: a client sends its clipboard only on its own player's command.
 *
 * @param text    the clipboard's text; empty if there is none, or it is too long to send
 * @param tooLong whether the clipboard held more than {@link ClipboardPayload#MAX_LENGTH} characters
 */
public record TimetablePastePayload(String text, boolean tooLong) implements CustomPacketPayload {

    public static final Type<TimetablePastePayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(Createrealtimeschedule.MODID, "timetable_paste"));

    public static final StreamCodec<ByteBuf, TimetablePastePayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.stringUtf8(ClipboardPayload.MAX_LENGTH), TimetablePastePayload::text,
        ByteBufCodecs.BOOL, TimetablePastePayload::tooLong,
        TimetablePastePayload::new);

    @Override
    public Type<TimetablePastePayload> type() {
        return TYPE;
    }
}
