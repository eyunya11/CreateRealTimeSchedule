package io.github.eyunya11.createrealtimeschedule.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import org.jetbrains.annotations.Nullable;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.simibubi.create.Create;

import io.github.eyunya11.createrealtimeschedule.railway.CreateNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.NetworkJson;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.TagSections;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTagData;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTagService;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTags;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpec;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpecs;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

/**
 * {@code /rtnetwork}: the stations of the track network and the running time between them, as JSON
 * for the timetable editor and the diagram (see {@link NetworkJson}). The name is provisional.
 *
 * <ul>
 * <li>{@code export} writes it to {@code createrealtimeschedule/network.json} in the world folder.</li>
 * <li>{@code tags} lists the world's track tags, how many blocks on the track have each, and what is
 * wrong with the blocks' sections ({@link TagSections}).</li>
 * </ul>
 *
 * <p>{@code /rteditor copy network}, a command of the client, puts it on the player's clipboard (see
 * {@link ClipboardService}).
 *
 * <p>The times are for a train without fuel at full throttle, by the server's config.
 */
public final class RtNetworkCommand {

    /** The prefix of the messages of /rtnetwork, which ClipboardService shares. */
    static final String KEY = "commands.createrealtimeschedule.rtnetwork.";

    private RtNetworkCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rtnetwork")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("export")
                .executes(RtNetworkCommand::export))
            .then(Commands.literal("tags")
                .executes(RtNetworkCommand::tags)));
    }

    /** The network as JSON, with how many stations and legs it has. */
    record Written(String json, int stations, int legs) {}

    /** The network as JSON; null after saying why there is none. */
    static @Nullable Written write(CommandSourceStack source) {
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
        RailNetwork.Routes untagged = network.routes(names, OptionalInt.empty());
        TrackTags tags = TrackTagData.get(source.getServer()).tags();
        return new Written(NetworkJson.write(network, untagged, tags, spec, 1.0), names.size(), untagged.legs().size());
    }

    private static int tags(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        TrackTags tags = TrackTagData.get(source.getServer()).tags();
        RailNetwork network = CreateNetwork.read(Create.RAILWAYS.trackNetworks.values());
        List<RailNetwork.Marker> markers = network.markers();
        List<TagSections.Problem> problems = TagSections.of(network).problems();
        long oneWay = markers.stream().filter(RailNetwork.Marker::oneWay).count();
        source.sendSuccess(() -> Component.translatableWithFallback(KEY + "tags",
            "%1$s track tags; on the track, %2$s tag blocks for both ways and %3$s one-way ones; %4$s problems",
            tags.names().size(), markers.size() - oneWay, oneWay, problems.size()), false);
        for (Map.Entry<Integer, String> tag : tags.names().entrySet()) {
            long blocks = markers.stream().filter(marker -> marker.tags().contains(tag.getKey())).count();
            source.sendSuccess(() -> Component.translatableWithFallback(KEY + "tag", "%1$s: %2$s blocks", tag.getValue(), blocks), false);
        }
        for (TagSections.Problem problem : problems)
            source.sendFailure(Component.translatableWithFallback(KEY + "tag_problem", "The tag block at %1$s: %2$s",
                TrackTagService.where(markers.get(problem.marker()).block()), TrackTagService.describe(problem, problem.marker(), markers)));
        return problems.size();
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
}
