package io.github.eyunya11.createrealtimeschedule.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.eyunya11.createrealtimeschedule.network.TrackTagListPayload;
import io.github.eyunya11.createrealtimeschedule.network.TrackTagOpenPayload;
import io.github.eyunya11.createrealtimeschedule.timetable.TypeColours;
import net.minecraft.client.Minecraft;

/** The client's copy of the world's track tags, as the server last sent it, and the opening of the tag screen. */
public final class TrackTagClient {

    private static Map<Integer, String> names = Map.of();
    private static Map<Integer, String> colours = Map.of();

    private TrackTagClient() {}

    public static void onList(TrackTagListPayload payload) {
        Map<Integer, String> received = new LinkedHashMap<>();
        Map<Integer, String> receivedColours = new LinkedHashMap<>();
        for (TrackTagListPayload.Entry entry : payload.tags()) {
            received.put(entry.id(), entry.name());
            receivedColours.put(entry.id(), entry.colour());
        }
        names = Collections.unmodifiableMap(received);
        colours = Collections.unmodifiableMap(receivedColours);
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

    /** The colours of the tags by their ids, {@code #rrggbb}. */
    public static Map<Integer, String> colours() {
        return colours;
    }

    /**
     * The colours the tags are drawn in, by their names, as ARGB: the server's, or, for one that is not
     * a colour, the one the name picks.
     */
    public static Map<String, Integer> argbs() {
        Map<String, Integer> argbs = new LinkedHashMap<>();
        names.forEach((id, name) -> {
            String colour = colours.get(id);
            argbs.put(name, TypeColours.argb(colour != null && TypeColours.isColour(colour) ? colour : TypeColours.byName(name)));
        });
        return argbs;
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
        colours = Map.of();
    }
}
