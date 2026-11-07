package io.github.eyunya11.createrealtimeschedule.railway;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import org.jetbrains.annotations.Nullable;

import io.github.eyunya11.createrealtimeschedule.json.JsonOutput;
import io.github.eyunya11.createrealtimeschedule.json.JsonOutput.ListNode;
import io.github.eyunya11.createrealtimeschedule.json.JsonOutput.MapNode;
import io.github.eyunya11.createrealtimeschedule.map.TrackMapJson;
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
 * with neither is written as version 2, as it was before them. With a map of the track, it is written
 * as version 4, which has all that version 3 has and adds the map, with the stretches of each of its
 * tracks that tags are on, the colours of the tags, and for each leg the tracks of the map it runs
 * along, by their indexes. A network with signals is written as version 5, which has all that version 3
 * has, the map if there is one, and adds the signal blocks: the signals between them, the blocks whose
 * tracks cross, the speeds of Create Train Next's light signals, and for each leg the block it starts in
 * and the signals it passes.
 */
public final class NetworkJson {

    /** The {@code format} member of a network in JSON. */
    public static final String FORMAT = "createrealtimeschedule.network";

    /**
     * The newest version of the JSON written. Version 2 added the signs of a leg, version 3 the track
     * tags, version 4 the map of the track, and version 5 the signal blocks. A network is written in the
     * oldest version that holds it.
     */
    public static final int VERSION = 5;

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
            list.add(leg(leg, Held.NONE, spec, throttle, null, false));
        root.put("legs", list);
        return JsonOutput.render(root);
    }

    /** {@link #write(RailNetwork, RailNetwork.Routes, TrackTags, TrainSpec, double)} with the routes of no tag worked out here. */
    public static String write(RailNetwork network, TrackTags tags, TrainSpec spec, double throttle) {
        return write(network, network.routes(network.names(), OptionalInt.empty()), tags, spec, throttle);
    }

    /** {@link #write(RailNetwork, RailNetwork.Routes, TrackTags, TrainSpec, double, NetworkMap)} with the routes of no tag worked out here. */
    public static String write(RailNetwork network, TrackTags tags, TrainSpec spec, double throttle, @Nullable NetworkMap map) {
        return write(network, network.routes(network.names(), OptionalInt.empty()), tags, spec, throttle, map);
    }

    /**
     * {@code network} with the world's track tags {@code tags}: as version 3 if there are tags or tag
     * blocks, and else as {@link #write(List, List, TrainSpec, double)} writes it.
     *
     * @param untagged the legs and ways of {@code network} for a train held to no tag, between all its names
     */
    public static String write(RailNetwork network, RailNetwork.Routes untagged, TrackTags tags, TrainSpec spec, double throttle) {
        return write(network, untagged, tags, spec, throttle, null);
    }

    /**
     * {@link #write(RailNetwork, RailNetwork.Routes, TrackTags, TrainSpec, double)} with the map of the
     * track, if there is one: as version 4 if there is, with all that version 3 has even if there are no
     * tags; and as version 5, with the map or without, if the network has signals.
     *
     * @param map the map, and which track of it each edge of the legs is; null for none
     */
    public static String write(RailNetwork network, RailNetwork.Routes untagged, TrackTags tags, TrainSpec spec, double throttle,
        @Nullable NetworkMap map) {
        List<String> names = network.names();
        SignalBlocks blocks = network.blocks();
        boolean signals = !blocks.signals().isEmpty();
        if (map == null && tags.names().isEmpty() && network.markers().isEmpty() && !signals)
            return write(names, untagged.legs(), spec, throttle);
        List<Held> held = new ArrayList<>();
        held.add(Held.NONE);
        for (Map.Entry<Integer, String> tag : tags.names().entrySet())
            held.add(new Held(tag.getValue(), OptionalInt.of(tag.getKey()), false));
        held.add(new Held("", OptionalInt.of(TrackTags.UNKNOWN), true));

        MapNode root = head(signals ? 5 : map != null ? 4 : 3, names, spec, throttle);
        root.put("tags", JsonOutput.texts(new ArrayList<>(tags.names().values())));
        if (map != null) {
            MapNode colours = new MapNode();
            for (Map.Entry<Integer, String> tag : tags.names().entrySet())
                colours.put(tag.getValue(), JsonOutput.text(tags.colour(tag.getKey())));
            root.put("tagColours", JsonOutput.inline(colours));
        }
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
        if (signals)
            putBlocks(root, blocks);

        ListNode legs = new ListNode();
        ListNode ways = new ListNode();
        for (Held tag : held) {
            RailNetwork.Routes routes = tag == Held.NONE ? untagged : network.routes(names, tag.id());
            for (Leg leg : routes.legs())
                legs.add(leg(leg, tag, spec, throttle, map, signals));
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
        if (map != null)
            root.put("map", TrackMapJson.write(map.map(), map.tagPieces(network, tags)));
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

    /** The signal blocks of version 5: how many there are, those that cross, the signals, and the speeds of the light signals. */
    private static void putBlocks(MapNode root, SignalBlocks blocks) {
        root.put("blocks", JsonOutput.raw(Integer.toString(blocks.count())));
        ListNode crossings = new ListNode();
        for (List<Integer> pair : blocks.crossings())
            crossings.add(JsonOutput.inline(numbers(pair)));
        root.put("crossings", JsonOutput.inline(crossings));
        ListNode signals = new ListNode();
        for (SignalBlocks.Signal signal : blocks.signals()) {
            MapNode node = new MapNode().put("block", JsonOutput.raw(Integer.toString(signal.block())));
            if (!signal.next().isEmpty())
                node.put("next", JsonOutput.inline(numbers(signal.next())));
            if (!signal.lights().isEmpty())
                node.put("lights", JsonOutput.inline(numbers(signal.lights())));
            if (signal.cross())
                node.put("cross", JsonOutput.raw("true"));
            signals.add(JsonOutput.inline(node));
        }
        root.put("signals", signals);
        SignalBlocks.LightSpeeds speeds = blocks.speeds();
        if (speeds != null)
            root.put("lightSpeeds", JsonOutput.inline(new MapNode()
                .put("restricted", JsonOutput.raw(Integer.toString(speeds.restricted())))
                .put("approach", JsonOutput.raw(Integer.toString(speeds.approach())))
                .put("reduced", JsonOutput.raw(Integer.toString(speeds.reduced())))
                .put("sixProceed", JsonOutput.raw(Integer.toString(speeds.sixProceed())))));
    }

    private static ListNode numbers(List<Integer> numbers) {
        ListNode list = new ListNode();
        for (int number : numbers)
            list.add(JsonOutput.raw(Integer.toString(number)));
        return list;
    }

    /**
     * A leg, for a train held to {@code tag}, with the tracks of {@code map} it runs along if there is one,
     * and with the block it starts in and the signals it passes if {@code signals}.
     */
    private static MapNode leg(Leg leg, Held tag, TrainSpec spec, double throttle, @Nullable NetworkMap map, boolean signals) {
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
        if (map != null)
            node.put("tracks", TrackMapJson.indexes(map.tracks(leg)));
        if (signals) {
            if (leg.block() >= 0)
                node.put("block", JsonOutput.raw(Integer.toString(leg.block())));
            if (!leg.signals().isEmpty()) {
                ListNode passed = new ListNode();
                for (Leg.PassedSignal signal : leg.signals())
                    passed.add(JsonOutput.inline(new MapNode().put("at", number(signal.at()))
                        .put("signal", JsonOutput.raw(Integer.toString(signal.signal())))));
                node.put("signals", JsonOutput.inline(passed));
            }
        }
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
