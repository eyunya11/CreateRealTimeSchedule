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
            list.add(leg(leg, Held.NONE, spec, throttle));
        root.put("legs", list);
        return JsonOutput.render(root);
    }

    /** {@link #write(RailNetwork, RailNetwork.Routes, TrackTags, TrainSpec, double)} with the routes of no tag worked out here. */
    public static String write(RailNetwork network, TrackTags tags, TrainSpec spec, double throttle) {
        return write(network, network.routes(network.names(), OptionalInt.empty()), tags, spec, throttle);
    }

    /**
     * {@code network} with the world's track tags {@code tags}: as version 3 if there are tags or tag
     * blocks, and else as {@link #write(List, List, TrainSpec, double)} writes it.
     *
     * @param untagged the legs and ways of {@code network} for a train held to no tag, between all its names
     */
    public static String write(RailNetwork network, RailNetwork.Routes untagged, TrackTags tags, TrainSpec spec, double throttle) {
        List<String> names = network.names();
        if (tags.names().isEmpty() && network.markers().isEmpty())
            return write(names, untagged.legs(), spec, throttle);
        List<Held> held = new ArrayList<>();
        held.add(Held.NONE);
        for (Map.Entry<Integer, String> tag : tags.names().entrySet())
            held.add(new Held(tag.getValue(), OptionalInt.of(tag.getKey()), false));
        held.add(new Held("", OptionalInt.of(TrackTags.UNKNOWN), true));

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
        for (Held tag : held) {
            RailNetwork.Routes routes = tag == Held.NONE ? untagged : network.routes(names, tag.id());
            for (Leg leg : routes.legs())
                legs.add(leg(leg, tag, spec, throttle));
            Map<Integer, MapNode> from = new LinkedHashMap<>();
            for (RailNetwork.Way way : routes.ways())
                from.computeIfAbsent(way.from(), f -> new MapNode()).put(way.to(), JsonOutput.raw(Integer.toString(way.station())));
            for (Map.Entry<Integer, MapNode> each : from.entrySet()) {
                MapNode node = new MapNode().put("from", JsonOutput.raw(Integer.toString(each.getKey())));
                tagged(node, tag);
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

    /**
     * The track tag a train is held to, as the network writes it.
     *
     * @param name  the tag's name; empty for none, and for a tag the world has none of
     * @param id    the id the search holds the train to ({@link TrackTags#held})
     * @param other whether it is a tag the world has none of, written {@code "otherTag": true}
     */
    private record Held(String name, OptionalInt id, boolean other) {
        /** No tag. */
        static final Held NONE = new Held("", OptionalInt.empty(), false);
    }

    /** Says which tag {@code node} is for: nothing for no tag, the tag's name, or {@code otherTag}. */
    private static void tagged(MapNode node, Held tag) {
        if (tag.other())
            node.put("otherTag", JsonOutput.raw("true"));
        else if (!tag.name().isEmpty())
            node.put("tag", JsonOutput.text(tag.name()));
    }

    private static JsonOutput.Leaf place(RailNetwork.Place place) {
        return JsonOutput.inline(new ListNode().add(number(place.x())).add(number(place.y())).add(number(place.z())));
    }

    /** A leg, for a train held to {@code tag}. */
    private static MapNode leg(Leg leg, Held tag, TrainSpec spec, double throttle) {
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
