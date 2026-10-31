package io.github.eyunya11.createrealtimeschedule.tag;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** The world's list of track tags ({@link TrackTags}), held by the overworld's data storage. */
public final class TrackTagData extends SavedData {

    public static final String NAME = Createrealtimeschedule.MODID + "_track_tags";

    public static final SavedData.Factory<TrackTagData> FACTORY = new SavedData.Factory<>(TrackTagData::new, TrackTagData::load);

    private TrackTags tags = new TrackTags();

    public static TrackTagData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /** The list; one who changes it calls {@link #setDirty()}. */
    public TrackTags tags() {
        return tags;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<Integer, String> entry : tags.names().entrySet()) {
            CompoundTag one = new CompoundTag();
            one.putInt("Id", entry.getKey());
            one.putString("Name", entry.getValue());
            list.add(one);
        }
        tag.put("Tags", list);
        tag.putInt("NextId", tags.nextId());
        return tag;
    }

    public static TrackTagData load(CompoundTag tag, HolderLookup.Provider registries) {
        Map<Integer, String> names = new LinkedHashMap<>();
        for (Tag each : tag.getList("Tags", Tag.TAG_COMPOUND)) {
            CompoundTag one = (CompoundTag) each;
            names.put(one.getInt("Id"), one.getString("Name"));
        }
        TrackTagData data = new TrackTagData();
        data.tags = new TrackTags(names, tag.getInt("NextId"));
        return data;
    }
}
