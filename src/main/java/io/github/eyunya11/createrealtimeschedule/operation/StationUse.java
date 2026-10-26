package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.diagram.LineAxis;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Line;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Stop;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Train;

/**
 * How the timetable's duties use a station over a while: whether a train runs through it without
 * stopping, and how many sets stand at it at once. Before a set is held at a station for longer than
 * the timetable has it stand there, the recovery asks this, so that the held set blocks no train that
 * runs through the station, and leaves a platform for every set that is to stand there meanwhile.
 *
 * <p>It goes by the timetable alone: a train of no duty, or one that follows a schedule of Create's,
 * is not in it. It has no Minecraft types in it so that it can be tested without a game.
 */
public final class StationUse {

    /** How many service days back a duty's times can reach a clock value: they run on to 47:59. */
    private static final int DAYS_BACK = 2;

    private StationUse() {}

    /**
     * Whether a train of the timetable's duties runs through {@code station} without stopping at some
     * time from {@code from} to {@code to} (clock values), by the timetable. The duty {@code duty} on
     * service {@code day}, whose set is the one to be held, is left out.
     *
     * @param calls the calls of each duty, by its id
     * @param axes  the axis of each line, by its id, for which way round a loop line a train runs; a
     *              loop line with none goes by the way that passes fewer stations
     */
    public static boolean passedBy(Timetable timetable, Map<String, List<Call>> calls, Map<String, LineAxis> axes, String duty, long day,
                                   String station, long from, long to) {
        for (Map.Entry<String, List<Call>> entry : calls.entrySet()) {
            List<Call> dutyCalls = entry.getValue();
            for (long serviceDay = RealClock.day(from) - DAYS_BACK; serviceDay <= RealClock.day(to); serviceDay++) {
                if (entry.getKey().equals(duty) && serviceDay == day)
                    continue;
                for (int way = 0; way < dutyCalls.size() - 1; way++) {
                    Call leaving = dutyCalls.get(way);
                    Call coming = dutyCalls.get(way + 1);
                    if (DutyPlan.at(serviceDay, leaving.depart()) > to || DutyPlan.at(serviceDay, coming.comesIn()) < from)
                        continue;
                    Train train = timetable.train(leaving.leavingTrain()).orElse(null);
                    if (train != null && runsThrough(timetable, axes, train, leaving.station(), coming.station(), station))
                        return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether {@code train}, going from its stop at {@code from} on to its next stop at {@code to},
     * passes {@code station} of its line on the way: the way the diagram draws it ({@link LineAxis#hops}),
     * through the rows between its ends, and across the first station of a loop line.
     */
    static boolean runsThrough(Timetable timetable, Map<String, LineAxis> axes, Train train, String from, String to, String station) {
        Line line = timetable.line(train.line()).orElse(null);
        if (line == null || station.equals(from) || station.equals(to))
            return false;
        LineAxis axis = axes.get(line.id());
        if (axis == null)
            axis = LineAxis.of(line, List.of());
        Boolean shown = axis.down(from, to);
        boolean down = shown != null ? shown : axis.down(train.stops().stream().map(Stop::station).toList());
        List<LineAxis.Hop> hops = axis.hops(from, to, down);
        for (int k = 0; k < hops.size(); k++) {
            LineAxis.Hop hop = hops.get(k);
            if (k > 0 && axis.stations().get(hop.fromRow()).equals(station))
                return true;
            for (int row = Math.min(hop.fromRow(), hop.toRow()) + 1; row < Math.max(hop.fromRow(), hop.toRow()); row++)
                if (axis.stations().get(row).equals(station))
                    return true;
        }
        return false;
    }

    /**
     * The most sets of the timetable's duties that stand at {@code station} at once at some time from
     * {@code from} to {@code to}, by the timetable, counting only those that come in after {@code now}:
     * one that is there already is a train that stands there. A duty's set stands at its depot from
     * {@code lead} ticks before it leaves, and from when it comes back on. The duty {@code duty} on
     * service {@code day} is left out.
     */
    public static int mostStanding(Map<String, List<Call>> calls, String duty, long day, String station, long from, long to, long now,
                                   long lead) {
        List<long[]> stays = new ArrayList<>();
        for (Map.Entry<String, List<Call>> entry : calls.entrySet()) {
            List<Call> dutyCalls = entry.getValue();
            int last = dutyCalls.size() - 1;
            for (long serviceDay = RealClock.day(from) - DAYS_BACK; serviceDay <= RealClock.day(to); serviceDay++) {
                if (entry.getKey().equals(duty) && serviceDay == day)
                    continue;
                for (int index = 0; index <= last; index++) {
                    Call call = dutyCalls.get(index);
                    if (!call.station().equals(station))
                        continue;
                    long in;
                    long out;
                    if (index == 0) {
                        out = DutyPlan.at(serviceDay, call.depart());
                        in = out - lead;
                    } else if (index == last) {
                        in = DutyPlan.at(serviceDay, call.arrive());
                        out = Long.MAX_VALUE;
                    } else {
                        in = DutyPlan.at(serviceDay, call.comesIn());
                        out = DutyPlan.at(serviceDay, call.depart());
                    }
                    if (in > now && in <= to && out >= from)
                        stays.add(new long[] {in, out});
                }
            }
        }
        // At most when one of them comes in, or at the start of the while.
        int most = 0;
        for (long[] stay : stays) {
            long at = Math.max(stay[0], from);
            int standing = 0;
            for (long[] other : stays)
                if (other[0] <= at && at <= other[1])
                    standing++;
            most = Math.max(most, standing);
        }
        return most;
    }
}
