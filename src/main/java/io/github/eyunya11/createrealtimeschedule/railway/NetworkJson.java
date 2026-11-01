package io.github.eyunya11.createrealtimeschedule.railway;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.OptionalInt;

import org.jetbrains.annotations.Nullable;

import io.github.eyunya11.createrealtimeschedule.json.JsonOutput;
import io.github.eyunya11.createrealtimeschedule.json.JsonOutput.ListNode;
import io.github.eyunya11.createrealtimeschedule.json.JsonOutput.MapNode;
import io.github.eyunya11.createrealtimeschedule.map.TrackMapJson;
import io.github.eyunya11.createrealtimeschedule.travel.SpeedSign;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpec;

/**
 * Writes what the game's track network has to say about running times as JSON, for the editor and
 * the diagram. The format is described in the proposal, in the section on the network export. It is
 * only written here; nothing in the mod reads it back.
 *
 * <p>Distances are given to a hundredth of a block, but the ticks are worked out from the distance
 * before it was rounded. The ticks are for a train that starts under no speed limit; one that comes
 * in under the limit of an earlier sign takes longer until the first sign of the leg.
 *
 * <p>With a map of the track, it is written as version 3: the map, and for each leg the tracks of the
 * map it runs along, by their indexes. Without one, it is version 2, as before there was a map.
 */
public final class NetworkJson {

    /** The {@code format} member of a network in JSON. */
    public static final String FORMAT = "createrealtimeschedule.network";

    /**
     * The newest version of the JSON written. Version 2 added the signs of a leg, and version 3 the
     * map of the track. A network is written in the oldest version that holds it.
     */
    public static final int VERSION = 3;

    private NetworkJson() {}

    /**
     * @param names    the stations, each once
     * @param legs     the legs between them
     * @param spec     the train the running times are for
     * @param throttle the share of the top speed the train is held to, from 0 to 1
     */
    public static String write(List<String> names, List<Leg> legs, TrainSpec spec, double throttle) {
        return write(names, legs, spec, throttle, null);
    }

    /**
     * {@link #write(List, List, TrainSpec, double)} with the map of the track, if there is one.
     *
     * @param map the map, and which track of it each edge of the legs is; null for none
     */
    public static String write(List<String> names, List<Leg> legs, TrainSpec spec, double throttle, @Nullable NetworkMap map) {
        MapNode root = new MapNode();
        root.put("format", JsonOutput.text(FORMAT));
        root.put("version", JsonOutput.raw(Integer.toString(map != null ? 3 : 2)));
        root.put("train", new MapNode()
            .put("topSpeed", number(spec.topSpeed()))
            .put("turnSpeed", number(spec.turnSpeed()))
            .put("acceleration", number(spec.acceleration()))
            .put("throttle", number(throttle)));
        root.put("stations", JsonOutput.texts(names));

        ListNode list = new ListNode();
        for (Leg leg : legs) {
            MapNode node = new MapNode();
            node.put("from", JsonOutput.text(leg.from()));
            node.put("to", JsonOutput.text(leg.to()));
            node.put("distance", number(leg.distance()));
            if (!leg.via().isEmpty())
                node.put("via", JsonOutput.inline(JsonOutput.texts(leg.via())));
            if (!leg.curves().isEmpty()) {
                ListNode curves = new ListNode();
                for (Leg.Curve curve : leg.curves())
                    curves.add(JsonOutput.inline(new ListNode().add(number(curve.start())).add(number(curve.end()))));
                node.put("curves", JsonOutput.inline(curves));
            }
            if (!leg.signs().isEmpty()) {
                ListNode signs = new ListNode();
                for (SpeedSign sign : leg.signs()) {
                    MapNode entry = new MapNode().put("at", number(sign.position()));
                    if (sign.release())
                        entry.put("release", JsonOutput.raw("true"));
                    else
                        entry.put("kmh", JsonOutput.raw(Integer.toString(sign.kmh())));
                    signs.add(JsonOutput.inline(entry));
                }
                node.put("signs", JsonOutput.inline(signs));
            }
            OptionalInt ticks = leg.ticks(spec, throttle);
            if (ticks.isPresent())
                node.put("ticks", JsonOutput.raw(Integer.toString(ticks.getAsInt())));
            if (map != null)
                node.put("tracks", TrackMapJson.indexes(map.tracks(leg)));
            list.add(node);
        }
        root.put("legs", list);
        if (map != null)
            root.put("map", TrackMapJson.write(map.map()));
        return JsonOutput.render(root);
    }

    /** A number to a hundredth, without the zeros after the point that say nothing. */
    private static JsonOutput.Leaf number(double value) {
        BigDecimal rounded = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        if (rounded.signum() == 0)
            return JsonOutput.raw("0");
        return JsonOutput.raw(rounded.toPlainString());
    }
}
