package io.github.eyunya11.createrealtimeschedule.tag;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;

import io.github.eyunya11.createrealtimeschedule.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The track tag block, of one of two kinds: for both ways, whose tags hold for trains running either
 * way along its section, or one way, whose tags hold only for those running the way it faces. It is
 * placed as a station is: the item is used on the track first, which picks the place and the way it
 * faces. Used, the block opens the screen to choose its tags.
 */
public class TrackTagBlock extends Block implements IBE<TrackTagBlockEntity>, IWrenchable {

    private final boolean oneWay;

    public TrackTagBlock(Properties properties, boolean oneWay) {
        super(properties);
        this.oneWay = oneWay;
    }

    /** Whether it is the one-way kind. */
    public boolean oneWay() {
        return oneWay;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer)
            TrackTagService.open(serverPlayer, pos, null);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public Class<TrackTagBlockEntity> getBlockEntityClass() {
        return TrackTagBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends TrackTagBlockEntity> getBlockEntityType() {
        return ModRegistry.TRACK_TAG_ENTITY.get();
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        IBE.onRemove(state, level, pos, newState);
    }
}
