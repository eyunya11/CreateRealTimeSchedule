package io.github.eyunya11.createrealtimeschedule.tag;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.Create;
import com.simibubi.create.api.contraption.transformable.TransformableBlockEntity;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A track tag block: the place on the track it points at and faces, as a station's block does, and
 * the ids of its tags, which it gives its point on the graph ({@link TrackTagEdgePoint}). Its block
 * says which kind it is, for both ways or one way.
 */
public class TrackTagBlockEntity extends SmartBlockEntity implements TransformableBlockEntity {

    public TrackTargetingBehaviour<TrackTagEdgePoint> edgePoint;

    private List<Integer> tags = List.of();

    public TrackTagBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        behaviours.add(edgePoint = new TrackTargetingBehaviour<>(this, TrackTagEdgePoint.type()));
    }

    /**
     * A block that pointed at no track did not count as having its tags ({@link TrackTagService#uses}),
     * so a tag may have been deleted meanwhile; it then loses it. This runs before the block makes its
     * point on the graph.
     */
    @Override
    public void initialize() {
        super.initialize();
        if (!(level instanceof ServerLevel server))
            return;
        TrackTags list = TrackTagData.get(server.getServer()).tags();
        List<Integer> kept = tags.stream().filter(id -> list.name(id) != null).toList();
        if (kept.size() != tags.size()) {
            tags = kept;
            setChanged();
        }
    }

    /** The ids of its tags, in order; none for a block that only ends a section. */
    public List<Integer> tags() {
        return tags;
    }

    /** Whether it is a one-way block, whose tags hold only for trains running the way it faces. */
    public boolean oneWay() {
        return getBlockState().getBlock() instanceof TrackTagBlock block && block.oneWay();
    }

    /** Gives the block, and its point on the graph if it has one yet, the tags {@code ids}. */
    public void setTags(int... ids) {
        List<Integer> tags = TrackTags.ids(ids);
        if (this.tags.equals(tags))
            return;
        this.tags = tags;
        TrackTagEdgePoint point = edgePoint.getEdgePoint();
        if (point != null) {
            point.setTags(tags);
            Create.RAILWAYS.markTracksDirty();
        }
        notifyUpdate();
    }

    /** Takes the tag {@code id} off the block if it has it, or gives it the block if not. */
    public void toggle(int id) {
        List<Integer> changed = new ArrayList<>(tags);
        if (!changed.remove(Integer.valueOf(id)))
            changed.add(id);
        setTags(changed.stream().mapToInt(Integer::intValue).toArray());
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        compound.put("Tags", new IntArrayTag(tags.stream().mapToInt(Integer::intValue).toArray()));
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        // Placing the item loads the block entity with only where it points, which leaves the tags as they are.
        if (compound.contains("Tags"))
            tags = TrackTags.ids(compound.getIntArray("Tags"));
    }

    @Override
    protected AABB createRenderBoundingBox() {
        return new AABB(Vec3.atLowerCornerOf(worldPosition), Vec3.atLowerCornerOf(edgePoint.getGlobalPosition())).inflate(2);
    }

    @Override
    public void transform(BlockEntity blockEntity, StructureTransform transform) {
        edgePoint.transform(blockEntity, transform);
    }
}
