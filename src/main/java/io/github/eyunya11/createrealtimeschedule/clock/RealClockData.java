package io.github.eyunya11.createrealtimeschedule.clock;

import org.jetbrains.annotations.Nullable;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * What the clock keeps in the world: the offset, and the one thing the sky sync has to remember.
 *
 * <p>Held by the overworld's data storage, so there is one clock per world. Nothing else about the
 * clock is saved, because everything else follows from the game time.
 */
public final class RealClockData extends SavedData {

    public static final String NAME = Createrealtimeschedule.MODID + "_clock";

    public static final SavedData.Factory<RealClockData> FACTORY = new SavedData.Factory<>(RealClockData::new, RealClockData::load);

    private static final String TAG_OFFSET = "Offset";
    private static final String TAG_INITIALIZED = "Initialized";
    private static final String TAG_SKY_RULE = "SkyPreviousRule";

    private long offset;
    private boolean initialized;
    /** The value of doDaylightCycle from before the sky sync switched it off; null while nothing is remembered. */
    @Nullable
    private Boolean skyPreviousRule;

    /** Offset added to the game time to get the clock value. */
    public long offset() {
        return offset;
    }

    /** False for a clock that has just been created and has not been given a start time yet. */
    public boolean isInitialized() {
        return initialized;
    }

    public void setOffset(long offset) {
        if (initialized && this.offset == offset)
            return;
        this.offset = offset;
        this.initialized = true;
        setDirty();
    }

    @Nullable
    public Boolean skyPreviousRule() {
        return skyPreviousRule;
    }

    public void rememberSkyRule(boolean value) {
        skyPreviousRule = value;
        setDirty();
    }

    public void forgetSkyRule() {
        if (skyPreviousRule == null)
            return;
        skyPreviousRule = null;
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong(TAG_OFFSET, offset);
        tag.putBoolean(TAG_INITIALIZED, initialized);
        if (skyPreviousRule != null)
            tag.putBoolean(TAG_SKY_RULE, skyPreviousRule);
        return tag;
    }

    public static RealClockData load(CompoundTag tag, HolderLookup.Provider registries) {
        RealClockData data = new RealClockData();
        data.offset = tag.getLong(TAG_OFFSET);
        data.initialized = tag.getBoolean(TAG_INITIALIZED);
        data.skyPreviousRule = tag.contains(TAG_SKY_RULE) ? tag.getBoolean(TAG_SKY_RULE) : null;
        return data;
    }
}
