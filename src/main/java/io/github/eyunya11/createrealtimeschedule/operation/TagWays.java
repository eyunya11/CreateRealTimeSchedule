package io.github.eyunya11.createrealtimeschedule.operation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Duty;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Train;

/**
 * The ways between stops that a train cannot run on the track tag the timetable holds it to: a set
 * held to it there would not leave. The editor finds the same from the network the mod writes
 * ({@code edit.js}, {@code noWay}), by the same rules, which shared test cases hold the two to.
 *
 * <p>Where a set comes in decides where it can go on from, as platforms of one name may lie on track
 * of different tags. So the platforms a set may stand at are followed along each duty, from those of
 * its depot, and along each train in no duty from those of its first stop: from each way between
 * stops to the next, the platforms the set comes to from any of them. A way none of them has a way
 * on from, on its tag, is a way with none; the set is then taken to come in at any platform of the
 * next stop. Only ways a train is held to a tag on are told of: a way with no tag that the track has
 * none for is no matter of the tags.
 *
 * <p>It has no Minecraft types in it so that it can be tested without a game.
 */
public final class TagWays {

    private TagWays() {}

    /** What the track says of the platforms of the timetable's stations. */
    public interface Track {

        /** The platforms of the station named {@code station}, by numbers of the track's own. */
        List<Integer> platforms(String station);

        /**
         * The platform a set held to the track tag {@code tag} (empty for none) that stands at
         * {@code platform} comes to when it is sent to a station named {@code station}; -1 if it has no
         * way there.
         */
        int way(int platform, String station, String tag);
    }

    /**
     * A way between stops a train cannot run on its tag.
     *
     * @param train the number of the train
     * @param stop  the index of the stop it leaves, from 0
     * @param from  the station of that stop
     * @param to    the station of the next
     * @param tag   the tag it is held to there
     */
    public record NoWay(String train, int stop, String from, String to, String tag) {

        /** What to log. */
        public String text() {
            return "Train " + train + " has no way from " + from + " (stop " + (stop + 1) + ") to " + to + " on the track tag \"" + tag
                + "\": a set held to it there will not leave";
        }
    }

    /** The ways of {@code timetable} its trains cannot run on their tags, by train and then by stop. */
    public static List<NoWay> of(Timetable timetable, Track track) {
        List<NoWay> found = new ArrayList<>();
        Set<String> followed = new HashSet<>();
        for (Duty duty : timetable.duties()) {
            Set<Integer> at = new LinkedHashSet<>(track.platforms(duty.depot()));
            for (String number : duty.trains()) {
                Optional<Train> train = timetable.train(number);
                if (train.isEmpty() || train.get().stops().isEmpty())
                    break;
                if (followed.add(number))
                    at = follow(train.get(), at, track, found);
                else
                    at = follow(train.get(), at, track, new ArrayList<>());
            }
        }
        for (Train train : timetable.trains())
            if (!train.stops().isEmpty() && followed.add(train.number()))
                follow(train, new LinkedHashSet<>(track.platforms(train.stops().getFirst().station())), track, found);
        List<NoWay> ordered = new ArrayList<>();
        for (Train train : timetable.trains())
            for (NoWay way : found)
                if (way.train().equals(train.number()))
                    ordered.add(way);
        return ordered;
    }

    /** The platforms {@code train} may come in at, from {@code at}, its ways with none added to {@code found}. */
    private static Set<Integer> follow(Train train, Set<Integer> at, Track track, List<NoWay> found) {
        for (int k = 0; k + 1 < train.stops().size(); k++) {
            String tag = train.tagAt(k);
            String to = train.stops().get(k + 1).station();
            Set<Integer> next = new LinkedHashSet<>();
            for (int platform : at) {
                int way = track.way(platform, to, tag);
                if (way >= 0)
                    next.add(way);
            }
            if (next.isEmpty()) {
                if (!tag.isEmpty())
                    found.add(new NoWay(train.number(), k, train.stops().get(k).station(), to, tag));
                next.addAll(track.platforms(to));
            }
            at = next;
        }
        return at;
    }

    /** The tags the timetable holds trains to that are not among {@code known}, each once, in the order they first come. */
    public static List<String> unknown(Timetable timetable, Collection<String> known) {
        Set<String> found = new LinkedHashSet<>();
        for (String tag : tags(timetable))
            if (!known.contains(tag))
                found.add(tag);
        return List.copyOf(found);
    }

    /** The tags the timetable holds trains to, each once, in the order they first come. */
    public static List<String> tags(Timetable timetable) {
        Set<String> found = new LinkedHashSet<>();
        for (Train train : timetable.trains())
            for (int k = 0; k + 1 < train.stops().size(); k++)
                if (!train.tagAt(k).isEmpty())
                    found.add(train.tagAt(k));
        return List.copyOf(found);
    }
}
