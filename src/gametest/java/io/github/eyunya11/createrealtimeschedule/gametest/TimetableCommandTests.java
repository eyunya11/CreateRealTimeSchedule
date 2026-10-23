package io.github.eyunya11.createrealtimeschedule.gametest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.clock.ClockService;
import io.github.eyunya11.createrealtimeschedule.command.RtTimetableCommand;
import io.github.eyunya11.createrealtimeschedule.operation.DutyRun;
import io.github.eyunya11.createrealtimeschedule.operation.OperationData;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.TimetableJson;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
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
    private static final String TIMETABLE = """
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

        CompoundTag saved = data.save(new CompoundTag(), server.registryAccess());
        OperationData again = OperationData.load(saved, server.registryAccess());
        helper.assertTrue(again.timetable() != null && again.timetable().equals(timetable), "the timetable did not come back");
        helper.assertTrue(again.runs().size() == 1 && again.runs().get(train).state().equals(run.state()),
            "the run did not come back the same: " + again.runs());
        helper.assertTrue(again.freeSince().equals(data.freeSince()), "the free sets did not come back");
        helper.assertTrue(again.notes().equals(data.notes()), "the notes did not come back");
        helper.assertTrue(again.lastDay("1A") == data.lastDay("1A"), "the last day of the duty did not come back");
        helper.succeed();
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
