package io.github.eyunya11.createrealtimeschedule.traffic;

import java.util.ArrayList;
import java.util.List;

import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Edge;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Point;
import io.github.eyunya11.createrealtimeschedule.railway.RailNetwork.Station;
import io.github.eyunya11.createrealtimeschedule.railway.SignalBlocks;

/** Networks with signals for the tests of the simulation of the trains of a timetable. */
public final class TestLines {

    private TestLines() {}

    /** The signals of {@link #line}, from the start. */
    public static final double[] SIGNALS = {200, 300, 400, 500, 600};

    /**
     * 1000 blocks of track one way, with a signal at each of {@link #SIGNALS}, each into a block of its
     * own: block 0 up to the first, and the last to the end of the track. The signals are light signals of
     * {@code aspects} aspects, or Create's own for 0, or cross signals of Create's for -1 (the first only).
     */
    public static RailNetwork line(int aspects, SignalBlocks.LightSpeeds speeds, double... stations) {
        List<Point> points = new ArrayList<>();
        List<SignalBlocks.Change> changes = new ArrayList<>(List.of(new SignalBlocks.Change(0, 0)));
        List<SignalBlocks.Signal> signals = new ArrayList<>();
        for (int i = 0; i < SIGNALS.length; i++) {
            points.add(new Point(SIGNALS[i], Point.Kind.SIGNAL, i));
            changes.add(new SignalBlocks.Change(SIGNALS[i], i + 1));
            List<Integer> next = i + 1 < SIGNALS.length ? List.of(i + 1) : List.of();
            signals.add(new SignalBlocks.Signal(i + 1, next, aspects > 0 ? List.of(aspects) : List.of(), aspects < 0 && i == 0));
        }
        SignalBlocks blocks = new SignalBlocks(List.of(changes), signals, SIGNALS.length + 1, List.of(), speeds);
        List<Station> named = new ArrayList<>();
        for (int i = 0; i < stations.length; i++)
            named.add(new Station(String.valueOf((char) ('A' + i)), 0, stations[i]));
        return new RailNetwork(List.of(new Edge(1000, false, List.of(), List.of(), List.of(), -1, points, -1, -1)), named,
            List.of(), List.of(), blocks);
    }
}
