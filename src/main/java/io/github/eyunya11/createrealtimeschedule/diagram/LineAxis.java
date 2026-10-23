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
 * between them, either way round: on a loop, the way back from the second to the first is the whole
 * loop less the gap. Where the track does not join the two, the gap is the mean of the gaps that are
 * known, or {@link #UNKNOWN_GAP} blocks if none is. On a loop line the first station comes again at
 * the bottom, after the gap from the last station round to it, so that a train running on past the
 * end of the list goes down to it.
 *
 * @param line      the line's id
 * @param stations  the stations from the top down; on a loop line the first is last again
 * @param positions the distance of each from the top, in blocks, in the same order; the first is 0
 */
public record LineAxis(String line, List<String> stations, List<Double> positions) {

    /** The gap between two stations, in blocks, when no gap of the line is known. */
    public static final double UNKNOWN_GAP = 100;

    public LineAxis {
        Objects.requireNonNull(line, "line");
        stations = List.copyOf(stations);
        positions = List.copyOf(positions);
        if (stations.size() != positions.size())
            throw new IllegalArgumentException(stations.size() + " stations and " + positions.size() + " positions");
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
            double gap = gap(stations.get(i), stations.get(i + 1), legs);
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
        return new LineAxis(line.id(), stations, positions);
    }

    /** The length of the shorter way between {@code a} and {@code b}, either way round, or NaN if neither is there. */
    private static double gap(String a, String b, List<Leg> legs) {
        double gap = Double.NaN;
        for (Leg leg : legs)
            if (leg.from().equals(a) && leg.to().equals(b) || leg.from().equals(b) && leg.to().equals(a))
                if (Double.isNaN(gap) || leg.distance() < gap)
                    gap = leg.distance();
        return gap;
    }

    /** How far down the bottom station is, in blocks. */
    public double length() {
        return positions.getLast();
    }

    public double position(int row) {
        return positions.get(row);
    }

    /**
     * The rows of the way from {@code from} to {@code to}, which are next to each other in the order
     * of a train's stops: of the rows each is at (two for the first station of a loop line), the pair
     * nearest each other, and of two as near, the one going down the axis, the way the line runs.
     * Empty if either station is not on the axis.
     *
     * @return {@code {fromRow, toRow}}, or an empty array
     */
    public int[] rows(String from, String to) {
        int[] best = new int[0];
        double nearest = Double.MAX_VALUE;
        for (int i = 0; i < stations.size(); i++) {
            if (!stations.get(i).equals(from))
                continue;
            for (int j = 0; j < stations.size(); j++) {
                if (!stations.get(j).equals(to))
                    continue;
                double apart = Math.abs(positions.get(i) - positions.get(j));
                boolean down = j > i;
                if (apart < nearest || apart == nearest && down && best[1] < best[0]) {
                    nearest = apart;
                    best = new int[] {i, j};
                }
            }
        }
        return best;
    }

    /** The first row {@code station} is at, or -1 if it is not on the axis. */
    public int row(String station) {
        return stations.indexOf(station);
    }
}
