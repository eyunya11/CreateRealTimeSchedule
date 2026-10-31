package io.github.eyunya11.createrealtimeschedule.tag;

import com.simibubi.create.content.trains.graph.DimensionPalette;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.signal.SingleBlockEntityEdgePoint;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * A track tag block's point on Create's track graph, which holds the block's tag so that the track can
 * be read with the blocks' chunks unloaded. As for a station, its {@code edgeLocation} runs from the
 * node behind it to the node it faces: its section runs on towards the second.
 */
public class TrackTagEdgePoint extends SingleBlockEntityEdgePoint {

    /** The kind of point it is. Saved with Create's graphs, so it is never changed. */
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Createrealtimeschedule.MODID, "track_tag");

    private static EdgePointType<TrackTagEdgePoint> type;

    private int tag = TrackTags.NONE;

    /**
     * Adds the kind to Create's. On both sides, as a client reads the graphs Create sends it by the
     * kinds' ids; before any world is loaded, as Create reads the points of a saved graph the same way.
     */
    static void register() {
        type = EdgePointType.register(ID, TrackTagEdgePoint::new);
    }

    /** The kind of point, once {@link #register} has run in the common setup. */
    public static EdgePointType<TrackTagEdgePoint> type() {
        return type;
    }

    /** The id of its tag, or {@link TrackTags#NONE}. */
    public int tag() {
        return tag;
    }

    void setTag(int tag) {
        this.tag = tag;
    }

    @Override
    public void blockEntityAdded(BlockEntity blockEntity, boolean front) {
        super.blockEntityAdded(blockEntity, front);
        if (blockEntity instanceof TrackTagBlockEntity tagEntity)
            tag = tagEntity.tag();
    }

    @Override
    public void read(CompoundTag nbt, HolderLookup.Provider registries, boolean migration, DimensionPalette dimensions) {
        super.read(nbt, registries, migration, dimensions);
        tag = nbt.getInt("Tag");
    }

    @Override
    public void write(CompoundTag nbt, HolderLookup.Provider registries, DimensionPalette dimensions) {
        super.write(nbt, registries, dimensions);
        nbt.putInt("Tag", tag);
    }

    @Override
    public void read(FriendlyByteBuf buffer, DimensionPalette dimensions) {
        super.read(buffer, dimensions);
        tag = buffer.readVarInt();
    }

    @Override
    public void write(FriendlyByteBuf buffer, DimensionPalette dimensions) {
        super.write(buffer, dimensions);
        buffer.writeVarInt(tag);
    }
}
