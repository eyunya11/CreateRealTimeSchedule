package io.github.eyunya11.createrealtimeschedule.command;

import java.time.Clock;
import java.util.OptionalLong;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import io.github.eyunya11.createrealtimeschedule.clock.ClockService;
import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /rtclock get | set <time> | align}. The name is provisional.
 *
 * <p>Messages are translatable with an English fallback, so a client that does not have this mod
 * still reads them.
 */
public final class RtClockCommand {

    private static final String KEY = "commands.createrealtimeschedule.rtclock.";

    private RtClockCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rtclock")
            .then(Commands.literal("get")
                .executes(RtClockCommand::get))
            .then(Commands.literal("set")
                .requires(source -> source.hasPermission(2))
                // Not word(): that stops at the colon of a time. The argument is the last one, so
                // taking the rest of the line costs nothing.
                .then(Commands.argument("time", StringArgumentType.greedyString())
                    .executes(RtClockCommand::set)))
            .then(Commands.literal("align")
                .requires(source -> source.hasPermission(2))
                .executes(RtClockCommand::align)));
    }

    private static int get(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        long now = ClockService.now(source.getServer());
        source.sendSuccess(() -> Component.translatableWithFallback(KEY + "get",
            "Real-time clock: %2$s on day %1$s", RealClock.day(now), RealClock.format(RealClock.timeOfDay(now))), false);
        return 1;
    }

    private static int set(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String text = StringArgumentType.getString(context, "time");
        OptionalLong parsed = RealClock.parseTimeOfDay(text);
        if (parsed.isEmpty()) {
            source.sendFailure(Component.translatableWithFallback(KEY + "invalid",
                "'%1$s' is not a time. Use H:MM or H:MM:SS.", text));
            return 0;
        }
        ClockService.setTimeOfDay(source.getServer(), parsed.getAsLong());
        long now = ClockService.now(source.getServer());
        source.sendSuccess(() -> Component.translatableWithFallback(KEY + "set",
            "Real-time clock set to %2$s on day %1$s", RealClock.day(now), RealClock.format(RealClock.timeOfDay(now))), true);
        return 1;
    }

    private static int align(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ClockService.alignToWallClock(source.getServer(), Clock.systemDefaultZone());
        long now = ClockService.now(source.getServer());
        source.sendSuccess(() -> Component.translatableWithFallback(KEY + "align",
            "Real-time clock aligned to the server's time: %2$s on day %1$s", RealClock.day(now), RealClock.format(RealClock.timeOfDay(now))), true);
        return 1;
    }
}
