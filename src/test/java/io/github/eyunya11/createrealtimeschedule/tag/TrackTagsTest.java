package io.github.eyunya11.createrealtimeschedule.tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.tag.TrackTags.Refusal;

/** The world's list of track tags. */
class TrackTagsTest {

    @Test
    void tagsAreMadeWithIdsThatAreNeverGivenTwice() {
        TrackTags tags = new TrackTags();
        assertEquals(new TrackTags.Created(1, null), tags.create(" Local "));
        assertEquals(new TrackTags.Created(2, null), tags.create("Rapid"));
        assertEquals("Local", tags.name(1), "spaces at either end are left off");
        assertNull(tags.delete(2, false));
        assertEquals(new TrackTags.Created(3, null), tags.create("Siding"), "the id of the tag taken out is not given again");
        assertEquals(Map.of(1, "Local", 3, "Siding"), tags.names());
    }

    @Test
    void aNameMustBeSomethingNotTooLongAndNotAnotherTagsName() {
        TrackTags tags = new TrackTags();
        tags.create("Local");
        assertEquals(Refusal.BLANK, tags.create("  ").refusal());
        assertEquals(Refusal.TOO_LONG, tags.create("x".repeat(TrackTags.MAX_NAME_LENGTH + 1)).refusal());
        assertEquals(Refusal.TAKEN, tags.create("Local").refusal());
        assertEquals(new TrackTags.Created(2, null), tags.create("x".repeat(TrackTags.MAX_NAME_LENGTH)));
    }

    @Test
    void aTagIsRenamedUnlessTheNameIsTaken() {
        TrackTags tags = new TrackTags();
        tags.create("Local");
        tags.create("Rapid");
        assertNull(tags.rename(1, "Local"), "its own name is not taken");
        assertEquals(Refusal.TAKEN, tags.rename(1, "Rapid"));
        assertNull(tags.rename(1, "Slow"));
        assertEquals("Slow", tags.name(1));
        assertEquals(Refusal.UNKNOWN, tags.rename(7, "Other"));
    }

    @Test
    void aTagABlockHasIsNotTakenOut() {
        TrackTags tags = new TrackTags();
        tags.create("Local");
        assertEquals(Refusal.IN_USE, tags.delete(1, true));
        assertEquals("Local", tags.name(1));
        assertEquals(Refusal.UNKNOWN, tags.delete(TrackTags.NONE, false));
    }

    @Test
    void aKeptListGoesOnFromItsNextId() {
        TrackTags kept = new TrackTags(Map.of(1, "Local", 4, "Rapid"), 2);
        assertEquals(5, kept.nextId(), "past the ids it has, whatever was kept");
        assertEquals(new TrackTags.Created(5, null), kept.create("Siding"));
    }
}
