package io.github.eyunya11.createrealtimeschedule.gametest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mojang.brigadier.ParseResults;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.clock.ClockService;
import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.command.ClipboardService;
import io.github.eyunya11.createrealtimeschedule.command.RtTimetableCommand;
import io.github.eyunya11.createrealtimeschedule.diagram.DutyTimes;
import io.github.eyunya11.createrealtimeschedule.network.ClipboardRequestPayload.What;
import io.github.eyunya11.createrealtimeschedule.operation.DutyRun;
import io.github.eyunya11.createrealtimeschedule.operation.OperationData;
import io.github.eyunya11.createrealtimeschedule.operation.Waits;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.TimetableJson;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * {@code /rttimetable} and what the operation keeps, with {@code ./gradlew runGameTestServer}: a
 * timetable written to the world folder is loaded, one with a mistake in it is not, and a running duty
 * comes back the same from what is saved.
 *
 * <p>Each test leaves no timetable loaded and no file behind, and has a batch of its own, because the
 * operation is the server's.
 */
@GameTestHolder(Createrealtimeschedule.MODID)
@PrefixGameTestTemplate(false)
public class TimetableCommandTests {

    private static final String EMPTY = "empty";

    /** The depot, A and B, as in {@link DutyRunTests}, written as a player would. */
    static final String TIMETABLE = """
        {
          "format": "createrealtimeschedule.timetable",
          "version": 2,
          "name": "Test loop",
          "lines": [{"id": "loop", "name": "Loop", "stations": ["Depot", "A", "B"], "loop": true}],
          "trains": [
            {"number": "D1", "line": "loop", "deadhead": true, "stops": [
              {"station": "Depot", "depart": "12:00:05"}, {"station": "A", "arrive": "12:00:07"}]},
            {"number": "1", "line": "loop", "stops": [
              {"station": "A", "depart": "12:00:20"}, {"station": "B", "arrive": "12:00:27"}]},
            {"number": "D2", "line": "loop", "deadhead": true, "stops": [
              {"station": "B", "depart": "12:00:35"}, {"station": "Depot", "arrive": "12:01:00"}]}
          ],
          "duties": [{"id": "1A", "depot": "Depot", "trains": ["D1", "1", "D2"]}]
        }
        """;

    @GameTest(template = EMPTY, batch = "realtimetimetable_load")
    public static void aTimetableInTheWorldFolderIsLoadedAndUnloaded(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        try {
            write(server, TIMETABLE);
            helper.assertTrue(run(server, "rttimetable load") == 1, "/rttimetable load did not load the one duty");
            Timetable timetable = OperationData.get(server).timetable();
            helper.assertTrue(timetable != null && timetable.name().equals("Test loop"), "the timetable is not the one written: " + timetable);
            helper.assertTrue(run(server, "rttimetable status") == 1, "/rttimetable status did not tell of the one duty");
            run(server, "rttimetable unload");
            helper.assertTrue(OperationData.get(server).timetable() == null, "the timetable is still run after /rttimetable unload");
            helper.succeed();
        } finally {
            cleanUp(server);
        }
    }

    /** The duty ends at B, not at its depot: the timetable is refused, and the one run before goes on being run. */
    @GameTest(template = EMPTY, batch = "realtimetimetable_refused")
    public static void aTimetableWithAMistakeIsRefusedAndTheOldOneKept(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        try {
            write(server, TIMETABLE);
            run(server, "rttimetable load");
            write(server, TIMETABLE.replace("\"trains\": [\"D1\", \"1\", \"D2\"]", "\"trains\": [\"D1\", \"1\"]"));
            helper.assertTrue(run(server, "rttimetable load") == 0, "a timetable whose duty ends away from its depot was loaded");
            Timetable timetable = OperationData.get(server).timetable();
            helper.assertTrue(timetable != null && timetable.duties().getFirst().trains().size() == 3,
                "the timetable run before is not run any more: " + timetable);
            helper.succeed();
        } finally {
            cleanUp(server);
        }
    }

