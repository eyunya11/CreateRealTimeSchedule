package io.github.eyunya11.createrealtimeschedule.network;

import java.util.List;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * A client that shows the track of some lines on a map screen asks which tracks those are, every two
 * seconds while it shows them. The server answers with a {@link MapViewPayload} if the answer is not
 * the one of {@code revision}, which the client has.
 *
 * @param lines    the ids of the lines; none for all the track
 * @param revision the revision of the answer the client has, or 0 for none
 */
public record MapViewRequestPayload(List<String> lines, long revision) implements CustomPacketPayload {

    public static final Type<MapViewRequestPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(Createrealtimeschedule.MODID, "map_view_request"));

    public static final StreamCodec<ByteBuf, MapViewRequestPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.stringUtf8(256).apply(ByteBufCodecs.list(MapScreenOpenPayload.MAX_LINES)), MapViewRequestPayload::lines,
        ByteBufCodecs.VAR_LONG, MapViewRequestPayload::revision,
        MapViewRequestPayload::new);

    @Override
    public Type<MapViewRequestPayload> type() {
        return TYPE;
    }
}
