package io.github.eyunya11.createrealtimeschedule.network;

import java.util.List;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The world's track tags, which every client is sent when it joins and whenever the list changes: the
 * blocks show their tags' names, the tag screen lists them, and the map draws their colours.
 */
public record TrackTagListPayload(List<Entry> tags) implements CustomPacketPayload {

    /** A tag's id, name and colour, {@code #rrggbb}: the one chosen for it, or the one its name picks. */
    public record Entry(int id, String name, String colour) {
        static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Entry::id,
            ByteBufCodecs.STRING_UTF8, Entry::name, ByteBufCodecs.STRING_UTF8, Entry::colour, Entry::new);
    }

    public static final Type<TrackTagListPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(Createrealtimeschedule.MODID, "track_tag_list"));

    public static final StreamCodec<ByteBuf, TrackTagListPayload> STREAM_CODEC =
        Entry.STREAM_CODEC.apply(ByteBufCodecs.list()).map(TrackTagListPayload::new, TrackTagListPayload::tags);

    @Override
    public Type<TrackTagListPayload> type() {
        return TYPE;
    }
}
