package io.github.eyunya11.createrealtimeschedule.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.eyunya11.createrealtimeschedule.network.TrackTagListPayload;
import io.github.eyunya11.createrealtimeschedule.network.TrackTagOpenPayload;
import net.minecraft.client.Minecraft;

/** The client's copy of the world's track tags, as the server last sent it, and the opening of the tag screen. */
public final class TrackTagClient {

    private static Map<Integer, String> names = Map.of();

    private TrackTagClient() {}

    public static void onList(TrackTagListPayload payload) {
        Map<Integer, String> received = new LinkedHashMap<>();
        for (TrackTagListPayload.Entry entry : payload.tags())
            received.put(entry.id(), entry.name());
        names = Collections.unmodifiableMap(received);
        if (Minecraft.getInstance().screen instanceof TrackTagScreen screen)
            screen.listChanged();
    }

    public static void onOpen(TrackTagOpenPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof TrackTagScreen screen && screen.pos().equals(payload.pos()))
            screen.update(payload);
        else
            minecraft.setScreen(new TrackTagScreen(payload));
    }

    /** The tags by their ids, in the order they were made. */
    public static Map<Integer, String> names() {
        return names;
    }

    /**
     * The names of the tags {@code ids}, joined with commas; a tag the client does not know of, one
     * deleted meanwhile, is left out.
     */
    public static String names(List<Integer> ids) {
        List<String> known = new ArrayList<>();
        for (int id : ids) {
            String name = names.get(id);
            if (name != null)
                known.add(name);
        }
        return String.join(", ", known);
    }

    static void reset() {
        names = Map.of();
    }
}
