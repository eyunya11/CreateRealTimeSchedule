package io.github.eyunya11.createrealtimeschedule.network;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import io.netty.buffer.ByteBuf;

/**
 * Text for a client to put on the player's clipboard, as {@code /rtnetwork copy} and
 * {@code /rttimetable copy} send it, for the timetable editor to paste.
 *
 * @param text what goes on the clipboard, at most {@link #MAX_LENGTH} characters
 */
public record ClipboardPayload(String text) implements CustomPacketPayload {

    /** The longest text sent either way through the clipboard: as long as a timetable the diagram is sent. */
    public static final int MAX_LENGTH = DiagramSnapshotPayload.MAX_JSON_LENGTH;

    public static final Type<ClipboardPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(Createrealtimeschedule.MODID, "clipboard"));

    public static final StreamCodec<ByteBuf, ClipboardPayload> STREAM_CODEC =
        StreamCodec.composite(ByteBufCodecs.stringUtf8(MAX_LENGTH), ClipboardPayload::text, ClipboardPayload::new);

    @Override
    public Type<ClipboardPayload> type() {
        return TYPE;
    }
}
