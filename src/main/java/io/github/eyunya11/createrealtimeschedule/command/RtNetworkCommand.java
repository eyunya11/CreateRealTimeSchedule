package io.github.eyunya11.createrealtimeschedule.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.simibubi.create.Create;

import io.github.eyunya11.createrealtimeschedule.railway.CreateNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import io.github.eyunya11.createrealtimeschedule.railway.NetworkJson;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpec;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpecs;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

/**
 * {@code /rtnetwork export}: writes the stations of the track network and the running time between
 * them to {@code createrealtimeschedule/network.json} in the world folder. The name is provisional.
 *
 * <p>The times are for a train without fuel at full throttle, by the server's config. The file is
 * for the timetable editor and the diagram (see {@link NetworkJson}); getting it to a player's
 * clipboard comes with the editor.
 */
public final class RtNetworkCommand {

    private static final String KEY = "commands.createrealtimeschedule.rtnetwork.";

    private RtNetworkCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rtnetwork")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("export")
                .executes(RtNetworkCommand::export)));
    }

    private static int export(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        TrainSpec spec;
        try {
            spec = TrainSpecs.unpowered();
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.translatableWithFallback(KEY + "no_train",
                "Create's config leaves no train that can move (%1$s)", e.getMessage()));
            return 0;
        }

        RailNetwork network = CreateNetwork.read(Create.RAILWAYS.trackNetworks.values());
        List<String> names = network.names();
        List<Leg> legs = network.legs();
        String json = NetworkJson.write(names, legs, spec, 1.0);

        Path file = source.getServer().getWorldPath(LevelResource.ROOT).resolve("createrealtimeschedule").resolve("network.json")
            .toAbsolutePath().normalize();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            source.sendFailure(Component.translatableWithFallback(KEY + "failed",
                "Could not write %1$s: %2$s", file.toString(), String.valueOf(e.getMessage())));
            return 0;
        }
        source.sendSuccess(() -> Component.translatableWithFallback(KEY + "done",
            "Wrote %1$s stations and %2$s legs to %3$s", names.size(), legs.size(), file.toString()), true);
        return legs.size();
    }
}
