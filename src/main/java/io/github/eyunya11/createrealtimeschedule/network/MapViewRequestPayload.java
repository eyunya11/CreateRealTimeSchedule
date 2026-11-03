package io.github.eyunya11.createrealtimeschedule.network;

import java.util.List;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * A client that shows map screens asks, every two seconds while it shows them, which tracks and
 * stretches each of them shows: by the lines and the track tags turned off of each. All of them are
 * asked at once, as the server answers a player only so often. The server answers each with a
 * {@link MapViewPayload} if the answer is not the one of its {@code revision}, which the client has.
 *
 * @param asked the screens' questions, at most {@link #MAX_ASKED}
 */
public record MapViewRequestPayload(List<Asked> asked) implements CustomPacketPayload {

    /**
     * The most questions asked at once. Of more screens shown than this, each set differently, those
     * with no answer yet are asked about first.
     */
    public static final int MAX_ASKED = 16;

    /**
     * What a screen shows.
     *
     * @param lines    the ids of the lines; none for all the track
     * @param tagsOff  the track tags turned off, by their names, {@code ""} for no tag
     * @param revision the revision of the answer the client has, or 0 for none
     */
    public record Asked(List<String> lines, List<String> tagsOff, long revision) {
        static final StreamCodec<ByteBuf, Asked> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(256).apply(ByteBufCodecs.list(MapScreenOpenPayload.MAX_LINES)), Asked::lines,
            ByteBufCodecs.stringUtf8(256).apply(ByteBufCodecs.list(MapScreenEditPayload.MAX_TAGS)), Asked::tagsOff,
            ByteBufCodecs.VAR_LONG, Asked::revision,
            Asked::new);
    }

    public static final Type<MapViewRequestPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(Createrealtimeschedule.MODID, "map_view_request"));

    public static final StreamCodec<ByteBuf, MapViewRequestPayload> STREAM_CODEC = StreamCodec.composite(
        Asked.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ASKED)), MapViewRequestPayload::asked,
        MapViewRequestPayload::new);

    @Override
    public Type<MapViewRequestPayload> type() {
        return TYPE;
    }
}
