package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Duty;
import io.github.eyunya11.createrealtimeschedule.timetable.TimetableJson;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * What the operation keeps: the timetable it runs, the last service day each duty was dealt with, the
 * duties running and the trains that run them, since when each free train has waited at its depot,
 * and a short record of duties that did not run or were given up. Held by the overworld's data
 * storage, next to the clock.
 *
 * <p>The timetable is kept as the JSON it was loaded from and read again when the world is, so that it
 * is the same text that was checked. A running duty is kept as its {@link DutyRun.State}, and made
 * again from the timetable's duty; one that no longer fits the timetable is given up.
 */
public final class OperationData extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String NAME = Createrealtimeschedule.MODID + "_operation";

    public static final SavedData.Factory<OperationData> FACTORY = new SavedData.Factory<>(OperationData::new, OperationData::load);

    /** How many notes are kept; the oldest go first. */
    public static final int MAX_NOTES = 50;

    /**
     * Something that went wrong with a duty, for {@code /rttimetable status}.
     *
     * @param clock when
     * @param duty  the duty's id
     * @param day   the service day
     * @param what  what happened, in words
     */
    public record Note(long clock, String duty, long day, String what) {}

    private static final String TAG_TIMETABLE = "Timetable";
    private static final String TAG_LAST_DAYS = "LastDays";
    private static final String TAG_RUNS = "Runs";
    private static final String TAG_FREE = "Free";
    private static final String TAG_NOTES = "Notes";
    private static final String TAG_DUTY = "Duty";
    private static final String TAG_DAY = "Day";
    private static final String TAG_TRAIN = "Train";
    private static final String TAG_CALL = "Call";
    private static final String TAG_PHASE = "Phase";
    private static final String TAG_DEPARTURE = "Departure";
    private static final String TAG_ARRIVALS = "Arrivals";
    private static final String TAG_DEPARTURES = "Departures";
    private static final String TAG_SINCE = "Since";
    private static final String TAG_CLOCK = "Clock";
    private static final String TAG_WHAT = "What";

    private String json = "";
    private @Nullable Timetable timetable;
    private final Map<String, List<Call>> calls = new HashMap<>();
    private final Map<String, Long> lastDays = new HashMap<>();
    private final Map<UUID, DutyRun> runs = new LinkedHashMap<>();
    private final Map<UUID, Long> freeSince = new HashMap<>();
    private final Deque<Note> notes = new ArrayDeque<>();

    public static OperationData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    // ------------------------------------------------------------------ the timetable

    public @Nullable Timetable timetable() {
        return timetable;
    }

    /** The calls of the timetable's duty {@code id}, or null if it has none of that id. */
    public @Nullable List<Call> calls(String id) {
        return calls.get(id);
    }

    /**
     * Runs {@code timetable} from now on, which {@code json} is the text of. A duty's starts that have
     * gone by at {@code now} are not looked at: loading a timetable at noon does not make the morning's
     * duties count as missed. A running duty goes on if the new timetable has it with the same calls,
     * and is given up if not.
     */
    public void loadTimetable(String json, Timetable timetable, long now) {
        this.json = json;
        use(timetable);
        for (Duty duty : timetable.duties()) {
            long started = DutyCalendar.lastDayStartedBy(now, DutyPlan.firstDeparture(calls.get(duty.id())));
            lastDays.merge(duty.id(), started, Math::max);
        }
        lastDays.keySet().retainAll(calls.keySet());
        runs.entrySet().removeIf(entry -> {
            DutyRun run = entry.getValue();
            if (run.calls().equals(calls.get(run.duty())))
                return false;
            note(now, run.duty(), run.day(), "given up: the new timetable has another duty " + run.duty());
            return true;
        });
        setDirty();
    }

    /** Stops running a timetable. The running duties are given up, and their trains go on to the station they are bound for and stop. */
    public void unloadTimetable(long now) {
        for (DutyRun run : runs.values())
            note(now, run.duty(), run.day(), "given up: the timetable was unloaded");
        json = "";
        timetable = null;
        calls.clear();
        lastDays.clear();
        runs.clear();
        freeSince.clear();
        setDirty();
    }

    private void use(Timetable timetable) {
        this.timetable = timetable;
        calls.clear();
        for (Duty duty : timetable.duties())
            calls.put(duty.id(), DutyPlan.calls(timetable, duty));
    }

    // ------------------------------------------------------------------ the duties

    /** The last service day duty {@code id} was given a set or missed on; far in the past if never. */
    public long lastDay(String id) {
        return lastDays.getOrDefault(id, Long.MIN_VALUE / 2);
    }

    public void dealtWith(String id, long day) {
        if (!Long.valueOf(day).equals(lastDays.put(id, day)))
            setDirty();
    }

    /** The running duties, by the train that runs each. Whoever changes a run calls {@link #setDirty()}. */
    public Map<UUID, DutyRun> runs() {
        return runs;
    }

    public Optional<UUID> trainOf(String duty) {
        return runs.entrySet().stream().filter(entry -> entry.getValue().duty().equals(duty)).map(Map.Entry::getKey).findFirst();
    }

    /** Since when each free train has waited at its depot, by the train. Whoever changes it calls {@link #setDirty()}. */
    public Map<UUID, Long> freeSince() {
        return freeSince;
    }

    public void note(long clock, String duty, long day, String what) {
        LOGGER.warn("Duty {} of service day {}: {}", duty, day, what);
        notes.addLast(new Note(clock, duty, day, what));
        while (notes.size() > MAX_NOTES)
            notes.removeFirst();
        setDirty();
    }

    /** The notes, oldest first. */
    public List<Note> notes() {
        return Collections.unmodifiableList(new ArrayList<>(notes));
    }

    // ------------------------------------------------------------------ saving

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putString(TAG_TIMETABLE, json);
        CompoundTag days = new CompoundTag();
        lastDays.forEach(days::putLong);
        tag.put(TAG_LAST_DAYS, days);

        ListTag runList = new ListTag();
        runs.forEach((train, run) -> {
            DutyRun.State state = run.state();
            CompoundTag entry = new CompoundTag();
            entry.putUUID(TAG_TRAIN, train);
            entry.putString(TAG_DUTY, state.duty());
            entry.putLong(TAG_DAY, state.day());
            entry.putInt(TAG_CALL, state.call());
            entry.putString(TAG_PHASE, state.phase().name());
            entry.putLong(TAG_DEPARTURE, state.departure());
            entry.put(TAG_ARRIVALS, new LongArrayTag(state.arrivals()));
            entry.put(TAG_DEPARTURES, new LongArrayTag(state.departures()));
            runList.add(entry);
        });
        tag.put(TAG_RUNS, runList);

        ListTag freeList = new ListTag();
        freeSince.forEach((train, since) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(TAG_TRAIN, train);
            entry.putLong(TAG_SINCE, since);
            freeList.add(entry);
        });
        tag.put(TAG_FREE, freeList);

        ListTag noteList = new ListTag();
        for (Note note : notes) {
            CompoundTag entry = new CompoundTag();
            entry.putLong(TAG_CLOCK, note.clock());
            entry.putString(TAG_DUTY, note.duty());
            entry.putLong(TAG_DAY, note.day());
            entry.putString(TAG_WHAT, note.what());
            noteList.add(entry);
        }
        tag.put(TAG_NOTES, noteList);
        return tag;
    }

    public static OperationData load(CompoundTag tag, HolderLookup.Provider registries) {
        OperationData data = new OperationData();
        data.json = tag.getString(TAG_TIMETABLE);
        if (!data.json.isEmpty()) {
            TimetableJson.Result result = TimetableJson.parse(data.json);
            if (result.ok())
                data.use(result.timetable());
            else
                LOGGER.error("The saved timetable no longer reads, and is not run: {}", result.problems());
        }
        CompoundTag days = tag.getCompound(TAG_LAST_DAYS);
        for (String duty : days.getAllKeys())
            data.lastDays.put(duty, days.getLong(duty));

        for (Tag element : tag.getList(TAG_RUNS, Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) element;
            String duty = entry.getString(TAG_DUTY);
            List<Call> calls = data.calls.get(duty);
            if (!entry.hasUUID(TAG_TRAIN) || calls == null)
                continue;
            try {
                DutyRun.State state = new DutyRun.State(duty, entry.getLong(TAG_DAY), entry.getInt(TAG_CALL),
                    DutyRun.Phase.valueOf(entry.getString(TAG_PHASE)), entry.getLong(TAG_DEPARTURE),
                    boxed(entry.getLongArray(TAG_ARRIVALS)), boxed(entry.getLongArray(TAG_DEPARTURES)));
                data.runs.put(entry.getUUID(TAG_TRAIN), DutyRun.restore(state, calls));
            } catch (IllegalArgumentException e) {
                LOGGER.error("The saved run of duty {} does not fit the timetable, and is given up", duty, e);
            }
        }
        for (Tag element : tag.getList(TAG_FREE, Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) element;
            if (entry.hasUUID(TAG_TRAIN))
                data.freeSince.put(entry.getUUID(TAG_TRAIN), entry.getLong(TAG_SINCE));
        }
        for (Tag element : tag.getList(TAG_NOTES, Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) element;
            data.notes.addLast(new Note(entry.getLong(TAG_CLOCK), entry.getString(TAG_DUTY), entry.getLong(TAG_DAY), entry.getString(TAG_WHAT)));
        }
        return data;
    }

    private static List<Long> boxed(long[] values) {
        List<Long> list = new ArrayList<>(values.length);
        for (long value : values)
            list.add(value);
        return list;
    }
}
