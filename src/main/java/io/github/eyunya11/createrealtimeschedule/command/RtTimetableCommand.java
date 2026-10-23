package io.github.eyunya11.createrealtimeschedule.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;

import io.github.eyunya11.createrealtimeschedule.clock.ClockService;
import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.operation.Call;
import io.github.eyunya11.createrealtimeschedule.operation.DutyCalendar;
import io.github.eyunya11.createrealtimeschedule.operation.DutyPlan;
import io.github.eyunya11.createrealtimeschedule.operation.DutyRun;
import io.github.eyunya11.createrealtimeschedule.operation.OperationData;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Duty;
import io.github.eyunya11.createrealtimeschedule.timetable.TimetableJson;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * {@code /rttimetable}: the timetable the operation runs (see {@link OperationData}). The name is
 * provisional.
 *
 * <ul>
 * <li>{@code load} reads {@code createrealtimeschedule/timetable.json} in the world folder, and runs it
 * if nothing is wrong with it; if anything is, it says what and keeps the timetable it had.</li>
 * <li>{@code status} says, for each duty, where its set is and how late, or when it next starts, and
 * what has gone wrong lately.</li>
 * <li>{@code unload} stops running the timetable.</li>
 * </ul>
 */
public final class RtTimetableCommand {

    private static final String KEY = "commands.createrealtimeschedule.rttimetable.";

    /** The most problems of a refused timetable said in the chat; all of them go to the log. */
    private static final int PROBLEMS_SHOWN = 10;

    /** The most notes {@code status} shows, the latest. */
    private static final int NOTES_SHOWN = 5;

    private RtTimetableCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rttimetable")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("load")
                .executes(RtTimetableCommand::load))
            .then(Commands.literal("status")
                .executes(RtTimetableCommand::status))
            .then(Commands.literal("unload")
                .executes(RtTimetableCommand::unload)));
    }

    /** Where {@code load} reads the timetable from. */
    public static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("createrealtimeschedule").resolve("timetable.json")
            .toAbsolutePath().normalize();
    }

    private static int load(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        Path file = file(server);
        if (!Files.isRegularFile(file)) {
            source.sendFailure(Component.translatableWithFallback(KEY + "missing", "There is no timetable at %1$s", file.toString()));
            return 0;
        }
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            source.sendFailure(Component.translatableWithFallback(KEY + "unreadable",
                "Could not read %1$s: %2$s", file.toString(), String.valueOf(e.getMessage())));
            return 0;
        }

        TimetableJson.Result result = TimetableJson.parse(json);
        if (!result.ok()) {
            List<String> problems = result.problems();
            source.sendFailure(Component.translatableWithFallback(KEY + "refused",
                "The timetable in %1$s was not loaded; what is wrong with it (%2$s):", file.toString(), problems.size()));
            for (String problem : problems.subList(0, Math.min(PROBLEMS_SHOWN, problems.size())))
                source.sendFailure(Component.literal("- " + problem));
            return 0;
        }

        Timetable timetable = result.timetable();
        OperationData.get(server).loadTimetable(json, timetable, ClockService.now(server));
        source.sendSuccess(() -> Component.translatableWithFallback(KEY + "loaded",
            "Running the timetable %1$s: %2$s duties of %3$s trains", name(timetable), timetable.duties().size(),
            timetable.trains().size()), true);
        return timetable.duties().size();
    }

    private static int unload(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        OperationData data = OperationData.get(server);
        int running = data.runs().size();
        data.unloadTimetable(ClockService.now(server));
        source.sendSuccess(() -> Component.translatableWithFallback(KEY + "unloaded",
            "Stopped running the timetable; %1$s running duties were given up", running), true);
        return running;
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        OperationData data = OperationData.get(server);
        Timetable timetable = data.timetable();
        if (timetable == null) {
            source.sendFailure(Component.translatableWithFallback(KEY + "none", "No timetable is being run"));
            return 0;
        }
        long now = ClockService.now(server);
        source.sendSuccess(() -> Component.translatableWithFallback(KEY + "header",
            "Timetable %1$s: %2$s duties, %3$s running", name(timetable), timetable.duties().size(), data.runs().size()), false);
        for (Duty duty : timetable.duties())
            source.sendSuccess(() -> dutyLine(data, duty, now), false);
        List<OperationData.Note> notes = data.notes();
        for (OperationData.Note note : notes.subList(Math.max(0, notes.size() - NOTES_SHOWN), notes.size()))
            source.sendSuccess(() -> Component.translatableWithFallback(KEY + "note", "%1$s: duty %2$s of day %3$s %4$s",
                TimeText.clock(note.clock()), note.duty(), note.day(), note.what()), false);
        return timetable.duties().size();
    }

    private static Component dutyLine(OperationData data, Duty duty, long now) {
        for (Map.Entry<UUID, DutyRun> entry : data.runs().entrySet()) {
            DutyRun run = entry.getValue();
            if (!run.duty().equals(duty.id()))
                continue;
            Train train = Create.RAILWAYS.trains.get(entry.getKey());
            String set = train == null ? entry.getKey().toString() : train.name.getString();
            if (run.phase() == DutyRun.Phase.STANDING || run.phase() == DutyRun.Phase.DEPARTING)
                return Component.translatableWithFallback(KEY + "standing", "%1$s: %2$s at %3$s, leaves at %4$s (%5$s)",
                    duty.id(), set, run.calls().get(run.call()).station(), TimeText.clock(run.departure()), TimeText.delay(run.delay(now)));
            return Component.translatableWithFallback(KEY + "running", "%1$s: %2$s on the way to %3$s (%4$s)",
                duty.id(), set, run.nextStation(), TimeText.delay(run.delay(now)));
        }
        List<Call> calls = data.calls(duty.id());
        int first = DutyPlan.firstDeparture(calls);
        long day = Math.max(data.lastDay(duty.id()) + 1, DutyCalendar.lastDayStartedBy(now, first));
        return Component.translatableWithFallback(KEY + "waiting", "%1$s: next leaves %2$s at %3$s",
            duty.id(), duty.depot(), TimeText.clock(DutyCalendar.startOf(day, first)));
    }

    private static String name(Timetable timetable) {
        return timetable.name().isEmpty() ? "-" : timetable.name();
    }

    /** Clock values and delays as the chat shows them. */
    private static final class TimeText {
        static String clock(long clock) {
            return RealClock.show(clock);
        }

        static String delay(long ticks) {
            long seconds = Math.round(ticks / (double) RealClock.TICKS_PER_SECOND);
            return (seconds >= 0 ? "+" : "") + seconds + " s";
        }
    }
}
