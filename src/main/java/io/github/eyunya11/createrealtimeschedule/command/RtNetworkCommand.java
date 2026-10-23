package io.github.eyunya11.createrealtimeschedule.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.simibubi.create.Create;

import io.github.eyunya11.createrealtimeschedule.network.ClipboardPayload;
import io.github.eyunya11.createrealtimeschedule.railway.CreateNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import io.github.eyunya11.createrealtimeschedule.railway.NetworkJson;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpec;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpecs;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * {@code /rtnetwork}: the stations of the track network and the running time between them, as JSON
 * for the timetable editor and the diagram (see {@link NetworkJson}). The name is provisional.
 *
 * <ul>
 * <li>{@code export} writes it to {@code createrealtimeschedule/network.json} in the world folder.</li>
 * <li>{@code copy} puts it on the clipboard of the player who runs it.</li>
 * </ul>
 *
 * <p>The times are for a train without fuel at full throttle, by the server's config.
 */
public final class RtNetworkCommand {

    private static final String KEY = "commands.createrealtimeschedule.rtnetwork.";

    private RtNetworkCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rtnetwork")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("export")
                .executes(RtNetworkCommand::export))
            .then(Commands.literal("copy")
                .executes(RtNetworkCommand::copy)));
    }

    /** The network as JSON, with how many stations and legs it has. */
    private record Written(String json, int stations, int legs) {}

    /** The network as JSON; null after saying why there is none. */
    private static @Nullable Written write(CommandSourceStack source) {
        TrainSpec spec;
        try {
            spec = TrainSpecs.unpowered();
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.translatableWithFallback(KEY + "no_train",
                "Create's config leaves no train that can move (%1$s)", e.getMessage()));
            return null;
        }
        RailNetwork network = CreateNetwork.read(Create.RAILWAYS.trackNetworks.values());
        List<String> names = network.names();
        List<Leg> legs = network.legs();
        return new Written(NetworkJson.write(names, legs, spec, 1.0), names.size(), legs.size());
    }

    private static int export(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Written written = write(source);
        if (written == null)
            return 0;
        Path file = source.getServer().getWorldPath(LevelResource.ROOT).resolve("createrealtimeschedule").resolve("network.json")
            .toAbsolutePath().normalize();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, written.json(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            source.sendFailure(Component.translatableWithFallback(KEY + "failed",
                "Could not write %1$s: %2$s", file.toString(), String.valueOf(e.getMessage())));
            return 0;
        }
        source.sendSuccess(() -> Component.translatableWithFallback(KEY + "done",
            "Wrote %1$s stations and %2$s legs to %3$s", written.stations(), written.legs(), file.toString()), true);
        return written.legs();
    }

    private static int copy(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = RtTimetableCommand.player(source);
        if (player == null)
            return 0;
        Written written = write(source);
        if (written == null)
            return 0;
        if (written.json().length() > ClipboardPayload.MAX_LENGTH) {
            source.sendFailure(RtTimetableCommand.tooLarge(written.json().length()));
            return 0;
        }
        PacketDistributor.sendToPlayer(player, new ClipboardPayload(written.json()));
        source.sendSuccess(() -> Component.translatableWithFallback(KEY + "copied",
            "Copied %1$s stations and %2$s legs to your clipboard", written.stations(), written.legs()), false);
        return written.legs();
    }
}
