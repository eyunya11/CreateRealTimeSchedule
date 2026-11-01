package io.github.eyunya11.createrealtimeschedule.railway;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import io.github.eyunya11.createrealtimeschedule.json.JsonOutput;
import io.github.eyunya11.createrealtimeschedule.json.JsonOutput.ListNode;
import io.github.eyunya11.createrealtimeschedule.json.JsonOutput.MapNode;
import io.github.eyunya11.createrealtimeschedule.tag.TrackTags;
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
 * <p>A network with track tags, or tag blocks, is written as version 3, which adds them: the tags, what
 * is wrong with the blocks' sections, the platforms, the legs for a train held to each tag (and to a
 * tag the world has none of), and where a train standing at each platform comes to on each tag. One
 * with neither is written as version 2, as it was before them.
 */
public final class NetworkJson {

    /** The {@code format} member of a network in JSON. */
    public static final String FORMAT = "createrealtimeschedule.network";

    /** The newest version of the JSON written. Version 2 added the signs of a leg, and version 3 the track tags. */
    public static final int VERSION = 3;

    private NetworkJson() {}

    /**
     * @param names    the stations, each once
     * @param legs     the legs between them
     * @param spec     the train the running times are for
     * @param throttle the share of the top speed the train is held to, from 0 to 1
     */
    public static String write(List<String> names, List<Leg> legs, TrainSpec spec, double throttle) {
        MapNode root = head(2, names, spec, throttle);
        ListNode list = new ListNode();
        for (Leg leg : legs)
            list.add(leg(leg, "", spec, throttle));
        root.put("legs", list);
        return JsonOutput.render(root);
    }

    /**
     * {@code network} with the world's track tags {@code tags}: as version 3 if there are tags or tag
     * blocks, and else as {@link #write(List, List, TrainSpec, double)} writes it.
     */
    public static String write(RailNetwork network, TrackTags tags, TrainSpec spec, double throttle) {
        List<String> names = network.names();
        if (tags.names().isEmpty() && network.markers().isEmpty())
            return write(names, network.legs(), spec, throttle);
        // Each tag by its name, and then a tag the world has none of, by null.
        Map<String, OptionalInt> held = new LinkedHashMap<>();
        held.put("", OptionalInt.empty());
        for (Map.Entry<Integer, String> tag : tags.names().entrySet())
            held.putIfAbsent(tag.getValue(), OptionalInt.of(tag.getKey()));
        held.put(null, OptionalInt.of(TrackTags.UNKNOWN));

        MapNode root = head(3, names, spec, throttle);
        root.put("tags", JsonOutput.texts(new ArrayList<>(tags.names().values())));
        ListNode problems = new ListNode();
        List<RailNetwork.Marker> markers = network.markers();
        for (TagSections.Problem problem : network.tagSections().problems()) {
            MapNode node = new MapNode();
            node.put("problem", JsonOutput.text(problem.kind() == TagSections.Kind.JUNCTION ? "junction" : "mixed"));
            node.put("block", place(markers.get(problem.marker()).block()));
            if (problem.other() >= 0)
                node.put("other", place(markers.get(problem.other()).block()));
            if (problem.node() != null)
                node.put("node", place(problem.node()));
            problems.add(JsonOutput.inline(node));
        }
        root.put("tagProblems", problems);
        ListNode platforms = new ListNode();
        for (RailNetwork.Station station : network.stations())
            platforms.add(JsonOutput.inline(new MapNode().put("station", JsonOutput.text(station.name()))));
        root.put("platforms", platforms);

        ListNode legs = new ListNode();
        ListNode ways = new ListNode();
        for (Map.Entry<String, OptionalInt> tag : held.entrySet()) {
            for (Leg leg : network.legs(names, tag.getValue()))
                legs.add(leg(leg, tag.getKey(), spec, throttle));
            Map<Integer, MapNode> from = new LinkedHashMap<>();
            for (RailNetwork.Way way : network.ways(names, tag.getValue()))
                from.computeIfAbsent(way.from(), f -> new MapNode()).put(way.to(), JsonOutput.raw(Integer.toString(way.station())));
            for (Map.Entry<Integer, MapNode> each : from.entrySet()) {
                MapNode node = new MapNode().put("from", JsonOutput.raw(Integer.toString(each.getKey())));
                tagged(node, tag.getKey());
                ways.add(JsonOutput.inline(node.put("to", JsonOutput.inline(each.getValue()))));
            }
        }
        root.put("legs", legs);
        root.put("ways", ways);
        return JsonOutput.render(root);
    }

    /** The members a network starts with, of version {@code version}. */
    private static MapNode head(int version, List<String> names, TrainSpec spec, double throttle) {
        MapNode root = new MapNode();
        root.put("format", JsonOutput.text(FORMAT));
        root.put("version", JsonOutput.raw(Integer.toString(version)));
        root.put("train", new MapNode()
            .put("topSpeed", number(spec.topSpeed()))
            .put("turnSpeed", number(spec.turnSpeed()))
            .put("acceleration", number(spec.acceleration()))
            .put("throttle", number(throttle)));
        root.put("stations", JsonOutput.texts(names));
        return root;
    }

    /** Says which tag {@code node} is for: none for {@code ""}, the name, or for null a tag the world has none of. */
    private static void tagged(MapNode node, String tag) {
        if (tag == null)
            node.put("otherTag", JsonOutput.raw("true"));
        else if (!tag.isEmpty())
            node.put("tag", JsonOutput.text(tag));
    }

    private static JsonOutput.Leaf place(RailNetwork.Place place) {
        return JsonOutput.inline(new ListNode().add(number(place.x())).add(number(place.y())).add(number(place.z())));
    }

    /** A leg, for a train held to {@code tag} as {@link #tagged} says it. */
    private static MapNode leg(Leg leg, String tag, TrainSpec spec, double throttle) {
        MapNode node = new MapNode();
        node.put("from", JsonOutput.text(leg.from()));
        node.put("to", JsonOutput.text(leg.to()));
        tagged(node, tag);
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
        return node;
    }

    /** A number to a hundredth, without the zeros after the point that say nothing. */
    private static JsonOutput.Leaf number(double value) {
        BigDecimal rounded = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        if (rounded.signum() == 0)
            return JsonOutput.raw("0");
        return JsonOutput.raw(rounded.toPlainString());
    }
}
