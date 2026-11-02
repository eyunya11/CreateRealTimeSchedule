package io.github.eyunya11.createrealtimeschedule.tag;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;

/** The world's list of track tags, saved and read again. */
class TrackTagDataTest {

    @Test
    void theListIsReadAgainAsItWasSavedAndGoesOnFromItsNextId() {
        TrackTagData data = new TrackTagData();
        data.tags().create("Local");
        data.tags().create("Rapid");
        data.tags().create("Siding");
        data.tags().delete(3, false);
        data.tags().recolour(2, "#123456");
        CompoundTag saved = data.save(new CompoundTag(), null);

        TrackTagData read = TrackTagData.load(saved, null);
        assertEquals(Map.of(1, "Local", 2, "Rapid"), read.tags().names());
        assertEquals(4, read.tags().nextId(), "the id of the tag taken out is not given again after a restart either");
        assertEquals(Map.of(2, "#123456"), read.tags().colours(), "a colour chosen is kept; one not chosen is not");
    }
}