    @GameTest(template = EMPTY, batch = "realtimetimetable_saved")
    public static void aRunningDutyIsSavedAndReadBackTheSame(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Timetable timetable = TimetableJson.parse(TIMETABLE).timetable();
        OperationData data = new OperationData();
        long now = ClockService.now(server);
        data.loadTimetable(TIMETABLE, timetable, now);
        DutyRun run = DutyRun.start("1A", 3, data.calls("1A"), now);
        run.left(run.departure());
        UUID train = new UUID(1, 2);
        data.runs().put(train, run);
        data.freeSince().put(new UUID(3, 4), now - 100);
        data.note(now, "1A", 2, "did not run: no free set at the depot by its time");
        DutyRun done = DutyRun.start("1A", RealClock.day(now), data.calls("1A"), now);
        done.left(done.departure());
        data.keep(done, "Train 7", DutyTimes.Status.GIVEN_UP, now);

        CompoundTag saved = data.save(new CompoundTag(), server.registryAccess());
        OperationData again = OperationData.load(saved, server.registryAccess());
        helper.assertTrue(again.timetable() != null && again.timetable().equals(timetable), "the timetable did not come back");
        helper.assertTrue(again.runs().size() == 1 && again.runs().get(train).state().equals(run.state()),
            "the run did not come back the same: " + again.runs());
        helper.assertTrue(again.freeSince().equals(data.freeSince()), "the free sets did not come back");
        helper.assertTrue(again.notes().equals(data.notes()), "the notes did not come back");
        helper.assertTrue(again.lastDay("1A") == data.lastDay("1A"), "the last day of the duty did not come back");
        List<OperationData.Record> history = again.history();
        helper.assertTrue(history.size() == 1 && history.getFirst().run().state().equals(done.state())
                && history.getFirst().train().equals("Train 7") && history.getFirst().status() == DutyTimes.Status.GIVEN_UP,
            "the record of the duty given up did not come back the same: " + history);
        helper.succeed();
    }

    /** A duty that comes back to A: out to A, on to B, back to A, and in again. */
    private static final String BACK_TO_A = """
        {
          "format": "createrealtimeschedule.timetable",
          "version": 2,
          "name": "Back to A",
          "lines": [{"id": "loop", "name": "Loop", "stations": ["Depot", "A", "B"], "loop": true}],
          "trains": [
            {"number": "D1", "line": "loop", "deadhead": true, "stops": [
              {"station": "Depot", "depart": "12:00:05"}, {"station": "A", "arrive": "12:00:07"}]},
            {"number": "1", "line": "loop", "stops": [
              {"station": "A", "depart": "12:00:20"}, {"station": "B", "arrive": "12:00:27"}]},
            {"number": "2", "line": "loop", "stops": [
              {"station": "B", "depart": "12:00:35"}, {"station": "A", "arrive": "12:01:00"}]},
            {"number": "D2", "line": "loop", "deadhead": true, "stops": [
              {"station": "A", "depart": "12:01:20"}, {"station": "Depot", "arrive": "12:01:40"}]}
          ],
          "duties": [{"id": "1A", "depot": "Depot", "trains": ["D1", "1", "2", "D2"]}]
        }
        """;

    /**
     * A run whose set was held at A, from its first call there to its second, and so leaves out the
     * call at B, is saved and read back the same, running and in the records; and so is when the
     * timetable was loaded.
     */
    @GameTest(template = EMPTY, batch = "realtimetimetable_saved_held")
    public static void aHeldRunIsSavedAndReadBackTheSame(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Timetable timetable = TimetableJson.parse(BACK_TO_A).timetable();
        OperationData data = new OperationData();
        long now = ClockService.now(server);
        data.loadTimetable(BACK_TO_A, timetable, now);
        DutyRun run = heldAtA(DutyRun.start("1A", 3, data.calls("1A"), now));
        UUID train = new UUID(1, 2);
        data.runs().put(train, run);
        DutyRun done = heldAtA(DutyRun.start("1A", RealClock.day(now), data.calls("1A"), now));
        done.left(done.departure());
        done.arrived(done.plannedArrival(4), 0);
        data.keep(done, "Train 7", DutyTimes.Status.FINISHED, now);

        CompoundTag saved = data.save(new CompoundTag(), server.registryAccess());
        OperationData again = OperationData.load(saved, server.registryAccess());
        helper.assertTrue(again.runs().size() == 1 && again.runs().get(train).state().equals(run.state())
            && again.runs().get(train).skipped().equals(List.of(2)), "the held run did not come back the same: " + again.runs());
        List<OperationData.Record> history = again.history();
        helper.assertTrue(history.size() == 1 && history.getFirst().run().state().equals(done.state())
            && history.getFirst().run().skipped().equals(List.of(2)), "the record of the held run did not come back the same: " + history);
        helper.assertTrue(again.loadedAt("1A") == now && data.loadedAt("1A") == now, "when the duty was loaded did not come back");
        // Loading the same timetable again leaves the duty as loaded when it was; a changed one is loaded anew.
        data.loadTimetable(BACK_TO_A, timetable, now + 100);
        helper.assertTrue(data.loadedAt("1A") == now, "loading the same duty again moved when it was loaded");
        data.loadTimetable(TIMETABLE, TimetableJson.parse(TIMETABLE).timetable(), now + 200);
        helper.assertTrue(data.loadedAt("1A") == now + 200, "a changed duty is not loaded anew");
        helper.succeed();
    }

