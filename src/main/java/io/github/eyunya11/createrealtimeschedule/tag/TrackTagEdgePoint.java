package io.github.eyunya11.createrealtimeschedule.tag;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.content.trains.graph.DimensionPalette;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.signal.SingleBlockEntityEdgePoint;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * A track tag block's point on Create's track graph, which holds the block's tags and its kind so that
 * the track can be read with the blocks' chunks unloaded. As for a station, its {@code edgeLocation} runs from the
 * node behind it to the node it faces: its section runs on towards the second.
 */
public class TrackTagEdgePoint extends SingleBlockEntityEdgePoint {

    /** The kind of point it is. Saved with Create's graphs, so it is never changed. */
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Createrealtimeschedule.MODID, "track_tag");

    private static EdgePointType<TrackTagEdgePoint> type;

    private List<Integer> tags = List.of();
    private boolean oneWay;

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

    /** The ids of its tags, in order; none for a block that only ends a section. */
    public List<Integer> tags() {
        return tags;
    }

    void setTags(List<Integer> tags) {
        this.tags = List.copyOf(tags);
    }

    /** Whether its block is a one-way one, whose tags hold only for trains running the way it faces. */
    public boolean oneWay() {
        return oneWay;
    }

    @Override
    public void blockEntityAdded(BlockEntity blockEntity, boolean front) {
        super.blockEntityAdded(blockEntity, front);
        if (blockEntity instanceof TrackTagBlockEntity tagEntity) {
            tags = tagEntity.tags();
            oneWay = tagEntity.oneWay();
        }
    }

    @Override
    public void read(CompoundTag nbt, HolderLookup.Provider registries, boolean migration, DimensionPalette dimensions) {
        super.read(nbt, registries, migration, dimensions);
        tags = TrackTags.ids(nbt.getIntArray("Tags"));
        oneWay = nbt.getBoolean("OneWay");
    }

    @Override
    public void write(CompoundTag nbt, HolderLookup.Provider registries, DimensionPalette dimensions) {
        super.write(nbt, registries, dimensions);
        nbt.put("Tags", new IntArrayTag(tags.stream().mapToInt(Integer::intValue).toArray()));
        nbt.putBoolean("OneWay", oneWay);
    }

    @Override
    public void read(FriendlyByteBuf buffer, DimensionPalette dimensions) {
        super.read(buffer, dimensions);
        int count = buffer.readVarInt();
        List<Integer> read = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            read.add(buffer.readVarInt());
        tags = List.copyOf(read);
        oneWay = buffer.readBoolean();
    }

    @Override
    public void write(FriendlyByteBuf buffer, DimensionPalette dimensions) {
        super.write(buffer, dimensions);
        buffer.writeVarInt(tags.size());
        for (int id : tags)
            buffer.writeVarInt(id);
        buffer.writeBoolean(oneWay);
    }
}
