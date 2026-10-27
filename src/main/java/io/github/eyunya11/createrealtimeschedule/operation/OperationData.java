package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.diagram.DutyTimes;
import io.github.eyunya11.createrealtimeschedule.diagram.LineAxis;
import io.github.eyunya11.createrealtimeschedule.railway.Leg;
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
 * a short record of duties that did not run or were given up, and the times of the duties that have
 * stopped running, for the diagram. Held by the overworld's data storage, next to the clock.
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

    /**
     * A duty that has stopped running, kept for the diagram with the times it really made.
     *
     * @param run    the run as it was at the end
     * @param train  the name of the train that ran it, or empty if the train was gone
     * @param status how it ended: {@link DutyTimes.Status#FINISHED} or {@link DutyTimes.Status#GIVEN_UP}
     */
    public record Record(DutyRun run, String train, DutyTimes.Status status) {
        public Record {
            if (status == DutyTimes.Status.RUNNING)
                throw new IllegalArgumentException("a record is of a duty that has stopped running");
        }
    }

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
    private static final String TAG_SKIPPED = "Skipped";
    private static final String TAG_SINCE = "Since";
    private static final String TAG_CLOCK = "Clock";
    private static final String TAG_WHAT = "What";
    private static final String TAG_HISTORY = "History";
    private static final String TAG_NAME = "Name";
    private static final String TAG_ENDING = "Ending";
    private static final String TAG_LOADED_AT = "LoadedAt";
    private static final String TAG_PASSES = "Passes";
    private static final String TAG_WAY = "Way";
    private static final String TAG_STATION = "Station";
    private static final String TAG_AT = "At";

    private String json = "";
    private @Nullable Timetable timetable;
    private final Map<String, List<Call>> calls = new HashMap<>();
    private final Map<String, Long> lastDays = new HashMap<>();
    private final Map<UUID, DutyRun> runs = new LinkedHashMap<>();
    private final Map<UUID, Long> freeSince = new HashMap<>();
    private final Deque<Note> notes = new ArrayDeque<>();
    private final List<Record> history = new ArrayList<>();
    /** When each duty was loaded as it is, by its id: not changed by loading a timetable that has it the same. */
    private final Map<String, Long> loadedAt = new HashMap<>();
    /** Not saved: the call at which each running train was last found to have no way on, so that it is logged once a call. */
    private final Map<UUID, Integer> noWay = new HashMap<>();
    /** Not saved: no duty starts before this clock value, so there is nothing to look for until a lead before it. */
    private long nextStart = Long.MIN_VALUE;
    /** Not saved: goes up whenever the timetable or the axes change, so that a client knows when its copy for the diagram is old. */
    private int revision;
    /** Not saved: goes up whenever the duties' times change, as {@link #revision} does for the timetable. */
    private int timesRevision;
    /** Not saved: the diagram's axis of each line, worked out from the track when it is first needed; null until then. */
    private @Nullable Map<String, LineAxis> axes;
    /** Not saved: the ways between the timetable's stations by their ends, worked out with the axes ({@link TrackFacts}). */
    private @Nullable Map<List<String>, Leg> legs;
    /** Not saved: the trains the sets wait for, worked out from the timetable when first needed; null until then. */
    private @Nullable Waits waits;
    /** Not saved: the end of each train watched for running through a station, on the tick before ({@link PassWatch}). */
    private final Map<UUID, PassWatch.Tail> tails = new HashMap<>();
    /** Not saved: the waits given up; a set that waits no more leaves on the tick it gives up. */
    private final Set<Waits.Key> waitsGivenUp = new HashSet<>();
    /** Not saved: the waits found to have ended without the train being seen to run through the station, noted once. */
    private final Set<Waits.Key> waitsUnseen = new HashSet<>();

    public static OperationData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    // ------------------------------------------------------------------ the timetable

    public @Nullable Timetable timetable() {
        return timetable;
    }

    /** The text of the timetable, as it was loaded; empty if there is none. */
    public String json() {
        return json;
    }

    /** The calls of the timetable's duty {@code id}, or null if it has none of that id. */
    public @Nullable List<Call> calls(String id) {
        return calls.get(id);
    }

    /** The calls of each of the timetable's duties, by its id. */
    public Map<String, List<Call>> allCalls() {
        return Collections.unmodifiableMap(calls);
    }

    /**
     * The trains the timetable's sets wait for, worked out with {@code axes} the first time they are
     * asked for since the timetable or the track changed; the timetable has to be there.
     */
    public Waits waits(Map<String, LineAxis> axes) {
        if (waits == null)
            waits = Waits.of(Objects.requireNonNull(timetable, "timetable"), calls, axes);
        return waits;
    }

    /** The end of each train watched for running through a station, on the tick before, by the train. */
    public Map<UUID, PassWatch.Tail> tails() {
        return tails;
    }

    /** Whether the wait {@code about} has been given up. */
    public boolean waitGivenUp(Waits.Key about) {
        return waitsGivenUp.contains(about);
    }

    public void giveUpWait(Waits.Key about) {
        waitsGivenUp.add(about);
    }

    /** Whether the wait {@code about} is found to have ended unseen for the first time. */
    public boolean firstUnseen(Waits.Key about) {
        return waitsUnseen.add(about);
    }

    /**
     * Runs {@code timetable} from now on, which {@code json} is the text of. Of a duty's starts that
     * have gone by at {@code now}, only the last is looked at, as a duty that may start late (see
     * {@link OperationService}); loading a timetable at noon makes none of the morning's duties count as
     * missed. A running duty goes on if the new timetable has it with the same calls, and is given up
     * if not; so is the record of a duty that has stopped running, which is dropped.
     */
    public void loadTimetable(String json, Timetable timetable, long now) {
        this.json = json;
        Map<String, List<Call>> before = new HashMap<>(calls);
        use(timetable);
        for (Map.Entry<String, List<Call>> duty : calls.entrySet())
            if (!duty.getValue().equals(before.get(duty.getKey())))
                loadedAt.put(duty.getKey(), now);
        loadedAt.keySet().retainAll(calls.keySet());
        for (Duty duty : timetable.duties()) {
            long started = DutyCalendar.lastDayStartedBy(now, DutyPlan.firstDeparture(calls.get(duty.id())));
            lastDays.merge(duty.id(), started - 1, Math::max);
        }
        lastDays.keySet().retainAll(calls.keySet());
        runs.entrySet().removeIf(entry -> {
            DutyRun run = entry.getValue();
            List<Call> loaded = calls.get(run.duty());
            if (run.calls().equals(loaded))
                return false;
            note(now, run.duty(), run.day(), loaded == null
                ? "given up: the new timetable has no duty " + run.duty()
                : "given up: the new timetable changes duty " + run.duty());
            return true;
        });
        history.removeIf(record -> !record.run().calls().equals(calls.get(record.run().duty())));
        timesChanged();
        noWay.clear();
        nextStart = Long.MIN_VALUE;
        forgetAxes();
        setDirty();
    }

    /**
     * Stops running a timetable. The running duties are given up, and their trains go on to the station
     * they are bound for and stop. With no timetable there is no diagram, so the records go too.
     */
    public void unloadTimetable(long now) {
        for (DutyRun run : runs.values())
            note(now, run.duty(), run.day(), "given up: the timetable was unloaded");
        json = "";
        timetable = null;
        calls.clear();
        lastDays.clear();
        loadedAt.clear();
        runs.clear();
        history.clear();
        timesChanged();
        freeSince.clear();
        noWay.clear();
        nextStart = Long.MIN_VALUE;
        forgetAxes();
        setDirty();
    }

    private void use(Timetable timetable) {
        waits = null;
        tails.clear();
        waitsGivenUp.clear();
        waitsUnseen.clear();
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

    /**
     * When duty {@code id} was loaded as it is now, by a timetable that brought it new or changed; far in
     * the past if that is not known. A duty that was to start before then and did not is no duty that
     * did not run.
     */
    public long loadedAt(String id) {
        return loadedAt.getOrDefault(id, Long.MIN_VALUE);
    }

    /** No duty starts before this clock value; {@link Long#MIN_VALUE} if that is not known. */
    public long nextStart() {
        return nextStart;
    }

    public void nextStart(long clock) {
        nextStart = clock;
    }

    /** The call at which each running train was last found to have no way on. */
    public Map<UUID, Integer> noWay() {
        return noWay;
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
        return List.copyOf(notes);
    }

    // ------------------------------------------------------------------ the diagram

    /** How many times the timetable or the axes have changed since the server started. */
    public int revision() {
        return revision;
    }

    /** The axis of each line, by its id; null if it has not been worked out since the timetable or the track last changed. */
    public @Nullable Map<String, LineAxis> axes() {
        return axes;
    }

    /** The ways between the timetable's stations, by their two ends; null if they have not been worked out, as {@link #axes()}. */
    public @Nullable Map<List<String>, Leg> legs() {
        return legs;
    }

    /** Keeps what the track says of the timetable's stations ({@link TrackFacts}). */
    public void track(Map<String, LineAxis> axes, Map<List<String>, Leg> legs) {
        this.axes = Map.copyOf(axes);
        this.legs = Map.copyOf(legs);
    }

    /** How many times the duties' times have changed since the server started. */
    public int timesRevision() {
        return timesRevision;
    }

    /** Says that the duties' times have changed: a run has started, moved on or stopped. */
    public void timesChanged() {
        timesRevision++;
    }

    /** Drops the axes and the ways between stations, and the waits worked out with them, so that they are worked out again, as after the track has changed. */
    public void forgetAxes() {
        axes = null;
        legs = null;
        waits = null;
        revision++;
    }

    // ------------------------------------------------------------------ the records

    /**
     * Keeps the times of {@code run}, which has stopped running, for the diagram. Only the service day
     * of {@code now} and the one before are kept: a duty of the day before can run on past midnight.
     */
    public void keep(DutyRun run, String train, DutyTimes.Status status, long now) {
        history.add(new Record(run, train, status));
        timesChanged();
        forgetOld(now);
        setDirty();
    }

    /** Drops the records of service days before the one before that of {@code now}, and what is kept of their waits. */
    public void forgetOld(long now) {
        long first = RealClock.day(now) - 1;
        waitsGivenUp.removeIf(key -> key.day() < first);
        waitsUnseen.removeIf(key -> key.day() < first);
        if (history.removeIf(record -> record.run().day() < first)) {
            timesChanged();
            setDirty();
        }
    }

    /** The duties that have stopped running, in the order they stopped. */
    public List<Record> history() {
        return List.copyOf(history);
    }

    // ------------------------------------------------------------------ saving

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putString(TAG_TIMETABLE, json);
        CompoundTag loaded = new CompoundTag();
        loadedAt.forEach(loaded::putLong);
        tag.put(TAG_LOADED_AT, loaded);
        CompoundTag days = new CompoundTag();
        lastDays.forEach(days::putLong);
        tag.put(TAG_LAST_DAYS, days);

        ListTag runList = new ListTag();
        runs.forEach((train, run) -> {
            CompoundTag entry = saveRun(run);
            entry.putUUID(TAG_TRAIN, train);
            runList.add(entry);
        });
        tag.put(TAG_RUNS, runList);

        ListTag historyList = new ListTag();
        for (Record record : history) {
            CompoundTag entry = saveRun(record.run());
            entry.putString(TAG_NAME, record.train());
            entry.putString(TAG_ENDING, record.status().name());
            historyList.add(entry);
        }
        tag.put(TAG_HISTORY, historyList);

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

    private static CompoundTag saveRun(DutyRun run) {
        DutyRun.State state = run.state();
        CompoundTag entry = new CompoundTag();
        entry.putString(TAG_DUTY, state.duty());
        entry.putLong(TAG_DAY, state.day());
        entry.putInt(TAG_CALL, state.call());
        entry.putString(TAG_PHASE, state.phase().name());
        entry.putLong(TAG_DEPARTURE, state.departure());
        entry.put(TAG_ARRIVALS, new LongArrayTag(state.arrivals()));
        entry.put(TAG_DEPARTURES, new LongArrayTag(state.departures()));
        if (!state.skipped().isEmpty())
            entry.putIntArray(TAG_SKIPPED, state.skipped());
        if (!state.passes().isEmpty()) {
            ListTag passes = new ListTag();
            for (DutyRun.Pass pass : state.passes()) {
                CompoundTag passTag = new CompoundTag();
                passTag.putInt(TAG_WAY, pass.way());
                passTag.putString(TAG_STATION, pass.station());
                passTag.putLong(TAG_AT, pass.at());
                passes.add(passTag);
            }
            entry.put(TAG_PASSES, passes);
        }
        return entry;
    }

    /** The run saved in {@code entry}, or null if its duty is not in the timetable or the run does not fit it. */
    private @Nullable DutyRun loadRun(CompoundTag entry) {
        String duty = entry.getString(TAG_DUTY);
        List<Call> dutyCalls = calls.get(duty);
        if (dutyCalls == null)
            return null;
        try {
            // A run saved before calls could be left out has no list of them, which reads as none.
            DutyRun.State state = DutyRun.State.of(duty, entry.getLong(TAG_DAY), entry.getInt(TAG_CALL),
                DutyRun.Phase.valueOf(entry.getString(TAG_PHASE)), entry.getLong(TAG_DEPARTURE),
                entry.getLongArray(TAG_ARRIVALS), entry.getLongArray(TAG_DEPARTURES), entry.getIntArray(TAG_SKIPPED));
            List<DutyRun.Pass> passes = new ArrayList<>();
            for (Tag element : entry.getList(TAG_PASSES, Tag.TAG_COMPOUND)) {
                CompoundTag passTag = (CompoundTag) element;
                passes.add(new DutyRun.Pass(passTag.getInt(TAG_WAY), passTag.getString(TAG_STATION), passTag.getLong(TAG_AT)));
            }
            state = new DutyRun.State(state.duty(), state.day(), state.call(), state.phase(), state.departure(), state.arrivals(),
                state.departures(), state.skipped(), passes);
            return DutyRun.restore(state, dutyCalls);
        } catch (IllegalArgumentException e) {
            LOGGER.error("The saved run of duty {} does not fit the timetable, and is dropped", duty, e);
            return null;
        }
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
        CompoundTag loaded = tag.getCompound(TAG_LOADED_AT);
        for (String duty : loaded.getAllKeys())
            data.loadedAt.put(duty, loaded.getLong(duty));
        CompoundTag days = tag.getCompound(TAG_LAST_DAYS);
        for (String duty : days.getAllKeys())
            data.lastDays.put(duty, days.getLong(duty));

        for (Tag element : tag.getList(TAG_RUNS, Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) element;
            DutyRun run = entry.hasUUID(TAG_TRAIN) ? data.loadRun(entry) : null;
            if (run != null)
                data.runs.put(entry.getUUID(TAG_TRAIN), run);
        }
        for (Tag element : tag.getList(TAG_HISTORY, Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) element;
            DutyRun run = data.loadRun(entry);
            DutyTimes.Status status = entry.getString(TAG_ENDING).equals(DutyTimes.Status.FINISHED.name())
                ? DutyTimes.Status.FINISHED : DutyTimes.Status.GIVEN_UP;
            if (run != null)
                data.history.add(new Record(run, entry.getString(TAG_NAME), status));
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
}