    /** {@code run}, out to A late and held there until its second call there. */
    private static DutyRun heldAtA(DutyRun run) {
        run.left(run.departure());
        run.arrived(run.plannedArrival(1) + 200, 0);
        run.hold(3, run.plannedDeparture(3));
        return run;
    }

    /**
     * The records of a duty's times are kept for the service day of now and the one before, and dropped
     * when a new timetable changes the duty.
     */
    @GameTest(template = EMPTY, batch = "realtimetimetable_records")
    public static void recordsAreKeptForTwoDaysAndDroppedWhenTheDutyChanges(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        long now = ClockService.now(server);
        long today = RealClock.day(now);
        OperationData data = new OperationData();
        data.loadTimetable(TIMETABLE, TimetableJson.parse(TIMETABLE).timetable(), now);
        data.keep(DutyRun.start("1A", today - 2, data.calls("1A"), now), "", DutyTimes.Status.GIVEN_UP, now);
        data.keep(DutyRun.start("1A", today - 1, data.calls("1A"), now), "", DutyTimes.Status.GIVEN_UP, now);
        data.keep(DutyRun.start("1A", today, data.calls("1A"), now), "", DutyTimes.Status.GIVEN_UP, now);
        helper.assertTrue(data.history().stream().map(record -> record.run().day()).toList().equals(List.of(today - 1, today)),
            "the records kept are not those of today and the day before: " + data.history());

        Waits.Key yesterday = new Waits.Key("1A", today - 1, 1, 0);
        Waits.Key ofToday = new Waits.Key("1A", today, 1, 0);
        data.giveUpWait(yesterday);
        data.giveUpWait(ofToday);
        data.firstUnseen(yesterday);
        data.forgetOld(now + RealClock.TICKS_PER_DAY);
        helper.assertTrue(data.history().size() == 1 && data.history().getFirst().run().day() == today,
            "a day on, the record of the day before yesterday is kept: " + data.history());
        helper.assertTrue(!data.waitGivenUp(yesterday) && data.waitGivenUp(ofToday),
            "a day on, the waits given up are not those of the day before and today");
        helper.assertTrue(data.firstUnseen(yesterday), "a day on, an unseen wait of the day before yesterday is kept");

        data.loadTimetable(TIMETABLE, TimetableJson.parse(TIMETABLE).timetable(), now);
        helper.assertTrue(data.history().size() == 1, "the record of a duty that has not changed is dropped on a reload");
        String changed = TIMETABLE.replace("\"depart\": \"12:00:20\"", "\"depart\": \"12:00:21\"");
        data.loadTimetable(changed, TimetableJson.parse(changed).timetable(), now);
        helper.assertTrue(data.history().isEmpty(), "the record of a duty whose times have changed is kept");
        helper.succeed();
    }

    @GameTest(template = EMPTY, batch = "realtimetimetable_reloaded")
    public static void aReloadKeepsARunWhoseDutyIsTheSameAndGivesUpTheOthers(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String two = TIMETABLE
            .replace("\"duties\": [{\"id\": \"1A\", \"depot\": \"Depot\", \"trains\": [\"D1\", \"1\", \"D2\"]}]",
                "\"duties\": [{\"id\": \"1A\", \"depot\": \"Depot\", \"trains\": [\"D1\", \"1\", \"D2\"]}, "
                    + "{\"id\": \"2A\", \"depot\": \"Depot\", \"trains\": [\"D3\", \"2\", \"D4\"]}]")
            .replace("\"trains\": [\n", "\"trains\": [\n"
                + "    {\"number\": \"D3\", \"line\": \"loop\", \"deadhead\": true, \"stops\": [\n"
                + "      {\"station\": \"Depot\", \"depart\": \"13:00:05\"}, {\"station\": \"A\", \"arrive\": \"13:00:07\"}]},\n"
                + "    {\"number\": \"2\", \"line\": \"loop\", \"stops\": [\n"
                + "      {\"station\": \"A\", \"depart\": \"13:00:20\"}, {\"station\": \"B\", \"arrive\": \"13:00:27\"}]},\n"
                + "    {\"number\": \"D4\", \"line\": \"loop\", \"deadhead\": true, \"stops\": [\n"
                + "      {\"station\": \"B\", \"depart\": \"13:00:35\"}, {\"station\": \"Depot\", \"arrive\": \"13:01:00\"}]},\n");
        Timetable both = TimetableJson.parse(two).timetable();
        helper.assertTrue(both != null, "the timetable of two duties does not read: " + TimetableJson.parse(two).problems());
        long now = ClockService.now(server);
        OperationData data = new OperationData();
        data.loadTimetable(two, both, now);
        UUID kept = new UUID(1, 1);
        UUID dropped = new UUID(2, 2);
        data.runs().put(kept, DutyRun.start("1A", 3, data.calls("1A"), now));
        data.runs().put(dropped, DutyRun.start("2A", 3, data.calls("2A"), now));

        data.loadTimetable(TIMETABLE, TimetableJson.parse(TIMETABLE).timetable(), now);
        helper.assertTrue(data.runs().keySet().equals(Set.of(kept)), "the runs after the reload are " + data.runs().keySet());
        helper.assertTrue(data.notes().size() == 1 && data.notes().getFirst().what().equals("given up: the new timetable has no duty 2A"),
            "the run of the duty that is gone is not noted as such: " + data.notes());

        String changed = TIMETABLE.replace("\"depart\": \"12:00:20\"", "\"depart\": \"12:00:21\"");
        data.loadTimetable(changed, TimetableJson.parse(changed).timetable(), now);
        helper.assertTrue(data.runs().isEmpty(), "a run of a duty whose times have changed is kept");
        helper.assertTrue(data.notes().size() == 2 && data.notes().getLast().what().equals("given up: the new timetable changes duty 1A"),
            "the run of the changed duty is not noted as such: " + data.notes());
        helper.succeed();
    }

