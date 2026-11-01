package io.github.eyunya11.createrealtimeschedule.screen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.simibubi.create.Create;

import io.github.eyunya11.createrealtimeschedule.diagram.RequestLimit;
import io.github.eyunya11.createrealtimeschedule.map.LiveMap;
import io.github.eyunya11.createrealtimeschedule.map.MapSelection;
import io.github.eyunya11.createrealtimeschedule.map.ScreenSettings;
import io.github.eyunya11.createrealtimeschedule.network.MapLivePayload;
import io.github.eyunya11.createrealtimeschedule.network.MapLiveRequestPayload;
import io.github.eyunya11.createrealtimeschedule.network.MapScreenEditPayload;
import io.github.eyunya11.createrealtimeschedule.network.MapScreenOpenPayload;
import io.github.eyunya11.createrealtimeschedule.network.MapViewPayload;
import io.github.eyunya11.createrealtimeschedule.network.MapViewRequestPayload;
import io.github.eyunya11.createrealtimeschedule.operation.OperationData;
import io.github.eyunya11.createrealtimeschedule.railway.CreateMap;
import io.github.eyunya11.createrealtimeschedule.railway.CreateNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import io.github.eyunya11.createrealtimeschedule.railway.NetworkMap;
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

    /** A player sets what a screen shows: from near it, and only the kinds of things there are. */
    public static void onEdit(MapScreenEditPayload edit, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player))
            return;
        ServerLevel level = player.serverLevel();
        BlockPos pos = edit.pos();
        if (!level.isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH
            || !(level.getBlockEntity(pos) instanceof MapScreenBlockEntity))
            return;
        if (!Double.isFinite(edit.centreX()) || !Double.isFinite(edit.centreZ()) || Math.abs(edit.centreX()) > 3e7
            || Math.abs(edit.centreZ()) > 3e7)
            return;
        Set<String> hidden = new HashSet<>(edit.hidden());
        hidden.retainAll(ScreenSettings.LAYERS);
        List<String> lines = new ArrayList<>(new LinkedHashSet<>(edit.lines()));
        ScreenGroup.change(level, pos, settings -> new ScreenSettings(edit.centreX(), edit.centreZ(), edit.scale(), lines, hidden, 0));
    }

    /** A client asks which tracks a screen showing some lines shows; it is answered if its answer is not the one now. */
    public static void onViewRequest(MapViewRequestPayload request, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
            || !VIEW_LIMIT.allow(player.getUUID(), player.server.overworld().getGameTime()))
            return;
        MapViewPayload view = view(player.server, request.lines());
        if (view.revision() != request.revision())
            PacketDistributor.sendToPlayer(player, view);
    }

    /** A client asks what the signals of its dimension show and where the trains are. */
    public static void onLiveRequest(MapLiveRequestPayload request, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
            || !LIVE_LIMIT.allow(player.getUUID(), player.server.overworld().getGameTime()))
            return;
        LiveMap live = CreateMap.live(Create.RAILWAYS.trackNetworks.values(), Create.RAILWAYS.trains.values(), player.server::getLevel,
            player.level().dimension().location().toString());
        PacketDistributor.sendToPlayer(player, new MapLivePayload(live));
    }

    /** Forgets a player who has left. */
    static void forget(ServerPlayer player) {
        VIEW_LIMIT.forget(player.getUUID());
        LIVE_LIMIT.forget(player.getUUID());
    }

    /** Which tracks a screen showing {@code lines} shows now. */
    static MapViewPayload view(MinecraftServer server, List<String> lines) {
        OperationData data = OperationData.get(server);
        Timetable timetable = data.timetable();
        long print = CreateNetwork.fingerprint(Create.RAILWAYS.trackNetworks.values());
        // Never 0, which a client that has no answer says it has.
        long revision = (print * 31 + data.revision()) * 31 + (timetable == null ? 0 : 1) | 1;
        List<Object> key = List.of(List.copyOf(lines), revision);
        MapViewPayload known = VIEWS.get(key);
        if (known != null)
            return known;
        MapViewPayload view;
        if (timetable == null)
            view = new MapViewPayload(lines, revision, true, List.of());
        else if (lines.isEmpty())
            view = new MapViewPayload(lines, revision, true, timetable.mapHidden());
        else
            view = new MapViewPayload(lines, revision, false, shown(timetable, lines));
        VIEWS.put(key, view);
        return view;
    }

    /** The keys of the tracks the lines' trains run along, with those the lines add and without those they take away and the timetable hides. */
    private static List<String> shown(Timetable timetable, List<String> lines) {
        Set<String> wanted = Set.copyOf(lines);
        Set<String> stations = new LinkedHashSet<>();
        for (Timetable.Line line : timetable.lines())
            if (wanted.contains(line.id()))
                stations.addAll(line.stations());
        var graphs = Create.RAILWAYS.trackNetworks.values();
        CreateNetwork.All all = CreateNetwork.readAll(graphs);
        CreateMap.Read read = CreateMap.read(graphs, List.of(), null);
        NetworkMap map = NetworkMap.of(read.map(), all.edges(), read.trackOf(), read.backward());
        List<MapSelection.LegTracks> legs = new ArrayList<>();
        for (Leg leg : all.network().legs(stations))
            legs.add(new MapSelection.LegTracks(leg.from(), leg.to(), map.tracks(leg)));
        List<String> keys = read.map().tracks().stream().map(track -> track.key()).toList();
        List<String> shown = new ArrayList<>();
        for (int track : MapSelection.visible(keys, legs, timetable, lines))
            shown.add(keys.get(track));
        return shown;
    }
}
