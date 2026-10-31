package io.github.eyunya11.createrealtimeschedule.tag;

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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A track tag block: the place on the track it points at and faces, as a station's block does, and
 * the id of its tag, which it gives its point on the graph ({@link TrackTagEdgePoint}).
 */
public class TrackTagBlockEntity extends SmartBlockEntity implements TransformableBlockEntity {

    public TrackTargetingBehaviour<TrackTagEdgePoint> edgePoint;

    private int tag = TrackTags.NONE;

    public TrackTagBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        behaviours.add(edgePoint = new TrackTargetingBehaviour<>(this, TrackTagEdgePoint.type()));
    }

    /**
     * A block that pointed at no track did not count as having its tag ({@link TrackTagService#uses}),
     * so the tag may have been deleted meanwhile; it then has none. This runs before the block makes its
     * point on the graph.
     */
    @Override
    public void initialize() {
        super.initialize();
        if (level instanceof ServerLevel server && tag != TrackTags.NONE && TrackTagData.get(server.getServer()).tags().name(tag) == null) {
            tag = TrackTags.NONE;
            setChanged();
        }
    }

    /** The id of its tag, or {@link TrackTags#NONE}. */
    public int tag() {
        return tag;
    }

    /** Gives the block, and its point on the graph if it has one yet, the tag {@code tag}. */
    public void setTag(int tag) {
        if (this.tag == tag)
            return;
        this.tag = tag;
        TrackTagEdgePoint point = edgePoint.getEdgePoint();
        if (point != null) {
            point.setTag(tag);
            Create.RAILWAYS.markTracksDirty();
        }
        notifyUpdate();
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        compound.putInt("Tag", tag);
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        // Placing the item loads the block entity with only where it points, which leaves the tag as it is.
        if (compound.contains("Tag"))
            tag = compound.getInt("Tag");
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
