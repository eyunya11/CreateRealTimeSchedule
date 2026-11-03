package io.github.eyunya11.createrealtimeschedule.map;

import java.util.List;
import java.util.Set;

/**
 * What a map screen shows, which every block of a screen holds the same of.
 *
 * @param centreX the world's X in the middle of the screen
 * @param centreZ the world's Z in the middle of the screen
 * @param scale   how many blocks of the world a block of the screen shows each way
 * @param lines   the ids of the timetable's lines whose track it shows; none for all the track
 * @param hidden  the kinds of things it does not show, by the names of {@link #SCREEN_LAYERS}
 * @param tagsOff the track tags turned off ({@link TagView}), by their names, {@link TagView#NO_TAG}
 *                for no tag; none for all on
 * @param stamp   when it was set, in game ticks: where screens join, the settings set last are kept
 */
public record ScreenSettings(double centreX, double centreZ, double scale, List<String> lines, Set<String> hidden, Set<String> tagsOff,
                             long stamp) {

    /** The kinds of things on a map that can be shown or not, as the editor's map.js names them. */
    public static final List<String> LAYERS = List.of("stations", "stationNames", "signals", "speedLimits", "trains");

    /**
     * The kinds a map screen can show or not: those of {@link #LAYERS}, and the heads of the signals on
     * their posts, which only a screen draws; without them a signal is its mark on the track.
     */
    public static final List<String> SCREEN_LAYERS = List.of("stations", "stationNames", "signals", "signalHeads", "speedLimits",
        "trains");

    /** What a new screen shows: all the track, everything on it, round the middle of the world. */
    public static final ScreenSettings DEFAULT = new ScreenSettings(0, 0, 16, List.of(), Set.of(), Set.of(), 0);

    public ScreenSettings {
        scale = MapProjection.clampScale(scale);
        lines = List.copyOf(lines);
        hidden = Set.copyOf(hidden);
        tagsOff = Set.copyOf(tagsOff);
    }

    /** Settings with every track tag on. */
    public ScreenSettings(double centreX, double centreZ, double scale, List<String> lines, Set<String> hidden, long stamp) {
        this(centreX, centreZ, scale, lines, hidden, Set.of(), stamp);
    }

    public boolean shows(String layer) {
        return !hidden.contains(layer);
    }

    /** The projection of a screen of this size. */
    public MapProjection projection(int width, int height) {
        return new MapProjection(centreX, centreZ, scale, width, height);
    }
}
