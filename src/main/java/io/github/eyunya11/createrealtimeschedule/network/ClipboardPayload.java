package io.github.eyunya11.createrealtimeschedule.network;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The server's answer to a {@link ClipboardRequestPayload}: text for the client to put on the
 * player's clipboard, for the timetable editor to paste, and what to tell the player once it is there.
 *
 * @param token the token of the request
 * @param text  what goes on the clipboard, at most {@link #MAX_LENGTH} characters
 * @param said  what the player is told, such as how many stations were copied
 */
public record ClipboardPayload(long token, String text, Component said) implements CustomPacketPayload {

    /** The longest text sent either way through the clipboard: as long as a timetable the diagram is sent. */
    public static final int MAX_LENGTH = DiagramSnapshotPayload.MAX_JSON_LENGTH;

    public static final Type<ClipboardPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(Createrealtimeschedule.MODID, "clipboard"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClipboardPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_LONG, ClipboardPayload::token,
        ByteBufCodecs.stringUtf8(MAX_LENGTH), ClipboardPayload::text,
        ComponentSerialization.STREAM_CODEC, ClipboardPayload::said,
        ClipboardPayload::new);

    @Override
    public Type<ClipboardPayload> type() {
        return TYPE;
    }
}