    /**
     * What {@code /rttimetable load} and {@code /rteditor paste} share: text with nothing wrong in it is run, and
     * text with a mistake is refused while the timetable run before goes on.
     */
    @GameTest(template = EMPTY, batch = "realtimetimetable_text")
    public static void aTimetableAsTextIsRunOrRefusedAsAFileIs(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        try {
            CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
            helper.assertTrue(RtTimetableCommand.loadText(source, TIMETABLE, Component.literal("a test")) == 1,
                "the timetable of one duty was not loaded from text");
            Timetable timetable = OperationData.get(server).timetable();
            helper.assertTrue(timetable != null && timetable.name().equals("Test loop"), "the timetable run is " + timetable);
            String broken = TIMETABLE.replace("\"depart\": \"12:00:20\"", "\"depart\": \"12:00:99\"");
            helper.assertTrue(RtTimetableCommand.loadText(source, broken, Component.literal("a test")) == 0, "a timetable with a bad time was loaded");
            helper.assertTrue(OperationData.get(server).timetable() == timetable, "the timetable run before is not run any more");
            helper.succeed();
        } finally {
            cleanUp(server);
        }
    }

    /**
     * The clipboard is the player's: no command of the server's reaches it, not even one run as another
     * player, since copying and pasting are commands of the client (/rteditor). What the server sends a
     * client that asks to copy is the timetable it runs, as it was loaded, or nothing while it runs none.
     */
    @GameTest(template = EMPTY, batch = "realtimetimetable_clipboard")
    public static void theServerHasNoClipboardCommandAndCopiesTheTimetableItRuns(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        try {
            CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
            for (String command : List.of("rttimetable copy", "rttimetable paste", "rtnetwork copy", "rteditor paste")) {
                ParseResults<CommandSourceStack> parsed = server.getCommands().getDispatcher().parse(command, source);
                helper.assertTrue(parsed.getReader().canRead() || parsed.getContext().getCommand() == null,
                    "/" + command + " is a command of the server");
            }
            helper.assertTrue(ClipboardService.copy(source, What.TIMETABLE) == null, "a timetable is copied while none is run");
            OperationData.get(server).loadTimetable(TIMETABLE, TimetableJson.parse(TIMETABLE).timetable(), ClockService.now(server));
            ClipboardService.Copy copy = ClipboardService.copy(source, What.TIMETABLE);
            helper.assertTrue(copy != null && copy.text().equals(TIMETABLE), "what is copied is not the timetable as it was loaded: " + copy);
            helper.succeed();
        } finally {
            cleanUp(server);
        }
    }

    private static void write(MinecraftServer server, String json) {
        Path file = RtTimetableCommand.file(server);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What the command returned; a command that fails returns 0. */
    private static int run(MinecraftServer server, String command) {
        int[] result = {0};
        var source = server.createCommandSourceStack().withSuppressedOutput().withCallback((success, value) -> result[0] = value);
        server.getCommands().performPrefixedCommand(source, command);
        return result[0];
    }

    private static void cleanUp(MinecraftServer server) {
        OperationData.get(server).unloadTimetable(ClockService.now(server));
        try {
            Files.deleteIfExists(RtTimetableCommand.file(server));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
