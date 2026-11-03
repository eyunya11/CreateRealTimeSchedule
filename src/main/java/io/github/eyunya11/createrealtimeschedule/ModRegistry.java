package io.github.eyunya11.createrealtimeschedule;

import io.github.eyunya11.createrealtimeschedule.screen.MapScreenBlock;
import io.github.eyunya11.createrealtimeschedule.screen.MapScreenBlockEntity;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTagBlock;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTagBlockEntity;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTagItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
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

    /** The track tag block for both ways, which gives a stretch of track tags (see {@link TrackTagBlock}). */
    public static final DeferredBlock<TrackTagBlock> TRACK_TAG = BLOCKS.register("track_tag", () -> new TrackTagBlock(tagBlock(), false));

    /** The one-way track tag block, whose tags hold only for trains running the way it faces. */
    public static final DeferredBlock<TrackTagBlock> TRACK_TAG_ONE_WAY = BLOCKS.register("track_tag_one_way", () -> new TrackTagBlock(tagBlock(), true));

    public static final DeferredItem<TrackTagItem> TRACK_TAG_ITEM = ITEMS.register("track_tag",
        () -> new TrackTagItem(TRACK_TAG.get(), new Item.Properties()));

    public static final DeferredItem<TrackTagItem> TRACK_TAG_ONE_WAY_ITEM = ITEMS.register("track_tag_one_way",
        () -> new TrackTagItem(TRACK_TAG_ONE_WAY.get(), new Item.Properties()));

    /** The block entity of both kinds of track tag block. */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TrackTagBlockEntity>> TRACK_TAG_ENTITY =
        BLOCK_ENTITY_TYPES.register("track_tag", () -> BlockEntityType.Builder
            .of((pos, state) -> new TrackTagBlockEntity(ModRegistry.TRACK_TAG_ENTITY.get(), pos, state), TRACK_TAG.get(), TRACK_TAG_ONE_WAY.get())
            .build(null));

    /** The map screen, which shows a map of the track; blocks of it side by side make one screen (see {@link MapScreenBlock}). */
    public static final DeferredBlock<MapScreenBlock> MAP_SCREEN = BLOCKS.register("map_screen",
        () -> new MapScreenBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(1.5f).sound(SoundType.NETHERITE_BLOCK)
            .lightLevel(state -> 4).noOcclusion()));

    public static final DeferredItem<BlockItem> MAP_SCREEN_ITEM = ITEMS.registerSimpleBlockItem("map_screen", MAP_SCREEN);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MapScreenBlockEntity>> MAP_SCREEN_ENTITY =
        BLOCK_ENTITY_TYPES.register("map_screen", () -> BlockEntityType.Builder
            .of((pos, state) -> new MapScreenBlockEntity(ModRegistry.MAP_SCREEN_ENTITY.get(), pos, state), MAP_SCREEN.get())
            .build(null));

    private ModRegistry() {}

    private static BlockBehaviour.Properties tagBlock() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.PODZOL).strength(1.5f).sound(SoundType.NETHERITE_BLOCK);
    }

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITY_TYPES.register(modBus);
    }
}
