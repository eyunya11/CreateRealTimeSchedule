package io.github.eyunya11.createrealtimeschedule;

import io.github.eyunya11.createrealtimeschedule.tag.TrackTagBlock;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTagBlockEntity;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTagItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The mod's blocks, items and block entities. Their ids are saved in worlds, so they are never changed. */
public final class ModRegistry {

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Createrealtimeschedule.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Createrealtimeschedule.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Createrealtimeschedule.MODID);

    /** The track tag block, which gives a stretch of track a tag (see {@link TrackTagBlock}). */
    public static final DeferredBlock<TrackTagBlock> TRACK_TAG = BLOCKS.register("track_tag",
        () -> new TrackTagBlock(BlockBehaviour.Properties.of().mapColor(MapColor.PODZOL).strength(1.5f).sound(SoundType.NETHERITE_BLOCK)));

    public static final DeferredItem<TrackTagItem> TRACK_TAG_ITEM = ITEMS.register("track_tag",
        () -> new TrackTagItem(TRACK_TAG.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TrackTagBlockEntity>> TRACK_TAG_ENTITY =
        BLOCK_ENTITY_TYPES.register("track_tag", () -> BlockEntityType.Builder
            .of((pos, state) -> new TrackTagBlockEntity(ModRegistry.TRACK_TAG_ENTITY.get(), pos, state), TRACK_TAG.get())
            .build(null));

    private ModRegistry() {}

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITY_TYPES.register(modBus);
    }
}
