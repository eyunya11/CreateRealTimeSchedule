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
 * blocks of the world a block of it shows, the ids of the lines whose track it shows, the kinds of
 * things it does not show, and the track tags turned off. The whole screen takes them.
 */
public record MapScreenEditPayload(BlockPos pos, double centreX, double centreZ, double scale, List<String> lines, List<String> hidden,
                                   List<String> tagsOff) implements CustomPacketPayload {

    private static final int MAX_TEXT = 256;

    /** The most track tags turned off: far more than a world has. */
    public static final int MAX_TAGS = 256;

    public static final Type<MapScreenEditPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(Createrealtimeschedule.MODID, "map_screen_edit"));

    private static final StreamCodec<ByteBuf, List<String>> LINES =
        ByteBufCodecs.stringUtf8(MAX_TEXT).apply(ByteBufCodecs.list(MapScreenOpenPayload.MAX_LINES));
    private static final StreamCodec<ByteBuf, List<String>> HIDDEN = ByteBufCodecs.stringUtf8(MAX_TEXT).apply(ByteBufCodecs.list(16));
    private static final StreamCodec<ByteBuf, List<String>> TAGS = ByteBufCodecs.stringUtf8(MAX_TEXT).apply(ByteBufCodecs.list(MAX_TAGS));

    public static final StreamCodec<ByteBuf, MapScreenEditPayload> STREAM_CODEC = StreamCodec.of((buffer, edit) -> {
        BlockPos.STREAM_CODEC.encode(buffer, edit.pos());
        buffer.writeDouble(edit.centreX());
        buffer.writeDouble(edit.centreZ());
        buffer.writeDouble(edit.scale());
        LINES.encode(buffer, edit.lines());
        HIDDEN.encode(buffer, edit.hidden());
        TAGS.encode(buffer, edit.tagsOff());
    }, buffer -> new MapScreenEditPayload(BlockPos.STREAM_CODEC.decode(buffer), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
        LINES.decode(buffer), HIDDEN.decode(buffer), TAGS.decode(buffer)));

    @Override
    public Type<MapScreenEditPayload> type() {
        return TYPE;
    }
}
