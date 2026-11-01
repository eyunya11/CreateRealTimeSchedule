package io.github.eyunya11.createrealtimeschedule.map;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import io.github.eyunya11.createrealtimeschedule.json.JsonOutput;
import io.github.eyunya11.createrealtimeschedule.json.JsonOutput.ListNode;
import io.github.eyunya11.createrealtimeschedule.json.JsonOutput.MapNode;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Carriage;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Lamp;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.SignalMark;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.SignalSide;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.SpeedMark;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Spot;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.StationMark;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Track;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.TrainMark;

/**
 * Writes a {@link TrackMap} as the {@code map} member of the network JSON (version 3), which the
 * editor draws. The format is described in the proposal, in the section on the map.
 *
 * <p>Places are given to a tenth of a block, and directions to a hundredth. A track's points are a
 * flat list, X and Z in turn. The signals' ids are left out: the editor shows the signals as they were
 * when the map was written, and has nothing to match them by later.
 */
public final class TrackMapJson {

    private TrackMapJson() {}

    public static MapNode write(TrackMap map) {
        MapNode node = new MapNode();
        node.put("dimensions", JsonOutput.inline(JsonOutput.texts(map.dimensions())));

        ListNode tracks = new ListNode();
        for (Track track : map.tracks()) {
            ListNode points = new ListNode();
            for (Spot point : track.points())
                points.add(place(point.x())).add(place(point.z()));
            tracks.add(JsonOutput.inline(new MapNode()
                .put("key", JsonOutput.text(track.key()))
                .put("dimension", whole(track.dimension()))
                .put("points", JsonOutput.inline(points))));
        }
        node.put("tracks", tracks);

        ListNode stations = new ListNode();
        for (StationMark station : map.stations())
            stations.add(JsonOutput.inline(new MapNode()
                .put("name", JsonOutput.text(station.name()))
                .put("track", whole(station.track()))
                .put("x", place(station.at().x()))
                .put("z", place(station.at().z()))));
        node.put("stations", stations);

        ListNode signals = new ListNode();
        for (SignalMark signal : map.signals()) {
            ListNode sides = new ListNode();
            for (SignalSide side : signal.sides()) {
                ListNode lamps = new ListNode();
                for (Lamp lamp : side.lamps())
                    lamps.add(JsonOutput.text(lamp.letter()));
                sides.add(JsonOutput.inline(new MapNode()
                    .put("dx", direction(side.direction().x()))
                    .put("dz", direction(side.direction().z()))
                    .put("kind", JsonOutput.text(side.kind()))
                    .put("lamps", JsonOutput.inline(lamps))));
            }
            signals.add(JsonOutput.inline(new MapNode()
                .put("track", whole(signal.track()))
                .put("x", place(signal.at().x()))
                .put("z", place(signal.at().z()))
                .put("sides", JsonOutput.inline(sides))));
        }
        node.put("signals", signals);

        ListNode limits = new ListNode();
        for (SpeedMark sign : map.speedLimits()) {
            MapNode entry = new MapNode()
                .put("track", whole(sign.track()))
                .put("x", place(sign.at().x()))
                .put("z", place(sign.at().z()))
                .put("dx", direction(sign.direction().x()))
                .put("dz", direction(sign.direction().z()));
            if (sign.release())
                entry.put("release", JsonOutput.raw("true"));
            else
                entry.put("kmh", whole(sign.kmh()));
            limits.add(JsonOutput.inline(entry));
        }
        node.put("speedLimits", limits);

        ListNode trains = new ListNode();
        for (TrainMark train : map.trains()) {
            ListNode carriages = new ListNode();
            for (Carriage carriage : train.carriages())
                carriages.add(JsonOutput.inline(new MapNode()
                    .put("points", JsonOutput.inline(new ListNode()
                        .add(place(carriage.front().x())).add(place(carriage.front().z()))
                        .add(place(carriage.back().x())).add(place(carriage.back().z()))))
                    .put("tracks", JsonOutput.inline(new ListNode()
                        .add(whole(carriage.frontTrack())).add(whole(carriage.backTrack()))))));
            trains.add(JsonOutput.inline(new MapNode()
                .put("name", JsonOutput.text(train.name()))
                .put("carriages", JsonOutput.inline(carriages))));
        }
        node.put("trains", trains);
        return node;
    }

    /** The indexes of {@code tracks}, as a leg of the network JSON lists the tracks it runs along. */
    public static JsonOutput.Leaf indexes(List<Integer> tracks) {
        ListNode list = new ListNode();
        for (int track : tracks)
            list.add(whole(track));
        return JsonOutput.inline(list);
    }

    private static JsonOutput.Leaf whole(int value) {
        return JsonOutput.raw(Integer.toString(value));
    }

    private static JsonOutput.Leaf place(double value) {
        return rounded(value, 1);
    }

    private static JsonOutput.Leaf direction(double value) {
        return rounded(value, 2);
    }

    /** A number to {@code digits} places, without the zeros after the point that say nothing. */
    private static JsonOutput.Leaf rounded(double value, int digits) {
        BigDecimal number = BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_UP).stripTrailingZeros();
        if (number.signum() == 0)
            return JsonOutput.raw("0");
        return JsonOutput.raw(number.toPlainString());
    }
}
