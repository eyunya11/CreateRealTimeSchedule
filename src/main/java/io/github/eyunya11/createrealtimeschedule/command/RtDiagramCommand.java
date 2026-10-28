package io.github.eyunya11.createrealtimeschedule.command;

import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import io.github.eyunya11.createrealtimeschedule.diagram.DiagramService;
import io.github.eyunya11.createrealtimeschedule.network.DiagramOpenPayload;
import io.github.eyunya11.createrealtimeschedule.operation.OperationData;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Line;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * {@code /rtdiagram}: opens the diagram of the timetable being run, for the player who runs it. The
 * name is provisional.
 *
 * <ul>
 * <li>{@code /rtdiagram} shows the first line, and {@code /rtdiagram line <id>} the line of that id.
 * Anyone may look.</li>
 * <li>{@code /rtdiagram refresh} works the distances between the stations out from the track again
 * at once (permission level 2). The server does so by itself too once the track has changed and stayed
 * as it is for a few seconds ({@code TrackFacts.watch}).</li>
 * </ul>
 */
public final class RtDiagramCommand {

    private static final String KEY = "commands.createrealtimeschedule.rtdiagram.";

    private RtDiagramCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rtdiagram")
            .executes(context -> open(context, ""))
            .then(Commands.literal("line")
                .then(Commands.argument("line", StringArgumentType.greedyString())
                    .suggests((context, builder) -> {
                        Timetable timetable = OperationData.get(context.getSource().getServer()).timetable();
                        return SharedSuggestionProvider.suggest(
                            timetable == null ? List.of() : timetable.lines().stream().map(Line::id).toList(), builder);
                    })
                    .executes(context -> open(context, StringArgumentType.getString(context, "line")))))
            .then(Commands.literal("refresh")
                .requires(source -> source.hasPermission(2))
                .executes(RtDiagramCommand::refresh)));
    }

    private static int open(CommandContext<CommandSourceStack> context, String line) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.translatableWithFallback(KEY + "not_player", "Only a player can look at the diagram"));
            return 0;
        }
        Timetable timetable = OperationData.get(source.getServer()).timetable();
        if (timetable == null) {
            source.sendFailure(Component.translatableWithFallback(KEY + "none", "No timetable is being run, so there is no diagram"));
            return 0;
        }
        if (!line.isEmpty() && timetable.line(line).isEmpty()) {
            source.sendFailure(Component.translatableWithFallback(KEY + "no_line", "The timetable has no line %1$s", line));
            return 0;
        }
        PacketDistributor.sendToPlayer(player, new DiagramOpenPayload(line));
        return 1;
    }

    private static int refresh(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        OperationData data = OperationData.get(source.getServer());
        Timetable timetable = data.timetable();
        if (timetable == null) {
            source.sendFailure(Component.translatableWithFallback(KEY + "none", "No timetable is being run, so there is no diagram"));
            return 0;
        }
        data.forgetAxes();
        int lines = DiagramService.axes(data, timetable).size();
        source.sendSuccess(() -> Component.translatableWithFallback(KEY + "refreshed",
            "Worked out the stations of %1$s lines from the track again", lines), true);
        return lines;
    }
}
