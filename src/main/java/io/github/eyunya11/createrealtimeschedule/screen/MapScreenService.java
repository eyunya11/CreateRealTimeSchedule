package io.github.eyunya11.createrealtimeschedule.screen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import com.simibubi.create.Create;

import io.github.eyunya11.createrealtimeschedule.diagram.RequestLimit;
import io.github.eyunya11.createrealtimeschedule.map.LiveMap;
import io.github.eyunya11.createrealtimeschedule.map.MapSelection;
import io.github.eyunya11.createrealtimeschedule.map.ScreenSettings;
import io.github.eyunya11.createrealtimeschedule.map.TagView;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap;
import io.github.eyunya11.createrealtimeschedule.network.MapLivePayload;
import io.github.eyunya11.createrealtimeschedule.network.MapLiveRequestPayload;
import io.github.eyunya11.createrealtimeschedule.network.MapScreenEditPayload;
import io.github.eyunya11.createrealtimeschedule.network.MapScreenOpenPayload;
import io.github.eyunya11.createrealtimeschedule.network.MapViewPayload;
import io.github.eyunya11.createrealtimeschedule.network.MapViewRequestPayload;
import io.github.eyunya11.createrealtimeschedule.operation.OperationData;
import io.github.eyunya11.createrealtimeschedule.operation.TagWays;
import io.github.eyunya11.createrealtimeschedule.railway.CreateMap;
import io.github.eyunya11.createrealtimeschedule.railway.CreateNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import io.github.eyunya11.createrealtimeschedule.railway.NetworkMap;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTagData;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTags;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The server's side of the map screens: their settings, which tracks a screen showing some lines
 * shows, and what the signals show and where the trains are. A client asks for the last two while it
 * shows a screen, so the server keeps no list of who is looking; it answers each player at most so
 * often ({@link RequestLimit}).
 */
public final class MapScreenService {

    /** How far a player may be from a screen to set it, in blocks. */
    public static final double REACH = 8;

    private static final RequestLimit VIEW_LIMIT = new RequestLimit(10);
    private static final RequestLimit LIVE_LIMIT = new RequestLimit(8);

