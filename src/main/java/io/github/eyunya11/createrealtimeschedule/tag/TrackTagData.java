package io.github.eyunya11.createrealtimeschedule.tag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** The world's list of track tags ({@link TrackTags}), held by the overworld's data storage. */
public final class TrackTagData extends SavedData {

    public static final String NAME = Createrealtimeschedule.MODID + "_track_tags";

    public static final SavedData.Factory<TrackTagData> FACTORY = new SavedData.Factory<>(TrackTagData::new, TrackTagData::load);

    private TrackTags tags = new TrackTags();

    public static TrackTagData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /**
     * The id a train held to the tag named {@code name} looks for on the track, by the list of the
     * server now running ({@link TrackTags#held}); with no server, {@link TrackTags#UNKNOWN} for a name.
     */
    public static OptionalInt held(String name) {
        return current().held(name);
    }

    /** The list of the server now running; an empty one with no server. */
    public static TrackTags current() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? new TrackTags() : get(server).tags();
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
            String colour = tags.colours().get(entry.getKey());
            if (colour != null)
                one.putString("Colour", colour);
            list.add(one);
        }
        tag.put("Tags", list);
        tag.putInt("NextId", tags.nextId());
        return tag;
    }

    public static TrackTagData load(CompoundTag tag, HolderLookup.Provider registries) {
        Map<Integer, String> names = new LinkedHashMap<>();
        Map<Integer, String> colours = new LinkedHashMap<>();
        for (Tag each : tag.getList("Tags", Tag.TAG_COMPOUND)) {
            CompoundTag one = (CompoundTag) each;
            names.put(one.getInt("Id"), one.getString("Name"));
            // A tag kept before tags had colours has none, and keeps the colour its name picks.
            if (one.contains("Colour", Tag.TAG_STRING))
                colours.put(one.getInt("Id"), one.getString("Colour"));
        }
        TrackTagData data = new TrackTagData();
        data.tags = new TrackTags(names, tag.getInt("NextId"), colours);
        return data;
    }
}
