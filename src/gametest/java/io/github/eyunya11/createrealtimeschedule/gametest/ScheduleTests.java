package io.github.eyunya11.createrealtimeschedule.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.clock.ClockService;
import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.ModSettings;
import io.github.eyunya11.createrealtimeschedule.schedule.DeparturePlanner.SlotRecord;
import io.github.eyunya11.createrealtimeschedule.schedule.RealTimeOfDayCondition;
import io.github.eyunya11.createrealtimeschedule.schedule.TrainSlotMemory;
import io.netty.buffer.Unpooled;
import net.createmod.catnip.data.Pair;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks on the {@code real_time_of_day} condition against a real server and Create's own schedule
 * code, run with {@code ./gradlew runGameTestServer}.
 *
 * <p>The trains here have no track: a bare {@link Train} is given a schedule and told it has
 * arrived, and Create's {@link ScheduleRuntime#tickConditions} is driven by hand while the test
 * decides what the clock says. That is the path a real train takes once it stands in a station, so
 * everything about waiting is Create's own; only the moving between stations is left out. That part
 * is covered by {@link TrackTests}, which runs a train on real track.
 *
 * <p>The tests never wait for game time. They put the clock where they want it, which the test
 * puts back as it found it, so they cannot disturb the tests that do wait.
 *
 * <p>Minecraft's {@code assertValueEqual(expected, actual, name)} words a failure the other way
 * round, "expected name to be &lt;actual&gt;, but was &lt;expected&gt;", so read those messages with
 * the two swapped.
 */
@GameTestHolder(Createrealtimeschedule.MODID)
@PrefixGameTestTemplate(false)
public class ScheduleTests {

    private static final String EMPTY = "empty";
    private static final String STATUS_KEY = "createrealtimeschedule.schedule.condition.real_time_of_day.status";
    static final int DAILY = 0;
    private static final int EVERY_QUARTER_HOUR = 9;

    // ------------------------------------------------------------------ helpers
    // The ones that are not private are shared with TrackTests.

    /** A clock value: the given day of the clock, at the given time. */
    static long clock(long day, int hour, int minute, int second) {
        return day * RealClock.TICKS_PER_DAY + RealClock.ticksOf(hour, minute, second);
    }

    static String show(long clockTicks) {
        return "day " + RealClock.day(clockTicks) + " " + RealClock.format(clockTicks);
    }

    static RealTimeOfDayCondition condition(int hour, int minute, int rotation, int minimumStopSeconds) {
        return new RealTimeOfDayCondition(hour, minute, rotation, minimumStopSeconds);
    }

    /**
     * A stop with one column of conditions for each condition given, any of which lets the train go.
     * With none given, the stop has no conditions at all and a train that gets there stays.
     */
    static ScheduleEntry stop(String name, RealTimeOfDayCondition... either) {
        DestinationInstruction destination = new DestinationInstruction();
        destination.getData().putString("Text", name);
        List<List<ScheduleWaitCondition>> columns = new ArrayList<>();
        for (RealTimeOfDayCondition condition : either) {
            List<ScheduleWaitCondition> column = new ArrayList<>();
            column.add(condition);
            columns.add(column);
        }
        return new ScheduleEntry(destination, columns);
    }

    static Schedule schedule(ScheduleEntry... entries) {
        return new Schedule(new ArrayList<>(List.of(entries)), true, 0);
    }

    /** Gets the train to the stop it is at in its schedule: from here its conditions are ticked. */
    private static void arrive(Train train) {
        train.runtime.state = ScheduleRuntime.State.IN_TRANSIT;
        train.runtime.destinationReached();
    }

    /** What the train keeps for the condition in the given column of its stop. */
    private static CompoundTag context(Train train, int column) {
        return train.runtime.conditionContext.get(column);
    }

    private static boolean met(Train train, int column) {
        return train.runtime.conditionProgress.get(column) > 0;
    }

    /**
     * The state these tests borrow: the clock, which they move, and the trains' slots, which they
     * leave behind. Closing it puts both back.
     */
    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server;
        final ServerLevel level;
        private final long offset;
        private final List<UUID> trains = new ArrayList<>();

        Fixture(GameTestHelper helper) {
            this.level = helper.getLevel();
            this.server = level.getServer();
            this.offset = ClockService.data(server).offset();
        }

