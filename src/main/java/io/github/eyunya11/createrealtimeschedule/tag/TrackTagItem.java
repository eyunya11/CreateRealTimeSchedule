package io.github.eyunya11.createrealtimeschedule.tag;

import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.track.TrackTargetingBlockItem;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * The track tag block's item, which is used on the track first, as a station's is. The kind of point
 * is made in the common setup, after the items, so it is looked up when the item is used.
 */
public class TrackTagItem extends TrackTargetingBlockItem {

    public TrackTagItem(Block block, Properties properties) {
        super(block, properties, null);
    }

    @Override
    public EdgePointType<?> getType(ItemStack stack) {
        return TrackTagEdgePoint.type();
    }
}
