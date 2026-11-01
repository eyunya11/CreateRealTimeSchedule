package io.github.eyunya11.createrealtimeschedule.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.CreateClient;

import io.github.eyunya11.createrealtimeschedule.map.LiveMap;
import io.github.eyunya11.createrealtimeschedule.map.MapProjection;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap;
import io.github.eyunya11.createrealtimeschedule.network.MapLivePayload;
import io.github.eyunya11.createrealtimeschedule.network.MapLiveRequestPayload;
import io.github.eyunya11.createrealtimeschedule.network.MapScreenOpenPayload;
import io.github.eyunya11.createrealtimeschedule.network.MapViewPayload;
import io.github.eyunya11.createrealtimeschedule.network.MapViewRequestPayload;
import io.github.eyunya11.createrealtimeschedule.railway.CreateMap;
import io.github.eyunya11.createrealtimeschedule.screen.MapScreenBlockEntity;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * What the client's map screens draw. The track, the stations and the speed-limit signs are read from
 * the track graphs Create sends every client ({@link CreateMap}), again a second after they change.
 * Which tracks a screen showing some lines shows, what the signals show and where the trains are, the
 * server is asked for while a screen is drawn ({@code MapScreenService}), and not while none is.
 */
public final class MapClient {

    /** How long after a screen was last drawn its requests go on, in ticks. */
    private static final int KEEP_ASKING = 40;
    private static final int VIEW_EVERY = 40;
    private static final int LIVE_EVERY = 10;

    private static TrackMap map = TrackMap.EMPTY;
    private static Map<String, Integer> indexOf = Map.of();
    /** Goes up each time the map is read again, so that what is worked out from it can be kept until then. */
    private static int version;
    private static int seenRailways = Integer.MIN_VALUE;
    private static int changedAt = -1;

    private static final Map<List<String>, MapViewPayload> VIEWS = new HashMap<>();
    /** The lines of each screen drawn lately, with the tick it was last drawn in. */
    private static final Map<List<String>, Integer> WANTED = new HashMap<>();
    private static LiveMap live = LiveMap.EMPTY;
    private static int liveWantedAt = Integer.MIN_VALUE;
    private static int ticks;

    private MapClient() {}

    public static TrackMap map() {
        return map;
    }

    public static int version() {
        return version;
    }

    public static LiveMap live() {
        return live;
    }

    /** The index of the track of a key on {@link #map()}, or -1. */
    public static int indexOf(String key) {
        return indexOf.getOrDefault(key, -1);
    }

    /** The index of a dimension on {@link #map()}, or -1 if it has no track. */
    public static int dimension(String name) {
        return map.dimensions().indexOf(name);
    }

    /** Says that a screen showing {@code lines} is being drawn, so that what it needs is asked for. */
    public static void drawn(List<String> lines) {
        WANTED.put(lines, ticks);
        liveWantedAt = ticks;
    }

    /**
     * The answer to which tracks a screen showing {@code lines} shows, or null if there is none yet; a
     * screen without one shows all the track.
     */
    public static @Nullable MapViewPayload view(List<String> lines) {
        return VIEWS.get(lines);
    }

    /** The box round the tracks of a dimension, or null if it has none. */
    public static MapProjection.@Nullable Box bounds(int dimension) {
        double minX = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (TrackMap.Track track : map.tracks())
            if (track.dimension() == dimension)
                for (TrackMap.Spot point : track.points()) {
                    minX = Math.min(minX, point.x());
                    minZ = Math.min(minZ, point.z());
                    maxX = Math.max(maxX, point.x());
                    maxZ = Math.max(maxZ, point.z());
                }
        return minX > maxX ? null : new MapProjection.Box(minX, minZ, maxX, maxZ);
    }

    static void tick() {
        ticks++;
        if (Minecraft.getInstance().level == null)
            return;
        if (CreateClient.RAILWAYS.version != seenRailways) {
            seenRailways = CreateClient.RAILWAYS.version;
            changedAt = ticks;
        }
        // A second after the track last changed, so that laying track does not read it every tick.
        if (changedAt >= 0 && ticks - changedAt >= 20) {
            changedAt = -1;
            read();
        }
        WANTED.values().removeIf(at -> ticks - at > KEEP_ASKING);
        VIEWS.keySet().retainAll(WANTED.keySet());
        if (ticks % VIEW_EVERY == 0)
            for (List<String> lines : WANTED.keySet()) {
                MapViewPayload known = VIEWS.get(lines);
                PacketDistributor.sendToServer(new MapViewRequestPayload(lines, known == null ? 0 : known.revision()));
            }
        if (ticks % LIVE_EVERY == 0 && ticks - liveWantedAt <= KEEP_ASKING)
            PacketDistributor.sendToServer(MapLiveRequestPayload.INSTANCE);
    }

    private static void read() {
        map = CreateMap.read(CreateClient.RAILWAYS.trackNetworks.values(), List.of(), null).map();
        Map<String, Integer> keys = new HashMap<>();
        for (int i = 0; i < map.tracks().size(); i++)
            keys.put(map.tracks().get(i).key(), i);
        indexOf = keys;
        version++;
    }

    public static void onView(MapViewPayload view) {
        VIEWS.put(view.lines(), view);
    }

    public static void onLive(MapLivePayload payload) {
        live = payload.live();
    }

    public static void onOpen(MapScreenOpenPayload open) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.level.getBlockEntity(open.pos()) instanceof MapScreenBlockEntity entity)
            minecraft.setScreen(new MapScreenEditor(entity, open.lines()));
    }

    /** Which of the map's tracks a screen showing {@code lines} shows: by their indexes. */
    public static Set<Integer> shown(List<String> lines) {
        MapViewPayload view = VIEWS.get(lines);
        Set<Integer> shown = new HashSet<>();
        if (view == null || view.except()) {
            for (int i = 0; i < map.tracks().size(); i++)
                shown.add(i);
            if (view != null)
                for (String key : view.keys())
                    shown.remove(indexOf(key));
        } else {
            for (String key : view.keys()) {
                int index = indexOf(key);
                if (index >= 0)
                    shown.add(index);
            }
        }
        return shown;
    }

    static void reset() {
        map = TrackMap.EMPTY;
        indexOf = Map.of();
        version++;
        seenRailways = Integer.MIN_VALUE;
        changedAt = -1;
        VIEWS.clear();
        WANTED.clear();
        live = LiveMap.EMPTY;
        liveWantedAt = Integer.MIN_VALUE;
    }
}
