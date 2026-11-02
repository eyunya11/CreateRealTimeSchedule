package io.github.eyunya11.createrealtimeschedule.screen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import io.github.eyunya11.createrealtimeschedule.map.ScreenLayout.Place;
import io.github.eyunya11.createrealtimeschedule.map.ScreenSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A block of a map screen: where it is on its screen, the screen's size, and what the screen shows.
 * Every block of a screen holds the settings, so that each draws its own part and none has to be
 * found first; {@link ScreenGroup} keeps them the same. The client is sent all of it.
 */
public class MapScreenBlockEntity extends BlockEntity {

    private Place place = Place.ALONE;
    private ScreenSettings settings = ScreenSettings.DEFAULT;

    public MapScreenBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public Place place() {
        return place;
    }

    public ScreenSettings settings() {
        return settings;
    }

    /** Puts the block on its screen with the settings, and tells the clients if anything changed. */
    void set(Place place, ScreenSettings settings) {
        if (place.equals(this.place) && settings.equals(this.settings))
            return;
        this.place = place;
        this.settings = settings;
        setChanged();
        if (level != null)
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Column", place.column());
        tag.putInt("Row", place.row());
        tag.putInt("Width", place.width());
        tag.putInt("Height", place.height());
        tag.putDouble("X", settings.centreX());
        tag.putDouble("Z", settings.centreZ());
        tag.putDouble("Scale", settings.scale());
        tag.put("Lines", strings(settings.lines()));
        tag.put("Hidden", strings(new ArrayList<>(settings.hidden())));
        tag.put("TagsOff", strings(new ArrayList<>(settings.tagsOff())));
        tag.putLong("Stamp", settings.stamp());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (!tag.contains("Width"))
            return;
        place = new Place(tag.getInt("Column"), tag.getInt("Row"), Math.max(1, tag.getInt("Width")), Math.max(1, tag.getInt("Height")));
        settings = new ScreenSettings(tag.getDouble("X"), tag.getDouble("Z"), tag.getDouble("Scale"),
            strings(tag.getList("Lines", Tag.TAG_STRING)), new HashSet<>(strings(tag.getList("Hidden", Tag.TAG_STRING))),
            new HashSet<>(strings(tag.getList("TagsOff", Tag.TAG_STRING))), tag.getLong("Stamp"));
    }

    private static ListTag strings(List<String> values) {
        ListTag list = new ListTag();
        for (String value : values)
            list.add(StringTag.valueOf(value));
        return list;
    }

    private static List<String> strings(ListTag list) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < list.size(); i++)
            values.add(list.getString(i));
        return values;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
