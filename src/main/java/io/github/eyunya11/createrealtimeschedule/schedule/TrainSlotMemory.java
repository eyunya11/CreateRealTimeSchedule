package io.github.eyunya11.createrealtimeschedule.schedule;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.schedule.DeparturePlanner.SlotRecord;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The slot each train booked at its last {@code real_time_of_day} stop, kept by the train's id.
 *
 * <p>Create throws away a condition's own storage at every stop, so what has to outlive a stop
 * cannot live there. Held by the overworld's data storage, next to the clock.
 */
public final class TrainSlotMemory extends SavedData {

    public static final String NAME = Createrealtimeschedule.MODID + "_train_slots";

    public static final SavedData.Factory<TrainSlotMemory> FACTORY = new SavedData.Factory<>(TrainSlotMemory::new, TrainSlotMemory::load);

    private static final String TAG_TRAINS = "Trains";
    private static final String TAG_ID = "Id";
    private static final String TAG_SLOT = "Slot";
    private static final String TAG_STAMP = "Stamp";
    private static final String TAG_BASE = "Base";
    private static final String TAG_HAS_BASE = "HasBase";
    private static final String TAG_SIGNATURE = "Signature";

    private final Map<UUID, SlotRecord> records = new HashMap<>();

    public static TrainSlotMemory get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    @Nullable
    public SlotRecord get(UUID train) {
        return records.get(train);
    }

    public void put(UUID train, SlotRecord record) {
        if (!record.equals(records.put(train, record)))
            setDirty();
    }

    public void forget(UUID train) {
        if (records.remove(train) != null)
            setDirty();
    }

    public int size() {
        return records.size();
    }

    /** Forgets every train that is not in {@code alive}, so trains that were taken apart leave nothing behind. */
    public void retainOnly(Set<UUID> alive) {
        if (records.keySet().retainAll(alive))
            setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        records.forEach((train, record) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(TAG_ID, train);
            entry.putLong(TAG_SLOT, record.slot());
            entry.putLong(TAG_STAMP, record.stamp());
            entry.putLong(TAG_BASE, record.base());
            entry.putBoolean(TAG_HAS_BASE, record.hasBase());
            entry.putInt(TAG_SIGNATURE, record.signature());
            list.add(entry);
        });
        tag.put(TAG_TRAINS, list);
        return tag;
    }

    public static TrainSlotMemory load(CompoundTag tag, HolderLookup.Provider registries) {
        TrainSlotMemory memory = new TrainSlotMemory();
        for (Tag element : tag.getList(TAG_TRAINS, Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) element;
            if (!entry.hasUUID(TAG_ID))
                continue;
            memory.records.put(entry.getUUID(TAG_ID), new SlotRecord(entry.getLong(TAG_SLOT), entry.getLong(TAG_STAMP),
                entry.getLong(TAG_BASE), entry.getBoolean(TAG_HAS_BASE), entry.getInt(TAG_SIGNATURE)));
        }
        return memory;
    }
}
