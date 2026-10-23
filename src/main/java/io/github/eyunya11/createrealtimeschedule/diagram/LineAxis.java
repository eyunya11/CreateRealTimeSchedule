package io.github.eyunya11.createrealtimeschedule.diagram;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Line;

/**
 * The vertical axis of a line's diagram: its stations from the top down, each at the distance along
 * the track from the first, in blocks. It has no Minecraft types in it so that it can be tested
 * without a game.
 *
 * <p>The distance between two stations next to each other on the line is that of the shorter way
 * between them, either way round, that passes no other station of the line: on a loop, the way back
 * from the second to the first is round the rest of the loop, through the line's other stations, and
 * does not count. Where the track does not join the two so, the gap is the mean of the gaps that are
 * known, or {@link #UNKNOWN_GAP} blocks if none is.
 *
 * <p>On a loop line the first station comes again at the bottom, after the gap from the last station
 * round to it, so that a train running down past the end of the list goes on down to it, and one
 * running up past the first station comes in at the bottom.
 *
 * @param line      the line's id
 * @param loop      whether it is a loop line
 * @param stations  the stations from the top down; on a loop line the first is last again
 * @param positions the distance of each from the top, in blocks, in the same order; the first is 0
 */
public record LineAxis(String line, boolean loop, List<String> stations, List<Double> positions) {

    /** The gap between two stations, in blocks, when no gap of the line is known. */
    public static final double UNKNOWN_GAP = 100;

    /**
     * One straight part of the way between two stations: a way across the first station of a loop line
     * has two, one to the bottom and one on from the top, or the other way round.
     *
     * @param fromRow the row it starts at
     * @param toRow   the row it ends at
     * @param share   its share of the length of the way, from 0 to 1
     */
    public record Hop(int fromRow, int toRow, double share) {}

    public LineAxis {
        Objects.requireNonNull(line, "line");
        stations = List.copyOf(stations);
        positions = List.copyOf(positions);
        if (stations.size() != positions.size())
            throw new IllegalArgumentException(stations.size() + " stations and " + positions.size() + " positions");
        if (loop && (stations.size() < 2 || !stations.getFirst().equals(stations.getLast())))
            throw new IllegalArgumentException("the axis of a loop line ends with its first station again: " + stations);
    }

    /** The axis of {@code line}, with the gaps taken from {@code legs}, the ways a train can run between stations. */
    public static LineAxis of(Line line, List<Leg> legs) {
        List<String> stations = new ArrayList<>(line.stations());
        if (line.loop())
            stations.add(stations.getFirst());
        List<Double> gaps = new ArrayList<>();
        double known = 0;
        int knownCount = 0;
        for (int i = 0; i + 1 < stations.size(); i++) {
            double gap = gap(stations.get(i), stations.get(i + 1), legs, line.stations());
            gaps.add(gap);
            if (!Double.isNaN(gap)) {
                known += gap;
                knownCount++;
            }
        }
        double fill = knownCount == 0 ? UNKNOWN_GAP : known / knownCount;
        List<Double> positions = new ArrayList<>();
        double at = 0;
        positions.add(at);
        for (double gap : gaps) {
            at += Double.isNaN(gap) ? fill : gap;
            positions.add(at);
        }
        return new LineAxis(line.id(), line.loop(), stations, positions);
    }

    /**
     * The length of the shorter way between {@code a} and {@code b}, either way round, that passes none
     * of the line's other stations; NaN if there is none.
     */
    private static double gap(String a, String b, List<Leg> legs, List<String> lineStations) {
        double gap = Double.NaN;
        for (Leg leg : legs) {
            boolean between = leg.from().equals(a) && leg.to().equals(b) || leg.from().equals(b) && leg.to().equals(a);
            if (between && leg.via().stream().noneMatch(lineStations::contains) && (Double.isNaN(gap) || leg.distance() < gap))
                gap = leg.distance();
        }
        return gap;
    }

    /** How far down the bottom station is, in blocks. */
    public double length() {
        return positions.getLast();
    }

    public double position(int row) {
        return positions.get(row);
    }

    /** The first row {@code station} is at, or -1 if it is not on the axis. */
    public int row(String station) {
        return stations.indexOf(station);
    }

    /** How many stations the line has: on a loop line, one fewer than the rows. */
    private int size() {
        return loop ? stations.size() - 1 : stations.size();
    }

    /**
     * Whether a train making {@code stops} in order runs down a loop line, the way its stations are
     * listed, rather than up it: the way round that passes fewer stations, over all its stops, and down
     * if both pass as many. Always true on a line that is not a loop.
     */
    public boolean down(List<String> stops) {
        if (!loop)
            return true;
        int n = size();
        int downSteps = 0;
        int upSteps = 0;
        for (int k = 0; k + 1 < stops.size(); k++) {
            int i = row(stops.get(k));
            int j = row(stops.get(k + 1));
            if (i < 0 || j < 0)
                continue;
            downSteps += Math.floorMod(j - i, n);
            upSteps += Math.floorMod(i - j, n);
        }
        return downSteps <= upSteps;
    }

    /**
     * The way from {@code from} to {@code to}, which are next to each other in the order of a train's
     * stops, going down the axis or up it ({@link #down}); empty if either station is not on the axis.
     * On a line that is not a loop the way is the one straight hop between the two rows, whichever way.
     */
    public List<Hop> hops(String from, String to, boolean down) {
        int i = row(from);
        int j = row(to);
        if (i < 0 || j < 0)
            return List.of();
        if (!loop)
            return List.of(new Hop(i, j, 1));
        int bottom = stations.size() - 1;
        if (down) {
            int end = j == 0 ? bottom : j;
            if (end > i)
                return List.of(new Hop(i, end, 1));
            return split(i, bottom, 0, end);
        }
        int start = i == 0 ? bottom : i;
        if (j < start)
            return List.of(new Hop(start, j, 1));
        return split(start, 0, bottom, j);
    }

    /** A way across the ends of a loop line's axis: from {@code a} to the end {@code b}, then from the other end {@code c} to {@code d}. */
    private List<Hop> split(int a, int b, int c, int d) {
        double first = Math.abs(position(b) - position(a));
        double second = Math.abs(position(d) - position(c));
        double share = first + second == 0 ? 0.5 : first / (first + second);
        return List.of(new Hop(a, b, share), new Hop(c, d, 1 - share));
    }
}
