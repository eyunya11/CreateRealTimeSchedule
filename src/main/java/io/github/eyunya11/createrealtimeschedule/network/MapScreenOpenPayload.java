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
 * The server has a player open the settings of the map screen at {@code pos}, with the lines of the
 * timetable being run to choose from (none if no timetable is). The settings themselves the client
 * has, in the block.
 */
public record MapScreenOpenPayload(BlockPos pos, List<Line> lines) implements CustomPacketPayload {

    /** The most lines offered. */
    public static final int MAX_LINES = 256;
    private static final int MAX_TEXT = 256;

    /**
     * A line of the timetable: its id, and what it is called. A timetable's line may have a name of any
     * length, which could not be sent: one longer than {@link #MAX_TEXT} characters is cut short, with
     * an ellipsis. Its id is never that long ({@code Timetable.MAX_ID_LENGTH}).
     */
    public record Line(String id, String name) {
        static final StreamCodec<ByteBuf, Line> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_TEXT), Line::id, ByteBufCodecs.stringUtf8(MAX_TEXT), Line::name, Line::new);

        public Line {
            name = shortened(name);
        }
    }

    /** {@code text}, or as much of it as fits in {@link #MAX_TEXT} characters with an ellipsis, not cutting a character in two. */
    static String shortened(String text) {
        if (text.length() <= MAX_TEXT)
            return text;
        int end = MAX_TEXT - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1)))
            end--;
        return text.substring(0, end) + "…";
    }

    public static final Type<MapScreenOpenPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(Createrealtimeschedule.MODID, "map_screen_open"));

    public static final StreamCodec<ByteBuf, MapScreenOpenPayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, MapScreenOpenPayload::pos,
        Line.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_LINES)), MapScreenOpenPayload::lines,
        MapScreenOpenPayload::new);

    @Override
    public Type<MapScreenOpenPayload> type() {
        return TYPE;
    }
}
