package io.github.eyunya11.createrealtimeschedule.network;

import java.util.List;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * A player sets what the map screen at {@code pos} shows: the middle of it in the world, how many
 * blocks of the world a block of it shows, the ids of the lines whose track it shows, and the kinds of
 * things it does not show. The whole screen takes them.
 */
public record MapScreenEditPayload(BlockPos pos, double centreX, double centreZ, double scale, List<String> lines, List<String> hidden)
    implements CustomPacketPayload {

    private static final int MAX_TEXT = 256;

    public static final Type<MapScreenEditPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(Createrealtimeschedule.MODID, "map_screen_edit"));

    public static final StreamCodec<ByteBuf, MapScreenEditPayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, MapScreenEditPayload::pos,
        ByteBufCodecs.DOUBLE, MapScreenEditPayload::centreX,
        ByteBufCodecs.DOUBLE, MapScreenEditPayload::centreZ,
        ByteBufCodecs.DOUBLE, MapScreenEditPayload::scale,
        ByteBufCodecs.stringUtf8(MAX_TEXT).apply(ByteBufCodecs.list(MapScreenOpenPayload.MAX_LINES)), MapScreenEditPayload::lines,
        ByteBufCodecs.stringUtf8(MAX_TEXT).apply(ByteBufCodecs.list(16)), MapScreenEditPayload::hidden,
        MapScreenEditPayload::new);

    @Override
    public Type<MapScreenEditPayload> type() {
        return TYPE;
    }
}