        /** Puts the clock at a value for the rest of this tick. */
        void at(long clockTicks) {
            ClockService.data(server).setOffset(RealClock.offsetFor(server.overworld().getGameTime(), clockTicks));
        }

        Train train(Schedule schedule) {
            Train train = bare(UUID.randomUUID());
            train.runtime.setSchedule(schedule, false);
            return train;
        }

        Train bare(UUID id) {
            trains.add(id);
            return new Train(id, null, null, new ArrayList<>(), new ArrayList<>(), false, 0);
        }

        /** One tick of Create's waiting at the stop, with the clock at the given value. */
        void tick(Train train, long clockTicks) {
            at(clockTicks);
            train.runtime.tickConditions(level);
        }

        /** Create moves a train on from a stop one tick after its condition is met. */
        void leave(Train train) {
            train.runtime.tickConditions(level);
        }

        @Override
        public void close() {
            ClockService.data(server).setOffset(offset);
            TrainSlotMemory memory = TrainSlotMemory.get(server);
            trains.forEach(memory::forget);
        }
    }

    // ------------------------------------------------------------------ in Create's hands

    @GameTest(template = EMPTY)
    public static void theConditionIsInCreatesListOfConditionsOnce(GameTestHelper helper) {
        long listed = Schedule.CONDITION_TYPES.stream().filter(type -> type.getFirst().equals(RealTimeOfDayCondition.ID)).count();
        helper.assertValueEqual(1L, listed, "times the condition is in Create's list");

        RealTimeOfDayCondition.register();
        long listedAgain = Schedule.CONDITION_TYPES.stream().filter(type -> type.getFirst().equals(RealTimeOfDayCondition.ID)).count();
        helper.assertValueEqual(1L, listedAgain, "times it is listed after registering again");

        Supplier<? extends ScheduleWaitCondition> factory = Schedule.CONDITION_TYPES.stream()
            .filter(type -> type.getFirst().equals(RealTimeOfDayCondition.ID))
            .map(Pair::getSecond).findFirst().orElseThrow();
        ScheduleWaitCondition made = factory.get();
        helper.assertTrue(made instanceof RealTimeOfDayCondition, "Create's list makes the condition");
        RealTimeOfDayCondition fresh = (RealTimeOfDayCondition) made;
        helper.assertValueEqual(RealTimeOfDayCondition.DEFAULT_HOUR, fresh.hour(), "hour of a new condition");
        helper.assertValueEqual(0, fresh.minute(), "minute of a new condition");
        helper.assertValueEqual(RealClock.TICKS_PER_DAY, fresh.rotationTicks(), "a new condition repeats daily");
        helper.assertValueEqual(ModSettings.DEFAULT_MINIMUM_STOP_SECONDS * 20L, fresh.minimumStopTicks(),
            "minimum stop of a new condition");
        helper.succeed();
    }

    /** The setting changes where a new condition starts, and nothing about one that has been made. */
    @GameTest(template = EMPTY)
    public static void aNewConditionStartsWithTheMinimumStopTheConfigSays(GameTestHelper helper) {
        ModConfigSpec.ConfigValue<Integer> setting = ModSettings.SPEC.getValues().get("default_minimum_stop_seconds");
        helper.assertTrue(setting != null, "the config has a default_minimum_stop_seconds setting");
        int before = setting.get();
        RealTimeOfDayCondition made = condition(8, 0, DAILY, 5);
        try {
            setting.set(45);
            helper.assertValueEqual(45 * 20L, new RealTimeOfDayCondition().minimumStopTicks(), "minimum stop of a new condition with the setting at 45 s");
            helper.assertValueEqual(45, ModSettings.defaultMinimumStopSeconds(), "the setting as the mod reads it");
            helper.assertValueEqual(5 * 20L, made.minimumStopTicks(), "minimum stop of a condition made with 5 s");
        } finally {
            setting.set(before);
        }
        helper.assertValueEqual(before, ModSettings.defaultMinimumStopSeconds(), "the setting after the test put it back");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aSavedConditionComesBackAsItself(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        RealTimeOfDayCondition original = condition(21, 45, EVERY_QUARTER_HOUR, 95);
        ScheduleWaitCondition back = ScheduleWaitCondition.fromTag(registries, original.write(registries));
        helper.assertTrue(back instanceof RealTimeOfDayCondition, "a saved condition is read back as the same kind");
        helper.assertValueEqual(original.getData(), back.getData(), "the settings after a save and load");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aScheduleHoldingTheConditionTravelsOverTheWire(GameTestHelper helper) {
        Schedule schedule = schedule(stop("A", condition(8, 10, DAILY, 30)), stop("B", condition(8, 25, EVERY_QUARTER_HOUR, 5)));
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        Schedule.STREAM_CODEC.encode(buffer, schedule);
        Schedule decoded = Schedule.STREAM_CODEC.decode(buffer);

        helper.assertValueEqual(2, decoded.entries.size(), "stops after encode and decode");
        for (int i = 0; i < 2; i++) {
            ScheduleWaitCondition before = schedule.entries.get(i).conditions.get(0).get(0);
            ScheduleWaitCondition after = decoded.entries.get(i).conditions.get(0).get(0);
            helper.assertTrue(after instanceof RealTimeOfDayCondition, "stop " + i + " still holds the condition");
            helper.assertValueEqual(before.getData(), after.getData(), "settings of stop " + i);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ one stop

    @GameTest(template = EMPTY)
    public static void aTrainThatArrivesEarlyWaitsForItsSlot(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            Train train = f.train(schedule(stop("A", condition(8, 10, DAILY, 30))));
            arrive(train);
            f.tick(train, clock(2, 7, 58, 0));
            long slot = clock(2, 8, 10, 0);
            helper.assertValueEqual(slot, context(train, 0).getLong("Slot"), "slot booked for an arrival at 07:58");
            helper.assertValueEqual(slot, context(train, 0).getLong("Departure"), "departure of a train that arrives early");
            helper.assertFalse(met(train, 0), "the condition is not met on arrival");

            f.tick(train, slot - 1);
            helper.assertFalse(met(train, 0), "the condition is not met a tick before the slot");
            f.tick(train, slot);
            helper.assertTrue(met(train, 0), "the condition is met at the slot");
            f.leave(train);
            helper.assertValueEqual(1, train.runtime.currentEntry, "the train moves on from the stop");
            helper.assertValueEqual(ScheduleRuntime.State.PRE_TRANSIT, train.runtime.state, "state of a train that has left");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aLateTrainStopsOnlyForTheMinimum(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            Train train = f.train(schedule(stop("A", condition(8, 10, DAILY, 30))));
            arrive(train);
            f.tick(train, clock(2, 8, 13, 0));
            long departure = clock(2, 8, 13, 30);
            helper.assertValueEqual(departure, context(train, 0).getLong("Departure"),
                "departure of a train three minutes late (" + show(departure) + ")");
            f.tick(train, departure - 1);
            helper.assertFalse(met(train, 0), "the late train is held for its minimum stop");
            f.tick(train, departure);
            helper.assertTrue(met(train, 0), "the late train goes once the minimum stop is over");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aTrainJustInTimeStillGetsItsMinimumStop(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            Train train = f.train(schedule(stop("A", condition(8, 10, DAILY, 30))));
            arrive(train);
            f.tick(train, clock(2, 8, 9, 50));
            helper.assertValueEqual(clock(2, 8, 10, 20), context(train, 0).getLong("Departure"),
                "departure of a train that is 10 s early");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aWaitingTrainFollowsTheClockWhenItIsSet(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            long slot = clock(2, 8, 10, 0);

            Train forward = f.train(schedule(stop("A", condition(8, 10, DAILY, 30))));
            arrive(forward);
            f.tick(forward, clock(2, 8, 0, 0));
            helper.assertFalse(met(forward, 0), "waiting at 08:00");
            f.tick(forward, clock(2, 8, 12, 0));
            helper.assertTrue(met(forward, 0), "the clock set past the slot lets the train go");

            Train back = f.train(schedule(stop("A", condition(8, 10, DAILY, 30))));
            arrive(back);
            f.tick(back, clock(2, 8, 0, 0));
            f.tick(back, clock(2, 6, 0, 0));
            helper.assertFalse(met(back, 0), "the clock set back holds the train");
            helper.assertValueEqual(slot, context(back, 0).getLong("Departure"), "departure after the clock was set back");
            f.tick(back, slot);
            helper.assertTrue(met(back, 0), "the train goes when the clock reaches the slot again");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theDepartureTimeSurvivesBeingSaved(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            var registries = f.level.registryAccess();
            Train train = f.train(schedule(stop("A", condition(8, 10, DAILY, 30))));
            arrive(train);
            // Eleven minutes past the slot, so the departure is 08:11:30, which working it out again
            // later would not give.
            f.tick(train, clock(2, 8, 11, 0));
            CompoundTag saved = train.runtime.write(registries);

            Train reloaded = f.bare(train.id);
            reloaded.runtime.read(registries, saved);
            helper.assertValueEqual(clock(2, 8, 11, 30), context(reloaded, 0).getLong("Departure"),
                "departure after the train was saved and loaded");
            f.tick(reloaded, clock(2, 8, 11, 40));
            helper.assertTrue(met(reloaded, 0), "the saved departure is kept, not worked out again from the later clock");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theStatusShowsTheDepartureTime(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            RealTimeOfDayCondition condition = condition(8, 10, DAILY, 30);
            Train train = f.train(schedule(stop("A", condition)));
            Component before = condition.getWaitingStatus(f.level, train, new CompoundTag());
            helper.assertValueEqual("08:10", statusTime(before), "time shown before the train is booked");

            arrive(train);
            f.tick(train, clock(2, 8, 11, 0));
            Component after = condition.getWaitingStatus(f.level, train, context(train, 0));
            helper.assertValueEqual("08:11:30", statusTime(after), "time shown for a train that arrived late");
        }
        helper.succeed();
    }

    private static String statusTime(Component status) {
        TranslatableContents contents = (TranslatableContents) status.getContents();
        if (!STATUS_KEY.equals(contents.getKey()))
            throw new AssertionError("status key is " + contents.getKey());
        return (String) contents.getArgs()[0];
    }

    // ------------------------------------------------------------------ a round

    @GameTest(template = EMPTY)
    public static void aLateTrainWinsItsTimeBackOverARound(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            Train train = f.train(schedule(
                stop("A", condition(8, 10, DAILY, 30)),
                stop("B", condition(8, 25, DAILY, 30)),
                stop("C", condition(8, 40, DAILY, 30))));

            // Three minutes late at A: it stops for the minimum and goes at 08:13:30.
            arrive(train);
            f.tick(train, clock(2, 8, 13, 0));
            helper.assertValueEqual(clock(2, 8, 13, 30), context(train, 0).getLong("Departure"), "departure from A");
            f.tick(train, clock(2, 8, 13, 30));
            helper.assertTrue(met(train, 0), "the train leaves A");
            f.leave(train);
            helper.assertValueEqual(1, train.runtime.currentEntry, "the train is bound for B");

            // Four minutes of running puts it at B at 08:17:30, in time for the 08:25 slot.
            arrive(train);
            f.tick(train, clock(2, 8, 17, 30));
            helper.assertValueEqual(clock(2, 8, 25, 0), context(train, 0).getLong("Departure"),
                "departure from B (" + show(clock(2, 8, 25, 0)) + ")");
            f.tick(train, clock(2, 8, 25, 0));
            helper.assertTrue(met(train, 0), "the train leaves B on its slot");
            f.leave(train);

            arrive(train);
            f.tick(train, clock(2, 8, 29, 0));
            helper.assertValueEqual(clock(2, 8, 40, 0), context(train, 0).getLong("Departure"), "departure from C");
            f.tick(train, clock(2, 8, 40, 0));
            helper.assertTrue(met(train, 0), "the train leaves C on its slot");

            SlotRecord remembered = TrainSlotMemory.get(f.server).get(train.id);
            helper.assertTrue(remembered != null, "the last slot is remembered");
            helper.assertValueEqual(clock(2, 8, 40, 0), remembered.slot(), "the slot remembered after the round");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theSameTimeAskedForTwiceInARoundBooksTomorrowTheSecondTime(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            Train train = f.train(schedule(
                stop("A", condition(8, 10, DAILY, 30)),
                stop("B", condition(8, 10, DAILY, 30))));
            arrive(train);
            f.tick(train, clock(2, 8, 8, 0));
            f.tick(train, clock(2, 8, 10, 0));
            helper.assertTrue(met(train, 0), "the train leaves A on its slot");
            f.leave(train);

            // At B two minutes later, 08:10 is the slot the train has just left on, not one that is
            // already past: it books the next day's.
            arrive(train);
            f.tick(train, clock(2, 8, 12, 0));
            helper.assertValueEqual(clock(3, 8, 10, 0), context(train, 0).getLong("Departure"),
                "departure from B (" + show(clock(3, 8, 10, 0)) + ")");
            helper.assertFalse(met(train, 0), "the train does not leave B at once");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aTrainGivenAnotherScheduleStartsTheChainAgain(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            Train train = f.train(schedule(stop("A", condition(8, 10, DAILY, 30))));
            arrive(train);
            f.tick(train, clock(2, 8, 8, 0));

            // A different schedule, asking for 08:05. Chained off the 08:10 slot of the old one it
            // would book tomorrow's 08:05; as a new schedule it takes the nearest, this morning's.
            train.runtime.setSchedule(schedule(stop("B", condition(8, 5, DAILY, 30))), false);
            arrive(train);
            f.tick(train, clock(2, 8, 12, 0));
            helper.assertValueEqual(clock(2, 8, 12, 30), context(train, 0).getLong("Departure"),
                "departure under the new schedule");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void everyColumnOfAStopChainsOffTheSameSlot(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            Train train = f.train(schedule(
                stop("A", condition(8, 10, DAILY, 30)),
                stop("B", condition(8, 25, DAILY, 30), condition(8, 20, DAILY, 30))));
            arrive(train);
            f.tick(train, clock(2, 8, 8, 0));
            f.tick(train, clock(2, 8, 10, 0));
            f.leave(train);

            // Both columns of B start in the same tick, twenty minutes after A's slot. The second must
            // chain off A's slot, 08:10, like the first. Chained off the 08:25 the first has just
            // booked, its 08:20 would already be behind it and it would book tomorrow's.
            arrive(train);
            f.tick(train, clock(2, 8, 30, 0));
            helper.assertValueEqual(clock(2, 8, 25, 0), context(train, 0).getLong("Slot"), "slot booked by the first column");
            helper.assertValueEqual(clock(2, 8, 20, 0), context(train, 1).getLong("Slot"), "slot booked by the second column");
            helper.assertValueEqual(clock(2, 8, 30, 30), context(train, 1).getLong("Departure"), "departure by the second column");

            f.tick(train, clock(2, 8, 30, 30));
            helper.assertTrue(met(train, 0), "the first column lets the train go");
            helper.assertTrue(met(train, 1), "the second column lets the train go");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ what is remembered

    @GameTest(template = EMPTY)
    public static void theSignatureIgnoresWhereTheTrainIsButNotTheSettings(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            Schedule schedule = schedule(stop("A", condition(8, 10, DAILY, 30)), stop("B", condition(8, 25, DAILY, 30)));
            Train train = f.train(schedule);
            int signature = RealTimeOfDayCondition.signatureOf(f.level, train);

            schedule.savedProgress = 1;
            helper.assertValueEqual(signature, RealTimeOfDayCondition.signatureOf(f.level, train),
                "signature after the train moved on in its schedule");

            schedule.entries.get(1).conditions.get(0).get(0).getData().putInt(RealTimeOfDayCondition.HOUR, 9);
            helper.assertFalse(signature == RealTimeOfDayCondition.signatureOf(f.level, train),
                "signature after a setting was changed");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theSlotsSurviveBeingSavedAndUnknownTrainsAreForgotten(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        UUID chained = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        SlotRecord chainedRecord = new SlotRecord(1_000L, 2_000L, 3_000L, true, -17);
        SlotRecord firstRecord = new SlotRecord(-5_000L, 6_000L, 0L, false, 99);

        TrainSlotMemory memory = new TrainSlotMemory();
        memory.put(chained, chainedRecord);
        memory.put(first, firstRecord);
        TrainSlotMemory loaded = TrainSlotMemory.load(memory.save(new CompoundTag(), registries), registries);
        helper.assertValueEqual(2, loaded.size(), "trains remembered after a save and load");
        helper.assertValueEqual(chainedRecord, loaded.get(chained), "record of a train that chained off a slot");
        helper.assertValueEqual(firstRecord, loaded.get(first), "record of a train that chained off nothing");

        loaded.retainOnly(Set.of(chained));
        helper.assertValueEqual(1, loaded.size(), "trains remembered after the others were dropped");
        helper.assertTrue(loaded.get(first) == null, "a train that is gone is forgotten");
        helper.assertValueEqual(chainedRecord, loaded.get(chained), "a train that is still there keeps its record");

        helper.assertValueEqual(0, TrainSlotMemory.load(new CompoundTag(), registries).size(), "an empty save remembers nothing");
        helper.succeed();
    }
}
