package io.github.eyunya11.createrealtimeschedule.screen;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.jetbrains.annotations.Nullable;

import io.github.eyunya11.createrealtimeschedule.ModRegistry;
import io.github.eyunya11.createrealtimeschedule.ModSettings;
import io.github.eyunya11.createrealtimeschedule.map.ScreenLayout;
import io.github.eyunya11.createrealtimeschedule.map.ScreenLayout.Cell;
import io.github.eyunya11.createrealtimeschedule.map.ScreenLayout.Place;
import io.github.eyunya11.createrealtimeschedule.map.ScreenSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The blocks of a map screen, found from one of them: those that face the same way and touch it in
 * the wall, to the side or above and below, and those that touch them. When the group changes, every
 * block gets its place again ({@link ScreenLayout}) and the settings set last of the group's, so that
 * a block put beside a screen takes on what the screen shows.
 */
public final class ScreenGroup {

    private ScreenGroup() {}

    /** The way to the right, as one looks at a screen facing {@code facing}. */
    public static Direction right(Direction facing) {
        return facing.getCounterClockWise();
    }

    /**
     * The blocks of the group of the screen block at {@code pos}, by their cells; empty if there is none
     * there. A group too large to be a screen of at most {@code most} blocks each way is not looked
     * through to its end; with it come the whole screens its blocks found were of, which it ends, so
     * that none of their blocks is left with its place on one.
     */
    static Map<Cell, BlockPos> find(ServerLevel level, BlockPos pos, int most) {
        Map<Cell, BlockPos> cells = new HashMap<>();
        Direction facing = facing(level.getBlockState(pos));
        if (facing == null)
            return cells;
        Direction right = right(facing);
        ArrayDeque<BlockPos> next = new ArrayDeque<>();
        next.add(pos);
        cells.put(cell(pos, pos, right), pos);
        int limit = ScreenLayout.cellsToFind(most);
        while (!next.isEmpty() && cells.size() < limit) {
            BlockPos at = next.poll();
            for (BlockPos near : List.of(at.relative(right), at.relative(right.getOpposite()), at.above(), at.below())) {
                Cell cell = cell(pos, near, right);
                if (cells.containsKey(cell) || !level.isLoaded(near) || facing(level.getBlockState(near)) != facing)
                    continue;
                cells.put(cell, near);
                next.add(near);
            }
        }
        if (cells.size() >= limit) {
            Set<BlockPos> screens = new HashSet<>();
            for (BlockPos found : List.copyOf(cells.values())) {
                MapScreenBlockEntity entity = entity(level, found);
                if (entity == null || entity.place().equals(Place.ALONE))
                    continue;
                Place place = entity.place();
                BlockPos bottomLeft = found.relative(right, -place.column()).below(place.row());
                if (!screens.add(bottomLeft))
                    continue;
                for (int u = 0; u < place.width(); u++)
                    for (int v = 0; v < place.height(); v++) {
                        BlockPos block = bottomLeft.relative(right, u).above(v);
                        if (level.isLoaded(block) && facing(level.getBlockState(block)) == facing)
                            cells.putIfAbsent(cell(pos, block, right), block);
                    }
            }
        }
        return cells;
    }

    /** Where a block is in the wall, counted from {@code origin}: to the right, and up. */
    private static Cell cell(BlockPos origin, BlockPos pos, Direction right) {
        BlockPos offset = pos.subtract(origin);
        return new Cell(offset.getX() * right.getStepX() + offset.getZ() * right.getStepZ(), offset.getY());
    }

    private static @Nullable Direction facing(BlockState state) {
        return state.is(ModRegistry.MAP_SCREEN.get()) ? state.getValue(HorizontalDirectionalBlock.FACING) : null;
    }

    /** Puts the group of the block at {@code pos} together again, keeping the settings set last. */
    public static void regroup(ServerLevel level, BlockPos pos) {
        regroup(level, pos, ModSettings.mapScreenMaxSize());
    }

    /** {@link #regroup(ServerLevel, BlockPos)}, for screens of at most {@code most} blocks each way. */
    public static void regroup(ServerLevel level, BlockPos pos, int most) {
        apply(level, find(level, pos, most), most, settings -> settings);
    }

    /** Puts together again the groups of the blocks around {@code pos} that face {@code facing}, as when a block there is gone. */
    public static void regroupAround(ServerLevel level, BlockPos pos, Direction facing) {
        Direction right = right(facing);
        for (BlockPos near : List.of(pos.relative(right), pos.relative(right.getOpposite()), pos.above(), pos.below()))
            if (level.isLoaded(near) && facing(level.getBlockState(near)) == facing)
                regroup(level, near);
    }

    /**
     * Gives the group of the block at {@code pos} the settings {@code change} makes of the ones it has,
     * stamped now; false if there is no screen there.
     */
    public static boolean change(ServerLevel level, BlockPos pos, UnaryOperator<ScreenSettings> change) {
        int most = ModSettings.mapScreenMaxSize();
        Map<Cell, BlockPos> cells = find(level, pos, most);
        if (cells.isEmpty())
            return false;
        long now = level.getGameTime();
        apply(level, cells, most, settings -> {
            ScreenSettings changed = change.apply(settings);
            return new ScreenSettings(changed.centreX(), changed.centreZ(), changed.scale(), changed.lines(), changed.hidden(),
                changed.tagsOff(), now);
        });
        return true;
    }

    private static void apply(ServerLevel level, Map<Cell, BlockPos> cells, int most, UnaryOperator<ScreenSettings> change) {
        if (cells.isEmpty())
            return;
        Map<Cell, Place> places = ScreenLayout.of(cells.keySet(), most);
        // The settings set last; of those set together (or never), the ones of the block at the bottom left.
        ScreenSettings settings = cells.entrySet().stream()
            .map(entry -> Map.entry(entry.getKey(), entity(level, entry.getValue())))
            .filter(entry -> entry.getValue() != null)
            .max(Comparator.<Map.Entry<Cell, MapScreenBlockEntity>>comparingLong(entry -> entry.getValue().settings().stamp())
                .thenComparing(entry -> -entry.getKey().v()).thenComparing(entry -> -entry.getKey().u()))
            .map(entry -> entry.getValue().settings()).orElse(ScreenSettings.DEFAULT);
        ScreenSettings changed = change.apply(settings);
        for (Map.Entry<Cell, BlockPos> entry : cells.entrySet()) {
            MapScreenBlockEntity entity = entity(level, entry.getValue());
            if (entity != null)
                entity.set(places.get(entry.getKey()), changed);
        }
    }

    private static @Nullable MapScreenBlockEntity entity(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof MapScreenBlockEntity entity ? entity : null;
    }
}
