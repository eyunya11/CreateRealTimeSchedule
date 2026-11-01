package io.github.eyunya11.createrealtimeschedule.network;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * A client that has a map screen in sight asks what the signals of its dimension show and where the
 * trains are, every half second while it does. The server answers with a {@link MapLivePayload}.
 */
public record MapLiveRequestPayload() implements CustomPacketPayload {

    public static final MapLiveRequestPayload INSTANCE = new MapLiveRequestPayload();

    public static final Type<MapLiveRequestPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(Createrealtimeschedule.MODID, "map_live_request"));

    public static final StreamCodec<ByteBuf, MapLiveRequestPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<MapLiveRequestPayload> type() {
        return TYPE;
    }
}
