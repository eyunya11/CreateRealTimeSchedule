package io.github.eyunya11.createrealtimeschedule.network;

import java.util.List;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Which tracks a map screen showing {@code lines} shows ({@code MapSelection}), by their keys: those
 * keys, or, with {@code except}, every track but those (for all the track, but the hidden).
 *
 * @param revision what the answer was worked out from: it is another when the track or the timetable changes
 */
public record MapViewPayload(List<String> lines, long revision, boolean except, List<String> keys) implements CustomPacketPayload {

    /** The most keys sent: a hundred thousand tracks, far more than a world has. */
    public static final int MAX_KEYS = 100_000;

    public static final Type<MapViewPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(Createrealtimeschedule.MODID, "map_view"));

    public static final StreamCodec<ByteBuf, MapViewPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.stringUtf8(256).apply(ByteBufCodecs.list(MapScreenOpenPayload.MAX_LINES)), MapViewPayload::lines,
        ByteBufCodecs.VAR_LONG, MapViewPayload::revision,
        ByteBufCodecs.BOOL, MapViewPayload::except,
        ByteBufCodecs.stringUtf8(512).apply(ByteBufCodecs.list(MAX_KEYS)), MapViewPayload::keys,
        MapViewPayload::new);

    @Override
    public Type<MapViewPayload> type() {
        return TYPE;
    }
}
