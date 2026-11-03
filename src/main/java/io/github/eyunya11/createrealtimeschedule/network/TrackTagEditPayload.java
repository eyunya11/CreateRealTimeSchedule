package io.github.eyunya11.createrealtimeschedule.network;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTags;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.codec.NeoForgeStreamCodecs;

/**
 * A change a player asks for on the tag screen of the block at {@code pos}. The server answers with a
 * {@link TrackTagOpenPayload}.
 *
 * @param tag  the tag to give the block or take off it, rename or delete
 * @param name the name of a tag to make, or the new name of one; for a colour, the colour
 */
public record TrackTagEditPayload(BlockPos pos, Action action, int tag, String name) implements CustomPacketPayload {

    public enum Action {
        /** Gives the block {@code tag}, or takes it off the block if it has it. */
        TOGGLE,
        /** Takes all the block's tags off it. */
        CLEAR,
        /** Makes a tag named {@code name} and gives the block it. */
        CREATE,
        /** Names {@code tag} {@code name}. */
        RENAME,
        /** Takes {@code tag} out of the list. */
        DELETE,
        /** Chooses the colour {@code name}, {@code #rrggbb}, for {@code tag}; an empty one gives it back the colour its name picks. */
        COLOUR
    }

    public static final Type<TrackTagEditPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(Createrealtimeschedule.MODID, "track_tag_edit"));

    /** Room for a name a little too long, which the server refuses with a message rather than dropping the player. */
    private static final int MAX_NAME_LENGTH = 4 * TrackTags.MAX_NAME_LENGTH;

    public static final StreamCodec<FriendlyByteBuf, TrackTagEditPayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, TrackTagEditPayload::pos,
        NeoForgeStreamCodecs.enumCodec(Action.class), TrackTagEditPayload::action,
        ByteBufCodecs.VAR_INT, TrackTagEditPayload::tag,
        ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH), TrackTagEditPayload::name,
        TrackTagEditPayload::new);

    @Override
    public Type<TrackTagEditPayload> type() {
        return TYPE;
    }
}
