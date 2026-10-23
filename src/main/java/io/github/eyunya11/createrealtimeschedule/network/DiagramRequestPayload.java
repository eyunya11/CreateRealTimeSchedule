package io.github.eyunya11.createrealtimeschedule.network;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import io.netty.buffer.ByteBuf;

/**
 * A client showing the diagram asks for what it draws, once a second while the screen is open, so the
 * server keeps no list of who is looking. It answers with a {@link DiagramSnapshotPayload}.
 *
 * @param revision the revision of the timetable the client has, or -1 if it has none
 */
public record DiagramRequestPayload(int revision) implements CustomPacketPayload {

    public static final Type<DiagramRequestPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(Createrealtimeschedule.MODID, "diagram_request"));

    public static final StreamCodec<ByteBuf, DiagramRequestPayload> STREAM_CODEC =
        StreamCodec.composite(ByteBufCodecs.VAR_INT, DiagramRequestPayload::revision, DiagramRequestPayload::new);

    @Override
    public Type<DiagramRequestPayload> type() {
        return TYPE;
    }
}
