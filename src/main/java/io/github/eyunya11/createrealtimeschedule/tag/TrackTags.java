package io.github.eyunya11.createrealtimeschedule.tag;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;

import org.jetbrains.annotations.Nullable;

/**
 * The world's list of track tags: an id for each, which the blocks hold, and its name, which only the
 * list holds. Renaming a tag so renames it on every block; a timetable names its tags, so it has to be
 * changed by hand. An id is never given twice, so that a block whose tag was taken out does not get
 * another one; a tag can only be taken out while no block has it. Minecraft-free, so that it can be
 * tested on its own; {@link TrackTagData} keeps it in the world.
 */
public final class TrackTags {

    /** The id a block with no tag holds. */
    public static final int NONE = 0;

    /** The longest name a tag can have. */
    public static final int MAX_NAME_LENGTH = 32;

    /** Why the list was not changed. */
    public enum Refusal {
        BLANK, TOO_LONG, TAKEN, UNKNOWN, IN_USE
    }

    /** What {@link #create} did: the new tag's id, or why there is none. */
    public record Created(int id, @Nullable Refusal refusal) {
    }

    private final TreeMap<Integer, String> names = new TreeMap<>();
    private int nextId = 1;

    public TrackTags() {
    }

    /**
     * The list as it was kept.
     *
     * @param nextId the id the next tag gets; raised past the ids of {@code names} if it is not
     */
    public TrackTags(Map<Integer, String> names, int nextId) {
        for (Map.Entry<Integer, String> entry : names.entrySet())
            if (entry.getKey() > NONE)
                this.names.put(entry.getKey(), entry.getValue());
        this.nextId = Math.max(nextId, this.names.isEmpty() ? 1 : this.names.lastKey() + 1);
    }

    /** The names of the tags by their ids, in the order the ids were given. */
    public Map<Integer, String> names() {
        return Collections.unmodifiableMap(names);
    }

    /** The ids {@code ids} as a block holds them: in order, once each, without {@link #NONE}. */
    public static List<Integer> ids(int... ids) {
        return IntStream.of(ids).filter(id -> id > NONE).sorted().distinct().boxed().toList();
    }

    public int nextId() {
        return nextId;
    }

    /** The name of tag {@code id}, or null if there is no such tag. */
    public @Nullable String name(int id) {
        return names.get(id);
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
        return null;
    }

    /** Why {@code name} cannot be the name of tag {@code id} (or of a new tag, for {@link #NONE}), or null if it can. */
    private @Nullable Refusal check(String name, int id) {
        if (name.isEmpty())
            return Refusal.BLANK;
        if (name.length() > MAX_NAME_LENGTH)
            return Refusal.TOO_LONG;
        for (Map.Entry<Integer, String> entry : names.entrySet())
            if (entry.getKey() != id && entry.getValue().equals(name))
                return Refusal.TAKEN;
        return null;
    }
}
