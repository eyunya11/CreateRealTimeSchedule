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
 * keys, or, with {@code except}, every track but those (for all the track, but the hidden); and, for
 * the track tags {@code tagsOff} turned off, the stretches of the tracks that are not all shown with no
 * tag ({@code TagView}): those shown with tags, in their colours, and those not shown. A track it does
 * not list stretches of is shown with no tag, if it is shown.
 *
 * @param revision what the answer was worked out from: it is another when the track, the tags or the
 *                 timetable change
 */
public record MapViewPayload(List<String> lines, List<String> tagsOff, long revision, boolean except, List<String> keys,
                             List<TrackStretches> stretches) implements CustomPacketPayload {

    /** The most keys sent: a hundred thousand tracks, far more than a world has. */
    public static final int MAX_KEYS = 100_000;

    /**
     * A stretch of a track, as a share of its length from its first point.
     *
     * @param shown whether it is shown
     * @param tags  the names of the tags on it that are on, whose colours it is drawn in
     */
    public record Stretch(float from, float to, boolean shown, List<String> tags) {
        static final StreamCodec<ByteBuf, Stretch> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, Stretch::from,
            ByteBufCodecs.FLOAT, Stretch::to,
            ByteBufCodecs.BOOL, Stretch::shown,
            ByteBufCodecs.stringUtf8(256).apply(ByteBufCodecs.list(MapScreenEditPayload.MAX_TAGS)), Stretch::tags,
            Stretch::new);
    }

    /** The stretches of the track {@code key}, from its first point to its last. */
    public record TrackStretches(String key, List<Stretch> stretches) {
        static final StreamCodec<ByteBuf, TrackStretches> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(512), TrackStretches::key,
            Stretch.STREAM_CODEC.apply(ByteBufCodecs.list(1024)), TrackStretches::stretches,
            TrackStretches::new);
    }

    public static final Type<MapViewPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(Createrealtimeschedule.MODID, "map_view"));

    public static final StreamCodec<ByteBuf, MapViewPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.stringUtf8(256).apply(ByteBufCodecs.list(MapScreenOpenPayload.MAX_LINES)), MapViewPayload::lines,
        ByteBufCodecs.stringUtf8(256).apply(ByteBufCodecs.list(MapScreenEditPayload.MAX_TAGS)), MapViewPayload::tagsOff,
        ByteBufCodecs.VAR_LONG, MapViewPayload::revision,
        ByteBufCodecs.BOOL, MapViewPayload::except,
        ByteBufCodecs.stringUtf8(512).apply(ByteBufCodecs.list(MAX_KEYS)), MapViewPayload::keys,
        TrackStretches.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_KEYS)), MapViewPayload::stretches,
        MapViewPayload::new);

    @Override
    public Type<MapViewPayload> type() {
        return TYPE;
    }
}
