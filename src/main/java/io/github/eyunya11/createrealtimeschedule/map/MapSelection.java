package io.github.eyunya11.createrealtimeschedule.map;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;

/**
 * Which tracks a map shows, as the editor's {@code map.js} works it out too: shared cases hold the two
 * to the same tracks ({@code src/test/resources/.../map/cases}).
 *
 * <p>A map that shows no lines shows every track. One that shows some lines shows, for each of them,
 * the tracks its trains run along between each station of the line and the next (both ways, and from
 * the last station back to the first on a loop), with the tracks the line adds and without those it
 * takes away. Either way, the tracks the timetable hides are not shown. A train runs along the tracks
 * of the leg from one station to the next: the way a train is sent between them.
 */
public final class MapSelection {

    private MapSelection() {}

    /**
     * The tracks of a leg.
     *
     * @param from   the station it starts at
     * @param to     the station it ends at
     * @param tracks the indexes of the tracks it runs along
     */
    public record LegTracks(String from, String to, List<Integer> tracks) {
        public LegTracks {
            tracks = List.copyOf(tracks);
        }
    }

    /**
     * The indexes of the tracks shown, in order.
     *
     * @param keys      the key of each track, by its index
     * @param legs      the legs between the stations, with the tracks they run along
     * @param timetable the timetable, whose lines and hidden tracks say what to show; null for none, which
     *                  shows every track
     * @param lines     the ids of the lines shown; none for every track. An id the timetable has no line of shows nothing.
     */
    public static List<Integer> visible(List<String> keys, List<LegTracks> legs, Timetable timetable, Collection<String> lines) {
        Map<String, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < keys.size(); i++)
            indexOf.putIfAbsent(keys.get(i), i);
        TreeSet<Integer> shown = new TreeSet<>();
        if (lines.isEmpty() || timetable == null) {
            for (int i = 0; i < keys.size(); i++)
                shown.add(i);
        } else {
            Map<List<String>, LegTracks> legOf = new HashMap<>();
            for (LegTracks leg : legs)
                legOf.putIfAbsent(List.of(leg.from(), leg.to()), leg);
            Set<String> wanted = Set.copyOf(lines);
            for (Timetable.Line line : timetable.lines()) {
                if (!wanted.contains(line.id()))
                    continue;
                TreeSet<Integer> ofLine = new TreeSet<>();
                List<String> stations = line.stations();
                for (int i = 0; i < stations.size(); i++) {
                    boolean last = i == stations.size() - 1;
                    if (last && !(line.loop() && stations.size() > 2))
                        break;
                    String a = stations.get(i);
                    String b = stations.get(last ? 0 : i + 1);
                    for (List<String> pair : List.of(List.of(a, b), List.of(b, a))) {
                        LegTracks leg = legOf.get(pair);
                        if (leg != null)
                            ofLine.addAll(leg.tracks());
                    }
                }
                for (String key : line.mapAdd())
                    if (indexOf.containsKey(key))
                        ofLine.add(indexOf.get(key));
                for (String key : line.mapRemove())
                    if (indexOf.containsKey(key))
                        ofLine.remove(indexOf.get(key));
                shown.addAll(ofLine);
            }
        }
        if (timetable != null)
            for (String key : timetable.mapHidden())
                if (indexOf.containsKey(key))
                    shown.remove(indexOf.get(key));
        shown.removeIf(track -> track < 0 || track >= keys.size());
        return new ArrayList<>(shown);
    }

    /** The keys a timetable names that are not keys of the map's tracks: tracks laid again, or taken up, since. */
    public static List<String> missing(List<String> keys, Timetable timetable) {
        Set<String> known = Set.copyOf(keys);
        TreeSet<String> missing = new TreeSet<>();
        for (Timetable.Line line : timetable.lines()) {
            for (String key : line.mapAdd())
                if (!known.contains(key))
                    missing.add(key);
            for (String key : line.mapRemove())
                if (!known.contains(key))
                    missing.add(key);
        }
        for (String key : timetable.mapHidden())
            if (!known.contains(key))
                missing.add(key);
        return new ArrayList<>(missing);
    }
}
