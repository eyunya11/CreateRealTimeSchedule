package io.github.eyunya11.createrealtimeschedule.network;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import io.netty.buffer.ByteBuf;

/**
 * Tells a client to open the diagram, as {@code /rtdiagram} does.
 *
 * @param line the id of the line to show; empty for the first
 */
public record DiagramOpenPayload(String line) implements CustomPacketPayload {

    public static final Type<DiagramOpenPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(Createrealtimeschedule.MODID, "diagram_open"));

    public static final StreamCodec<ByteBuf, DiagramOpenPayload> STREAM_CODEC =
        StreamCodec.composite(ByteBufCodecs.STRING_UTF8, DiagramOpenPayload::line, DiagramOpenPayload::new);

    @Override
    public Type<DiagramOpenPayload> type() {
        return TYPE;
    }
}