    /** The tracks of the lines last asked for, by the lines and what they were worked out from. */
    private static final Map<List<Object>, MapViewPayload> VIEWS = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<List<Object>, MapViewPayload> eldest) {
            return size() > 32;
        }
    };

    /** How long what is live is sent again as it was, in ticks: the players near screens are answered from one reading. */
    private static final int LIVE_KEPT = 5;

    /** What is live, as last read for each dimension, with the tick it was read in. */
    private static final Map<String, Live> LIVE = new HashMap<>();

    private record Live(long tick, MapLivePayload payload) {}

    private MapScreenService() {}

    /** Opens the settings of the screen at {@code pos} for {@code player}, with the lines of the timetable to choose from. */
    static void open(ServerPlayer player, BlockPos pos) {
        Timetable timetable = OperationData.get(player.server).timetable();
        List<MapScreenOpenPayload.Line> lines = new ArrayList<>();
        if (timetable != null)
            for (Timetable.Line line : timetable.lines())
                if (lines.size() < MapScreenOpenPayload.MAX_LINES)
                    lines.add(new MapScreenOpenPayload.Line(line.id(), line.name()));
        PacketDistributor.sendToPlayer(player, new MapScreenOpenPayload(pos, lines));
    }

    /** Whether a player may set the screen at {@code pos}: one who may build there, as for breaking it. */
    static boolean mayChange(ServerPlayer player, BlockPos pos) {
        return player.mayBuild() && player.serverLevel().mayInteract(player, pos);
    }

    /** A player sets what a screen shows: from near it, one who may build there, and only the kinds of things there are. */
    public static void onEdit(MapScreenEditPayload edit, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player))
            return;
        ServerLevel level = player.serverLevel();
        BlockPos pos = edit.pos();
        if (!level.isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH || !mayChange(player, pos)
            || !(level.getBlockEntity(pos) instanceof MapScreenBlockEntity))
            return;
        if (!Double.isFinite(edit.centreX()) || !Double.isFinite(edit.centreZ()) || Math.abs(edit.centreX()) > 3e7
            || Math.abs(edit.centreZ()) > 3e7)
            return;
        Set<String> hidden = new HashSet<>(edit.hidden());
        hidden.retainAll(ScreenSettings.SCREEN_LAYERS);
        List<String> lines = new ArrayList<>(new LinkedHashSet<>(edit.lines()));
        // A tag the world does not have may be turned off: it is kept, and hides its stretches once there are any.
        Set<String> tagsOff = new HashSet<>(edit.tagsOff());
        ScreenGroup.change(level, pos, settings -> new ScreenSettings(edit.centreX(), edit.centreZ(), edit.scale(), lines, hidden, tagsOff, 0));
    }

    /** A client asks which tracks and stretches the screens it shows show; each is answered if its answer is not the one now. */
    public static void onViewRequest(MapViewRequestPayload request, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
            || !VIEW_LIMIT.allow(player.getUUID(), player.server.overworld().getGameTime()))
            return;
        for (MapViewPayload view : answers(player.server, request.asked()))
            PacketDistributor.sendToPlayer(player, view);
    }

    /** The answers to the questions {@code asked} that are not the ones the client has. */
    public static List<MapViewPayload> answers(MinecraftServer server, List<MapViewRequestPayload.Asked> asked) {
        List<MapViewPayload> answers = new ArrayList<>();
        for (MapViewRequestPayload.Asked question : asked) {
            MapViewPayload view = view(server, question.lines(), question.tagsOff());
            if (view.revision() != question.revision())
                answers.add(view);
        }
        return answers;
    }

    /** A client asks what the signals of its dimension show and where the trains are. */
    public static void onLiveRequest(MapLiveRequestPayload request, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
            || !LIVE_LIMIT.allow(player.getUUID(), player.server.overworld().getGameTime()))
            return;
        String dimension = player.level().dimension().location().toString();
        long now = player.server.overworld().getGameTime();
        Live known = LIVE.get(dimension);
        if (known == null || now < known.tick() || now - known.tick() >= LIVE_KEPT) {
            OperationData operation = OperationData.get(player.server);
            LiveMap live = CreateMap.live(Create.RAILWAYS.trackNetworks.values(), Create.RAILWAYS.trains.values(), player.server::getLevel,
                dimension, train -> operation.tagsOf(train.id));
            known = new Live(now, new MapLivePayload(live));
            LIVE.put(dimension, known);
        }
        PacketDistributor.sendToPlayer(player, known.payload());
    }

    /** Forgets what was worked out for a server that has stopped, which a world opened next must not be shown. */
    static void stopped() {
        LIVE.clear();
        VIEWS.clear();
    }

    /** Forgets a player who has left. */
    static void forget(ServerPlayer player) {
        VIEW_LIMIT.forget(player.getUUID());
        LIVE_LIMIT.forget(player.getUUID());
    }

    /** Which tracks a screen showing {@code lines} shows now, and which stretches of them with the tags {@code tagsOff} turned off. */
    public static MapViewPayload view(MinecraftServer server, List<String> lines, List<String> tagsOff) {
        OperationData data = OperationData.get(server);
        Timetable timetable = data.timetable();
        TrackTags tags = TrackTagData.get(server).tags();
        var graphs = Create.RAILWAYS.trackNetworks.values();
        long print = CreateNetwork.fingerprint(graphs);
        // Never 0, which a client that has no answer says it has. The colours of the tags are not in it: a client has them.
        long revision = ((print * 31 + data.revision()) * 31 + tags.names().hashCode()) * 31 + (timetable == null ? 0 : 1) | 1;
        List<Object> key = List.of(List.copyOf(lines), List.copyOf(tagsOff), revision);
        MapViewPayload known = VIEWS.get(key);
        if (known != null)
            return known;
        CreateNetwork.All all = CreateNetwork.readAll(graphs);
        CreateMap.Read read = CreateMap.read(graphs, List.of(), null);
        NetworkMap map = NetworkMap.of(read.map(), all.edges(), read.trackOf(), read.backward());
        List<String> keys = read.map().tracks().stream().map(track -> track.key()).toList();
        List<MapViewPayload.TrackStretches> stretches = stretches(keys, map.tagPieces(all.network(), tags), Set.copyOf(tagsOff));
        MapViewPayload view;
        if (timetable == null)
            view = new MapViewPayload(lines, tagsOff, revision, true, List.of(), stretches);
        else if (lines.isEmpty())
            view = new MapViewPayload(lines, tagsOff, revision, true, timetable.mapHidden(), stretches);
        else
            view = new MapViewPayload(lines, tagsOff, revision, false, shown(timetable, lines, all.network(), map, keys, tags), stretches);
        VIEWS.put(key, view);
        return view;
    }

    /**
     * The keys of the tracks the lines' trains run along, on the ways of the track tags they are held
     * to too, with those the lines add and without those they take away and the timetable hides.
     */
    private static List<String> shown(Timetable timetable, List<String> lines, RailNetwork network, NetworkMap map, List<String> keys,
        TrackTags tags) {
        Set<String> wanted = Set.copyOf(lines);
        Set<String> stations = new LinkedHashSet<>();
        for (Timetable.Line line : timetable.lines())
            if (wanted.contains(line.id()))
                stations.addAll(line.stations());
        List<MapSelection.LegTracks> legs = new ArrayList<>();
        for (Leg leg : network.legs(stations))
            legs.add(new MapSelection.LegTracks(leg.from(), leg.to(), map.tracks(leg)));
        for (String tag : TagWays.tags(timetable)) {
            OptionalInt held = tags.held(tag);
            boolean other = held.isPresent() && held.getAsInt() == TrackTags.UNKNOWN;
            for (Leg leg : network.legs(stations, held))
                legs.add(new MapSelection.LegTracks(leg.from(), leg.to(), tag, other, map.tracks(leg)));
        }
        List<String> shown = new ArrayList<>();
        for (int track : MapSelection.visible(keys, legs, timetable, lines))
            shown.add(keys.get(track));
        return shown;
    }

    /** The stretches of the tracks that are not all shown with no tag ({@link TagView}), by their keys. */
    private static List<MapViewPayload.TrackStretches> stretches(List<String> keys, List<List<TrackMap.TagPiece>> pieces, Set<String> off) {
        List<MapViewPayload.TrackStretches> stretches = new ArrayList<>();
        List<List<TagView.Segment>> segments = TagView.segments(keys, pieces, off);
        for (int track = 0; track < keys.size(); track++) {
            List<TagView.Segment> ofTrack = segments.get(track);
            if (ofTrack.size() == 1 && ofTrack.getFirst().shown() && !ofTrack.getFirst().tagged())
                continue;
            List<MapViewPayload.Stretch> sent = new ArrayList<>();
            for (TagView.Segment segment : ofTrack)
                sent.add(new MapViewPayload.Stretch((float) segment.from(), (float) segment.to(), segment.shown(), segment.tags()));
            stretches.add(new MapViewPayload.TrackStretches(keys.get(track), sent));
        }
        return stretches;
    }
}
