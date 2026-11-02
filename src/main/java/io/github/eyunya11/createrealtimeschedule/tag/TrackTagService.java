package io.github.eyunya11.createrealtimeschedule.tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackGraph;

import io.github.eyunya11.createrealtimeschedule.network.TrackTagEditPayload;
import io.github.eyunya11.createrealtimeschedule.network.TrackTagListPayload;
import io.github.eyunya11.createrealtimeschedule.network.TrackTagOpenPayload;
import io.github.eyunya11.createrealtimeschedule.railway.CreateNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.TagSections;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The server's side of the tag screen. Anyone may give a block a tag or change the list, as anyone may
 * name a station; the server only makes sure the player is near the block they ask about.
 */
public final class TrackTagService {

    /** How near a block a player has to be to change it, in blocks. */
    static final double REACH = 64;

    /** The prefix of the screen's messages. */
    public static final String KEY = "createrealtimeschedule.track_tag.";

    private TrackTagService() {}

    /** Opens the tag screen of the block at {@code pos} for {@code player}, or brings it up to date, with {@code message} if there is one. */
    public static void open(ServerPlayer player, BlockPos pos, @Nullable Component message) {
        if (!(player.level().getBlockEntity(pos) instanceof TrackTagBlockEntity entity))
            return;
        PacketDistributor.sendToPlayer(player, new TrackTagOpenPayload(pos, entity.tags(), entity.oneWay(), problems(entity), Optional.ofNullable(message)));
    }

