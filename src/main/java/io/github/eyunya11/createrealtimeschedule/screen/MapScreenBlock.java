package io.github.eyunya11.createrealtimeschedule.screen;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import io.github.eyunya11.createrealtimeschedule.ModRegistry;

/**
 * The map screen: a panel against a wall that shows a map of the track, facing the one who placed it.
 * Blocks of it side by side, facing the same way, make one screen ({@link ScreenGroup}). Used, it opens
 * the screen's settings: what part of the world it shows, the lines whose track it shows, and what it
 * shows on it.
 */
public class MapScreenBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public static final MapCodec<MapScreenBlock> CODEC = simpleCodec(MapScreenBlock::new);

    /** How thick the panel is, in sixteenths of a block. */
    public static final int THICKNESS = 2;

    private static final VoxelShape NORTH = Block.box(0, 0, 16 - THICKNESS, 16, 16, 16);
    private static final VoxelShape SOUTH = Block.box(0, 0, 0, 16, 16, THICKNESS);
    private static final VoxelShape WEST = Block.box(16 - THICKNESS, 0, 0, 16, 16, 16);
    private static final VoxelShape EAST = Block.box(0, 0, 0, THICKNESS, 16, 16);

    public MapScreenBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    /** The panel lies against the block behind it, the way the screen faces away from. */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case EAST -> EAST;
            default -> NORTH;
        };
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MapScreenBlockEntity(ModRegistry.MAP_SCREEN_ENTITY.get(), pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        // One who may not build here (in adventure mode, or by spawn) may not set the screen either.
        if (!player.mayBuild())
            return InteractionResult.PASS;
        if (player instanceof ServerPlayer serverPlayer && MapScreenService.mayChange(serverPlayer, pos))
            MapScreenService.open(serverPlayer, pos);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        // The block's entity comes after this: the group is put together on the next tick, with it.
        if (!level.isClientSide && !oldState.is(this))
            level.scheduleTick(pos, this, 1);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        ScreenGroup.regroup(level, pos);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        boolean gone = !newState.is(this);
        super.onRemove(state, level, pos, newState, movedByPiston);
        // The blocks that were of its screen make screens again without it; one turned round, with it.
        if (level instanceof ServerLevel serverLevel)
            if (gone)
                ScreenGroup.regroupAround(serverLevel, pos, state.getValue(FACING));
            else if (newState.getValue(FACING) != state.getValue(FACING)) {
                ScreenGroup.regroupAround(serverLevel, pos, state.getValue(FACING));
                ScreenGroup.regroup(serverLevel, pos);
            }
    }
}
