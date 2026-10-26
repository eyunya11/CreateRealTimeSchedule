package io.github.eyunya11.createrealtimeschedule.diagram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.operation.Call;
import io.github.eyunya11.createrealtimeschedule.operation.DutyPlan;
import io.github.eyunya11.createrealtimeschedule.operation.DutyRun;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Duty;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Stop;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Train;

/**
 * What a line's diagram draws for one service day: the timetable's trains on the line, as planned,
 * and the duties that ran on it that day, as they really went. Each is a list of straight segments
 * from one (time, position) to another: running between stations they slope, and standing at a
 * station they are level. Times are seconds from 00:00 of the service day, and run on past 24:00;
 * positions are those of the {@link LineAxis}. It has no Minecraft types in it so that it can be
 * tested without a game.
 *
 * @param axis    the line's axis
 * @param day     the service day
 * @param planned the timetable's trains on the line, in the timetable's order
 * @param ran     the duties of the day that ran on the line, each with the part of it on the line
 */
public record DiagramModel(LineAxis axis, long day, List<PlannedTrain> planned, List<RanDuty> ran) {

    /** A straight line from one point of the diagram to another; {@code from} is the earlier. */
    public record Segment(double fromSecond, double fromPosition, double toSecond, double toPosition) {}

    /**
     * A train of the timetable.
     *
     * @param labelSecond   where its number goes: at its first departure
     * @param labelPosition and the position of its first station
     * @param cancelled     whether it does not run on the day, cancelled to get a late set back on time
     */
    public record PlannedTrain(String number, String type, boolean deadhead, List<Segment> segments, double labelSecond,
                               double labelPosition, boolean cancelled) {
        public PlannedTrain {
            segments = List.copyOf(segments);
        }
    }

    /** A duty that ran, or is running, with the segments of it that were on the line. */
    public record RanDuty(DutyTimes times, List<Segment> segments) {
        public RanDuty {
            segments = List.copyOf(segments);
        }
    }

    public DiagramModel {
        planned = List.copyOf(planned);
        ran = List.copyOf(ran);
    }

    /**
     * The diagram of the line of {@code axis} on service {@code day}, at clock {@code now}. A duty that
     * is not in the timetable, or whose times do not fit its calls, is left out. A set standing at a
     * station is drawn standing there until now; one on the way, only as far as where it left.
     */
    public static DiagramModel of(Timetable timetable, LineAxis axis, long day, List<DutyTimes> times, long now) {
        return of(timetable, calls(timetable), axis, day, times, now);
    }

    /** The calls of each duty of {@code timetable}, by its id, leaving out a duty whose trains are not all there. */
    public static Map<String, List<Call>> calls(Timetable timetable) {
        Map<String, List<Call>> calls = new HashMap<>();
        for (Duty duty : timetable.duties()) {
            try {
                calls.put(duty.id(), DutyPlan.calls(timetable, duty));
            } catch (IllegalArgumentException e) {
                // left out, as the operation would not run it
            }
        }
        return calls;
    }

    /** {@link #of(Timetable, LineAxis, long, List, long)}, with the duties' {@code calls} worked out already, as by {@link #calls}. */
    public static DiagramModel of(Timetable timetable, Map<String, List<Call>> calls, LineAxis axis, long day, List<DutyTimes> times,
                                  long now) {
        Set<String> cancelled = new HashSet<>();
        for (DutyTimes duty : times) {
            List<Call> dutyCalls = calls.get(duty.duty());
            if (duty.day() == day && dutyCalls != null && dutyCalls.size() == duty.arrivals().size())
                cancelled.addAll(DutyPlan.cancelledTrains(dutyCalls, duty.skipped()));
        }
        List<PlannedTrain> planned = new ArrayList<>();
        for (Train train : timetable.trains()) {
            if (!train.line().equals(axis.line()))
                continue;
            List<Visit> visits = new ArrayList<>();
            boolean down = axis.down(train.stops().stream().map(Stop::station).toList());
            for (Stop stop : train.stops()) {
                // A stop with only a departure is a short one: the train comes in then too.
                double arrive = seconds(stop.hasArrive() || stop == train.stops().getFirst() ? stop.arrive() : stop.depart());
                visits.add(new Visit(stop.station(), arrive, seconds(stop.depart()), true, true, down));
            }
            Path path = path(axis, visits);
            planned.add(new PlannedTrain(train.number(), train.type(), train.deadhead(), path.segments,
                train.stops().getFirst().depart(), path.firstPosition, cancelled.contains(train.number())));
        }

        List<RanDuty> ran = new ArrayList<>();
        for (DutyTimes duty : times) {
            if (duty.day() != day)
                continue;
            List<Call> dutyCalls = calls.get(duty.duty());
            if (dutyCalls == null || dutyCalls.size() != duty.arrivals().size())
                continue;
            List<Visit> visits = new ArrayList<>();
            Set<Integer> skipped = new HashSet<>(duty.skipped());
            int made = -1; // the last call made before this one
            for (int i = 0; i < dutyCalls.size(); i++) {
                if (skipped.contains(i))
                    continue;
                Call call = dutyCalls.get(i);
                double arrive = clockSeconds(duty.arrivals().get(i), day);
                double depart = clockSeconds(duty.departures().get(i), day);
                String arrivingTrain = call.arrivingTrain();
                if (made >= 0 && made < i - 1) {
                    // Held at the station of the last call made until this one: one stand, from coming in there to leaving here.
                    arrive = visits.removeLast().arrive();
                    arrivingTrain = dutyCalls.get(made).arrivingTrain();
                }
                if (!Double.isNaN(arrive) && Double.isNaN(depart) && duty.status() == DutyTimes.Status.RUNNING && i < dutyCalls.size() - 1)
                    depart = Math.max(arrive, clockSeconds(now, day)); // standing there now
                boolean leavesOnLine = onLine(timetable, call.leavingTrain(), axis);
                boolean down = timetable.train(call.leavingTrain())
                    .map(train -> axis.down(train.stops().stream().map(Stop::station).toList())).orElse(true);
                visits.add(new Visit(call.station(), arrive, depart, leavesOnLine || onLine(timetable, arrivingTrain, axis), leavesOnLine, down));
                made = i;
            }
            List<Segment> segments = path(axis, visits).segments;
            if (!segments.isEmpty())
                ran.add(new RanDuty(duty, segments));
        }
        return new DiagramModel(axis, day, planned, ran);
    }

