package io.github.eyunya11.createrealtimeschedule.network;

import java.util.List;
import java.util.Optional;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Opens the tag screen of the track tag block at {@code pos}, or brings it up to date if it is open,
 * after a change the player asked for.
 *
 * @param tag      the block's tag, or 0 for none
 * @param problems what is wrong with the block's section
 * @param message  how the player's last change went, if it was refused or did something to say
 */
public record TrackTagOpenPayload(BlockPos pos, int tag, List<Component> problems, Optional<Component> message)
    implements CustomPacketPayload {

    public static final Type<TrackTagOpenPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(Createrealtimeschedule.MODID, "track_tag_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TrackTagOpenPayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, TrackTagOpenPayload::pos,
        ByteBufCodecs.VAR_INT, TrackTagOpenPayload::tag,
        ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()), TrackTagOpenPayload::problems,
        ComponentSerialization.OPTIONAL_STREAM_CODEC, TrackTagOpenPayload::message,
        TrackTagOpenPayload::new);

    @Override
    public Type<TrackTagOpenPayload> type() {
        return TYPE;
    }
}