    /** The list as the clients are sent it. */
    public static TrackTagListPayload list(MinecraftServer server) {
        List<TrackTagListPayload.Entry> entries = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : TrackTagData.get(server).tags().names().entrySet())
            entries.add(new TrackTagListPayload.Entry(entry.getKey(), entry.getValue()));
        return new TrackTagListPayload(entries);
    }

    public static void onEdit(TrackTagEditPayload edit, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player))
            return;
        BlockPos pos = edit.pos();
        Level level = player.level();
        if (!level.isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH
            || !(level.getBlockEntity(pos) instanceof TrackTagBlockEntity entity)) {
            // A screen left open as the player walked away: say so, rather than seem to do nothing.
            player.displayClientMessage(Component.translatableWithFallback(KEY + "too_far",
                "That tag block is too far away to change"), true);
            return;
        }
        TrackTagData data = TrackTagData.get(player.server);
        TrackTags tags = data.tags();
        String before = tags.name(edit.tag());
        Outcome outcome = switch (edit.action()) {
            case TOGGLE -> {
                // A tag deleted meanwhile can still be taken off; that is what initialize does too.
                if (before == null && !entity.tags().contains(edit.tag()))
                    yield Outcome.refused(TrackTags.Refusal.UNKNOWN, null);
                entity.toggle(edit.tag());
                yield new Outcome(null, false);
            }
            case CLEAR -> {
                entity.setTags();
                yield new Outcome(null, false);
            }
            case CREATE -> {
                TrackTags.Created created = tags.create(edit.name());
                if (created.refusal() != null)
                    yield Outcome.refused(created.refusal(), edit.name());
                entity.toggle(created.id());
                yield new Outcome(Component.translatableWithFallback(KEY + "created", "Made the tag %1$s", tags.name(created.id())), true);
            }
            case RENAME -> {
                TrackTags.Refusal refusal = tags.rename(edit.tag(), edit.name());
                if (refusal != null)
                    yield Outcome.refused(refusal, edit.name());
                yield new Outcome(Component.translatableWithFallback(KEY + "renamed", "Renamed %1$s to %2$s", before, tags.name(edit.tag())), true);
            }
            case DELETE -> {
                TrackTags.Refusal refusal = tags.delete(edit.tag(), uses(edit.tag()) > 0);
                if (refusal != null)
                    yield Outcome.refused(refusal, before);
                yield new Outcome(Component.translatableWithFallback(KEY + "deleted", "Deleted the tag %1$s", before), true);
            }
        };
        if (outcome.listChanged())
            listChanged(player.server);
        open(player, pos, outcome.message());
    }

    /**
     * Saves the world's list after a change to it and sends it to every player, whose screens and the
     * names over the blocks show it.
     */
    public static void listChanged(MinecraftServer server) {
        TrackTagData.get(server).setDirty();
        PacketDistributor.sendToAllPlayers(list(server));
    }

    /** What a change did: what to tell the player, and whether the list is not as it was. */
    private record Outcome(@Nullable Component message, boolean listChanged) {

        /** A change refused for {@code refusal}, of the tag named {@code name}. */
        static Outcome refused(TrackTags.Refusal refusal, @Nullable String name) {
            return new Outcome(TrackTagService.refused(refusal, name), false);
        }
    }

    private static Component refused(TrackTags.Refusal refusal, @Nullable String name) {
        String shown = name == null ? "" : name.strip();
        return switch (refusal) {
            case BLANK -> Component.translatableWithFallback(KEY + "blank", "A tag needs a name");
            case TOO_LONG -> Component.translatableWithFallback(KEY + "too_long", "A tag's name can be at most %1$s characters long",
                TrackTags.MAX_NAME_LENGTH);
            case TAKEN -> Component.translatableWithFallback(KEY + "taken", "There is a tag named %1$s already", shown);
            case UNKNOWN -> Component.translatableWithFallback(KEY + "unknown", "There is no such tag; it may have been deleted");
            case IN_USE -> Component.translatableWithFallback(KEY + "in_use", "A tag block has the tag %1$s, so it cannot be deleted", shown);
            case CONTROL -> Component.translatableWithFallback(KEY + "control", "A tag's name cannot have a control character, such as a line break");
        };
    }

    /**
     * How many tag blocks on the track have tag {@code id}. A block that points at no track (its track
     * was taken up) is not counted: if its tag is deleted meanwhile, it has none when it is next loaded.
     */
    public static int uses(int id) {
        EdgePointType<TrackTagEdgePoint> type = TrackTagEdgePoint.type();
        int count = 0;
        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values())
            for (TrackTagEdgePoint point : graph.getPoints(type))
                if (point.tags().contains(id))
                    count++;
        return count;
    }

    /**
     * What is wrong with the section of {@code entity}'s block, in the words the screen shows. Only the
     * track graph of the block's point is read: it is all a section can run on, and a block of another
     * graph, in another dimension, may stand at the same place.
     */
    public static List<Component> problems(TrackTagBlockEntity entity) {
        TrackTagEdgePoint point = entity.edgePoint.getEdgePoint();
        TrackGraph graph = point == null ? null : graphOf(point);
        if (graph == null)
            return List.of(Component.translatableWithFallback(KEY + "no_track", "It points at no track yet"));
        RailNetwork network = CreateNetwork.read(List.of(graph));
        TagSections sections = TagSections.of(network);
        BlockPos pos = entity.getBlockPos();
        List<RailNetwork.Marker> markers = network.markers();
        List<Component> said = new ArrayList<>();
        for (int i = 0; i < markers.size(); i++) {
            if (!at(markers.get(i).block(), pos))
                continue;
            for (TagSections.Problem problem : sections.problemsOf(i))
                said.add(describe(problem, i, markers));
        }
        return said;
    }

    /** The track graph that has {@code point}, or null if none has it. */
    private static @Nullable TrackGraph graphOf(TrackTagEdgePoint point) {
        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values())
            if (graph.getPoint(TrackTagEdgePoint.type(), point.getId()) == point)
                return graph;
        return null;
    }

    /** {@code problem} as the player is told it, for the block {@code self}. */
    public static Component describe(TagSections.Problem problem, int self, List<RailNetwork.Marker> markers) {
        return switch (problem.kind()) {
            case JUNCTION -> Component.translatableWithFallback(KEY + "junction",
                "Its section comes to a junction at %1$s before the next tag block of its kind facing the same way", where(problem.node()));
            case MIXED -> Component.translatableWithFallback(KEY + "mixed",
                "It is mixed with the tag block of the other kind at %1$s: their sections overlap, or one is in the other's section",
                where(markers.get(problem.marker() == self ? problem.other() : problem.marker()).block()));
        };
    }

    /** A place in whole blocks, as coordinates are shown. */
    public static String where(@Nullable RailNetwork.Place place) {
        if (place == null || Double.isNaN(place.x()))
            return "?";
        return (int) Math.floor(place.x()) + ", " + (int) Math.floor(place.y()) + ", " + (int) Math.floor(place.z());
    }

    private static boolean at(RailNetwork.Place place, BlockPos pos) {
        return place.x() == pos.getX() && place.y() == pos.getY() && place.z() == pos.getZ();
    }
}