    private static boolean onLine(Timetable timetable, String number, LineAxis axis) {
        return !number.isEmpty() && timetable.train(number).map(train -> train.line().equals(axis.line())).orElse(false);
    }

    private static double seconds(int serviceTime) {
        return serviceTime == Stop.NONE ? Double.NaN : serviceTime;
    }

    /** Clock value {@code clock} as seconds from 00:00 of service {@code day}, or NaN for {@link DutyRun#NOT_YET}. */
    public static double clockSeconds(long clock, long day) {
        if (clock == DutyRun.NOT_YET)
            return Double.NaN;
        return (clock - day * RealClock.TICKS_PER_DAY) / (double) RealClock.TICKS_PER_SECOND;
    }

    /**
     * A station a train comes to.
     *
     * @param arrive  when it arrives there, or NaN
     * @param depart  when it leaves, or NaN
     * @param stay    whether its standing there is drawn
     * @param moveOn  whether its way to the next station is drawn
     * @param down    whether it goes on down the axis or up it, on a loop line, where the track does not
     *                show it (see {@link LineAxis#down(List)})
     */
    private record Visit(String station, double arrive, double depart, boolean stay, boolean moveOn, boolean down) {}

    private record Path(List<Segment> segments, double firstPosition) {}

    /**
     * The segments of a train's way through {@code visits}. The way between two stations is that of
     * {@link LineAxis#hops}; one across the ends of a loop line's axis is two segments, with the time
     * shared between them by their lengths. Standing is drawn at the row the train came in at, and also
     * at the row it goes out from if that is another (the first station of a loop line).
     */
    private static Path path(LineAxis axis, List<Visit> visits) {
        int n = visits.size();
        int[] rowIn = new int[n];
        int[] rowOut = new int[n];
        Arrays.fill(rowIn, -1);
        Arrays.fill(rowOut, -1);
        List<Segment> segments = new ArrayList<>();
        for (int k = 0; k + 1 < n; k++) {
            Visit from = visits.get(k);
            Visit to = visits.get(k + 1);
            Boolean track = axis.down(from.station(), to.station());
            List<LineAxis.Hop> hops = axis.hops(from.station(), to.station(), track != null ? track : from.down());
            if (hops.isEmpty())
                continue;
            rowOut[k] = hops.getFirst().fromRow();
            rowIn[k + 1] = hops.getLast().toRow();
            if (!from.moveOn() || Double.isNaN(from.depart()) || Double.isNaN(to.arrive()))
                continue;
            double at = from.depart();
            for (LineAxis.Hop hop : hops) {
                double until = hop == hops.getLast() ? to.arrive() : at + (to.arrive() - from.depart()) * hop.share();
                segments.add(new Segment(at, axis.position(hop.fromRow()), until, axis.position(hop.toRow())));
                at = until;
            }
        }
        for (int k = 0; k < n; k++) {
            Visit visit = visits.get(k);
            if (!visit.stay() || Double.isNaN(visit.arrive()) || Double.isNaN(visit.depart()) || visit.depart() <= visit.arrive())
                continue;
            int in = rowIn[k] >= 0 ? rowIn[k] : rowOut[k] >= 0 ? rowOut[k] : axis.row(visit.station());
            if (in < 0)
                continue;
            segments.add(new Segment(visit.arrive(), axis.position(in), visit.depart(), axis.position(in)));
            if (rowOut[k] >= 0 && rowOut[k] != in)
                segments.add(new Segment(visit.arrive(), axis.position(rowOut[k]), visit.depart(), axis.position(rowOut[k])));
        }
        segments.sort(Comparator.comparingDouble(Segment::fromSecond));
        int first = rowOut[0] >= 0 ? rowOut[0] : visits.isEmpty() ? -1 : axis.row(visits.getFirst().station());
        return new Path(segments, first >= 0 ? axis.position(first) : 0);
    }
}
