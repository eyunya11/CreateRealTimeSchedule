package io.github.eyunya11.createrealtimeschedule.tag;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.stream.IntStream;

import org.jetbrains.annotations.Nullable;

import io.github.eyunya11.createrealtimeschedule.timetable.TypeColours;

/**
 * The world's list of track tags: an id for each, which the blocks hold, and its name, which only the
 * list holds. Renaming a tag so renames it on every block; a timetable names its tags, so it has to be
 * changed by hand. An id is never given twice, so that a block whose tag was taken out does not get
 * another one; a tag can only be taken out while no block has it. A tag may have a colour chosen for
 * it, which the map draws its sections in; one without has the colour its name picks, as a train's
 * type does. Minecraft-free, so that it can be
 * tested on its own; {@link TrackTagData} keeps it in the world.
 */
public final class TrackTags {

    /** The id a block with no tag holds. */
    public static final int NONE = 0;

    /** The id a train is held to for a tag the list does not have: one no block holds. */
    public static final int UNKNOWN = -1;

    /** The longest name a tag can have. */
    public static final int MAX_NAME_LENGTH = 32;

    /** Why the list was not changed. */
    public enum Refusal {
        BLANK, TOO_LONG, TAKEN, UNKNOWN, IN_USE,
        /** The name has a control character, such as a line break, which a timetable's text cannot have. */
        CONTROL,
        /** The colour is not one of the form {@code #rrggbb}. */
        COLOUR
    }

    /** What {@link #create} did: the new tag's id, or why there is none. */
    public record Created(int id, @Nullable Refusal refusal) {
    }

    private final TreeMap<Integer, String> names = new TreeMap<>();
    private final TreeMap<Integer, String> colours = new TreeMap<>();
    private int nextId = 1;

    public TrackTags() {
    }

    /**
     * The list as it was kept.
     *
     * @param nextId the id the next tag gets; raised past the ids of {@code names} if it is not
     */
    public TrackTags(Map<Integer, String> names, int nextId) {
        this(names, nextId, Map.of());
    }

    /**
     * The list as it was kept, with the colours chosen for its tags; a colour of a tag the list does not
     * have, or one not of the form {@code #rrggbb}, is left out.
     */
    public TrackTags(Map<Integer, String> names, int nextId, Map<Integer, String> colours) {
        for (Map.Entry<Integer, String> entry : names.entrySet())
            if (entry.getKey() > NONE)
                this.names.put(entry.getKey(), entry.getValue());
        this.nextId = Math.max(nextId, this.names.isEmpty() ? 1 : this.names.lastKey() + 1);
        for (Map.Entry<Integer, String> entry : colours.entrySet())
            if (this.names.containsKey(entry.getKey()) && TypeColours.isColour(entry.getValue()))
                this.colours.put(entry.getKey(), entry.getValue());
    }

    /** The names of the tags by their ids, in the order the ids were given. */
    public Map<Integer, String> names() {
        return Collections.unmodifiableMap(names);
    }

    /** The ids {@code ids} as a block holds them: in order, once each, without {@link #NONE}. */
    public static List<Integer> ids(int... ids) {
        return IntStream.of(ids).filter(id -> id > NONE).sorted().distinct().boxed().toList();
    }

    /** The colours chosen for tags, by their ids; a tag not here has the colour its name picks ({@link #colour}). */
    public Map<Integer, String> colours() {
        return Collections.unmodifiableMap(colours);
    }

    /**
     * The colour tag {@code id} is drawn in, {@code #rrggbb}: the one chosen for it, or else the one its
     * name picks ({@link TypeColours#byName}); null if there is no such tag.
     */
    public @Nullable String colour(int id) {
        String name = names.get(id);
        if (name == null)
            return null;
        String chosen = colours.get(id);
        return chosen != null ? chosen : TypeColours.byName(name);
    }

    /**
     * Chooses {@code colour}, {@code #rrggbb}, for tag {@code id}; an empty one gives it back the colour
     * its name picks. Null if it did, or why it did not.
     */
    public @Nullable Refusal recolour(int id, String colour) {
        if (!names.containsKey(id))
            return Refusal.UNKNOWN;
        if (colour.isEmpty()) {
            colours.remove(id);
            return null;
        }
        if (!TypeColours.isColour(colour))
            return Refusal.COLOUR;
        colours.put(id, colour.toLowerCase(Locale.ROOT));
        return null;
    }

    public int nextId() {
        return nextId;
    }

    /** The name of tag {@code id}, or null if there is no such tag. */
    public @Nullable String name(int id) {
        return names.get(id);
    }

    /**
     * The id a train held to the tag named {@code name} looks for on the track (see
     * {@code RouteSearch}): empty for no name, which holds a train to no tag; for a name the list does
     * not have, {@link #UNKNOWN}, which no section has.
     */
    public OptionalInt held(String name) {
        if (name.isEmpty())
            return OptionalInt.empty();
        for (Map.Entry<Integer, String> entry : names.entrySet())
            if (entry.getValue().equals(name))
                return OptionalInt.of(entry.getKey());
        return OptionalInt.of(UNKNOWN);
    }

    /** A new tag named {@code name}, its spaces at either end left off. */
    public Created create(String name) {
        String clean = name.strip();
        Refusal refusal = check(clean, NONE);
        if (refusal != null)
            return new Created(NONE, refusal);
        int id = nextId++;
        names.put(id, clean);
        return new Created(id, null);
    }

    /** Names tag {@code id} {@code name}; null if it did, or why it did not. */
    public @Nullable Refusal rename(int id, String name) {
        if (!names.containsKey(id))
            return Refusal.UNKNOWN;
        String clean = name.strip();
        Refusal refusal = check(clean, id);
        if (refusal != null)
            return refusal;
        names.put(id, clean);
        return null;
    }

    /** Takes tag {@code id} out of the list, unless a block has it ({@code used}); null if it did, or why it did not. */
    public @Nullable Refusal delete(int id, boolean used) {
        if (!names.containsKey(id))
            return Refusal.UNKNOWN;
        if (used)
            return Refusal.IN_USE;
        names.remove(id);
        colours.remove(id);
        return null;
    }

    /** Why {@code name} cannot be the name of tag {@code id} (or of a new tag, for {@link #NONE}), or null if it can. */
    private @Nullable Refusal check(String name, int id) {
        if (name.isEmpty())
            return Refusal.BLANK;
        if (name.length() > MAX_NAME_LENGTH)
            return Refusal.TOO_LONG;
        if (name.codePoints().anyMatch(Character::isISOControl))
            return Refusal.CONTROL;
        for (Map.Entry<Integer, String> entry : names.entrySet())
            if (entry.getKey() != id && entry.getValue().equals(name))
                return Refusal.TAKEN;
        return null;
    }
}
