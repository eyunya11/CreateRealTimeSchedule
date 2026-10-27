package io.github.eyunya11.createrealtimeschedule.timetable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

/**
 * A timetable as one document: the lines, the trains that run on them, and the duties that string
 * trains together. The later parts of the mod work from it (duties, the diagram, the editor), and
 * {@link TimetableJson} reads and writes it. It has no Minecraft types in it so that it can be tested
 * without a game.
 *
 * <p>A station is named the way Create names it, because a train is sent to a station by its name,
 * and names are compared exactly. A <em>line</em> is only the order its stations come in, which Create
 * does not have: it is the vertical axis of a diagram. A <em>train</em> is one run along a line under a
 * number such as {@code 101}, with the times it arrives at and leaves each of its stops. A
 * <em>duty</em> is what one set of rolling stock does in a day: trains one after another, each starting
 * where the one before ended, from its depot and back. A <em>deadhead</em> train carries no passengers;
 * it is how a duty gets from the depot to where its first train starts, and back again, and it is drawn
 * on the diagram and timed like any other.
 *
 * <p>How long a train stands at a station is the time between its arrival and its departure there.
 * A <em>station</em> entry gives a station of the lines the times the editor and the operation take
 * for it when a stop does not say: the dwell a train drawn in the editor gets there, and the minimum
 * stop a late train still makes there.
 *
 * <p>The records hold whatever they are given. {@link #problems()} says what is wrong with them.
 *
 * @param name     what to call the timetable; may be empty
 * @param lines    the lines
 * @param stations the stations that have times of their own, each at most once; the others have none
 * @param trains   the trains, on any of the lines
 * @param duties   the duties, each made of trains from {@code trains}
 */
public record Timetable(String name, List<Line> lines, List<Station> stations, List<Train> trains, List<Duty> duties) {

    /** The {@code format} member of a timetable in JSON. */
    public static final String FORMAT = "createrealtimeschedule.timetable";

    /**
     * The newest version of the JSON this mod reads. Version 2 added deadhead trains, version 3 the
     * stations' times and the stops' minimum stops, version 4 the trains that are never cancelled to
     * get a late set back on time, and version 5 the trains a stop waits for. A timetable is written in
     * the oldest version that holds it ({@link #version()}), so that a mod that reads only version 2
     * still takes one that has none of what the later versions added.
     */
    public static final int VERSION = 5;

    /** The longest line id, train number or duty id. */
    public static final int MAX_ID_LENGTH = 32;

    /** The longest dwell or minimum stop, in seconds: the longest minimum stop the server's config takes. */
    public static final int MAX_STOP_SECONDS = 600;

    /** The longest a train waits for another past its own time, in seconds: the longest the server's config takes. */
    public static final int MAX_WAIT_SECONDS = 3600;

    public Timetable {
        Objects.requireNonNull(name, "name");
        lines = List.copyOf(lines);
        stations = List.copyOf(stations);
        trains = List.copyOf(trains);
        duties = List.copyOf(duties);
    }

    /** A timetable whose stations have no times of their own. */
    public Timetable(String name, List<Line> lines, List<Train> trains, List<Duty> duties) {
        this(name, lines, List.of(), trains, duties);
    }

    /**
     * @param id       what a train refers to the line by
     * @param name     what to show
     * @param stations the stations in order, at least two, none twice
     * @param loop     whether the last station is followed by the first, so that a train may go on
     *                 past the end of the list and the order of its stops is not checked
     */
    public record Line(String id, String name, List<String> stations, boolean loop) {
        public Line {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            stations = List.copyOf(stations);
        }

        /**
         * Whether a train that makes {@code stops} in order may run through {@code station} of the line,
         * without stopping, on its way from stop {@code from} to the next. On a line that is not a loop,
         * that is a station between the two. On a loop line, which way round it goes is the track's,
         * which the timetable does not know, so every other station of the line may be on its way;
         * the operation goes by the track (see {@code Waits}).
         */
        public boolean runsThrough(List<String> stops, int from, String station) {
            int a = stations.indexOf(stops.get(from));
            int b = stations.indexOf(stops.get(from + 1));
            int q = stations.indexOf(station);
            if (a < 0 || b < 0 || q < 0 || q == a || q == b)
                return false;
            return loop || Math.min(a, b) < q && q < Math.max(a, b);
        }
    }

    /**
     * @param number the train number, such as {@code 101} or {@code 12A}; no other train has it
     * @param line   the id of the line it runs on
     * @param type   the kind of service, such as {@code local} or {@code rapid}, for display; may be empty
     * @param stops    the stops in the order the train makes them, at least two
     * @param deadhead whether it runs empty, such as from the depot to where the duty starts
     * @param keep     whether it must run even when its set is late: it is never cancelled to get the
     *                 set back on time, such as the last train of the day
     */
    public record Train(String number, String line, String type, List<Stop> stops, boolean deadhead, boolean keep) {
        public Train {
            Objects.requireNonNull(number, "number");
            Objects.requireNonNull(line, "line");
            Objects.requireNonNull(type, "type");
            stops = List.copyOf(stops);
        }

        /** A train that may be cancelled to get a late set back on time. */
        public Train(String number, String line, String type, List<Stop> stops, boolean deadhead) {
            this(number, line, type, stops, deadhead, false);
        }

        /** A train that carries passengers. */
        public Train(String number, String line, String type, List<Stop> stops) {
            this(number, line, type, stops, false);
        }
    }

    /**
     * The times a station has of its own, for the stops that do not say.
     *
     * @param name        the station, as Create names it; a station of one of the lines
     * @param dwell       how many seconds a train drawn in the editor stands there, or {@link Stop#NONE}
     *                    for the editor's own setting. The operation does not read it: a train's stop
     *                    has its times
     * @param minimumStop how many seconds a late train stands there at the least, or {@link Stop#NONE}
     *                    for the server's config
     */
    public record Station(String name, int dwell, int minimumStop) {
        public Station {
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * One stop of a train. The first stop of a train has only a departure, the last only an arrival,
     * and every stop in between has a departure and may have an arrival (the train waits between them).
     *
     * @param station     the station, as Create names it
     * @param arrive      when the train arrives, as {@link ServiceTime} seconds, or {@link #NONE}
     * @param depart      when the train leaves, or {@link #NONE}
     * @param minimumStop how many seconds the train stands here at the least when it comes in late, or
     *                    {@link #NONE} for the station's (see {@link Timetable#minimumStop})
     * @param waits       the trains it waits for here before it leaves, in order
     */
    public record Stop(String station, int arrive, int depart, int minimumStop, List<Wait> waits) {

        /** The time of a stop that has no such time. */
        public static final int NONE = -1;

        public Stop {
            Objects.requireNonNull(station, "station");
            waits = List.copyOf(waits);
        }

        /** A stop that waits for no train. */
        public Stop(String station, int arrive, int depart, int minimumStop) {
            this(station, arrive, depart, minimumStop, List.of());
        }

        /** A stop with the station's minimum stop. */
        public Stop(String station, int arrive, int depart) {
            this(station, arrive, depart, NONE);
        }

        public boolean hasMinimumStop() {
            return minimumStop != NONE;
        }

        /** The first stop of a train, or a stop it passes through without a time of arrival. */
        public static Stop leaving(String station, int depart) {
            return new Stop(station, NONE, depart);
        }

        /** The last stop of a train. */
        public static Stop arriving(String station, int arrive) {
            return new Stop(station, arrive, NONE);
        }

        /** A stop in between, where the train stands from {@code arrive} to {@code depart}. */
        public static Stop calling(String station, int arrive, int depart) {
            return new Stop(station, arrive, depart);
        }

        public boolean hasArrive() {
            return arrive != NONE;
        }

        public boolean hasDepart() {
            return depart != NONE;
        }
    }

    /**
     * A train that a train waits for at a stop before it leaves: until that train has left station
     * {@code at}, or run through it without stopping. A local train waits so for an express to pass it
     * where it stands aside, or to leave first where the two meet.
     *
     * @param train   the number of the train waited for
     * @param at      the station that train leaves or runs through, as Create names it; empty for the
     *                stop's own (see {@link #station})
     * @param maxWait how many seconds past its own time the train waits at the most, or {@link Stop#NONE}
     *                for the server's config
     */
    public record Wait(String train, String at, int maxWait) {
        public Wait {
            Objects.requireNonNull(train, "train");
            Objects.requireNonNull(at, "at");
        }

        /** A wait at the stop's own station, for as long as the server's config says. */
        public Wait(String train) {
            this(train, "", Stop.NONE);
        }

        /** The station the train waited for leaves or runs through, for a stop at {@code stopStation}. */
        public String station(String stopStation) {
            return at.isEmpty() ? stopStation : at;
        }

        public boolean hasMaxWait() {
            return maxWait != Stop.NONE;
        }
    }

    /**
     * @param id     the duty number, such as {@code 12A}; no other duty has it
     * @param depot  the station where the trains for this duty wait when they are not running. The
     *               first train of the duty leaves from it and the last arrives at it
     * @param trains the numbers of the trains the duty runs, in order, at least one. Each starts at the
     *               station where the one before ended, and after it arrived there
     */
    public record Duty(String id, String depot, List<String> trains) {
        public Duty {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(depot, "depot");
            trains = List.copyOf(trains);
        }
    }

    public Optional<Line> line(String id) {
        return lines.stream().filter(line -> line.id().equals(id)).findFirst();
    }

    public Optional<Train> train(String number) {
        return trains.stream().filter(train -> train.number().equals(number)).findFirst();
    }

    public Optional<Station> station(String name) {
        return stations.stream().filter(station -> station.name().equals(name)).findFirst();
    }

    /**
     * The minimum stop a late train makes at {@code stop}, in seconds: the stop's own, or else the
     * station's, or {@link Stop#NONE} when neither says, for the server's config.
     */
    public int minimumStop(Stop stop) {
        if (stop.hasMinimumStop())
            return stop.minimumStop();
        return station(stop.station()).map(Station::minimumStop).orElse(Stop.NONE);
    }

    /**
     * The oldest version of the JSON that holds this timetable: 5 if a stop waits for a train, else 4 if
     * it has a train that is never cancelled, else 3 if it has anything version 3 added, else 2.
     */
    public int version() {
        if (trains.stream().anyMatch(train -> train.stops().stream().anyMatch(stop -> !stop.waits().isEmpty())))
            return 5;
        if (trains.stream().anyMatch(Train::keep))
            return 4;
        boolean three = !stations.isEmpty()
            || trains.stream().anyMatch(train -> train.stops().stream().anyMatch(Stop::hasMinimumStop));
        return three ? 3 : 2;
    }

    /**
     * Everything that is wrong with the timetable, in words; empty if it can be used.
     *
     * <p>That is: ids, numbers and names that are empty or have a space at either end; two lines,
     * trains or duties with the same id; a train on a line that is not there, or stopping at a station
     * that is not on its line; stops that are not in order along a line, or the same station twice in a
     * row; times that are missing where they are needed, out of range, or not in order; dwells and
     * minimum stops out of range, a station given times twice or that no line has, and a minimum stop
     * at a train's last stop, which it does not leave; and duties that name a train that is not there,
     * use a train twice, string trains together that do not meet (the next starts somewhere else, or
     * before the one before has arrived), or do not start and end at their depot. A train waited for at
     * a stop has to be another train of the timetable, which leaves the station (or runs through it)
     * before the stop's train leaves: so a train never waits, by and by, for itself.
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (!name.isEmpty() && !isText(name))
            problems.add("the name must not start or end with a space or contain a control character");
        Map<String, Line> lineById = checkLines(problems);
        checkStations(problems);
        Map<String, Train> trainByNumber = checkTrains(lineById, problems);
        checkWaits(lineById, trainByNumber, problems);
        checkDuties(trainByNumber, problems);
        return problems;
    }

    // ------------------------------------------------------------------ lines

    private Map<String, Line> checkLines(List<String> problems) {
        Map<String, Line> byId = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            String label = label("line", "lines", i, line.id());
            if (!isIdentifier(line.id()))
                problems.add(label + ": " + idRule("id"));
            else if (byId.putIfAbsent(line.id(), line) != null)
                problems.add(label + ": another line has the same id");
            if (!isText(line.name()))
                problems.add(label + ": the name " + TEXT_RULE);
            if (line.stations().size() < 2)
                problems.add(label + ": needs at least 2 stations");
            Set<String> seen = new HashSet<>();
            for (String station : line.stations()) {
                if (!isText(station))
                    problems.add(label + ": a station name " + TEXT_RULE);
                else if (!seen.add(station))
                    problems.add(label + ": station \"" + station + "\" is listed twice");
            }
        }
        return byId;
    }

    // ------------------------------------------------------------------ stations

    private void checkStations(List<String> problems) {
        Set<String> onLines = new HashSet<>();
        for (Line line : lines)
            onLines.addAll(line.stations());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < stations.size(); i++) {
            Station station = stations.get(i);
            String label = label("station", "stations", i, station.name());
            if (!isText(station.name()))
                problems.add(label + ": the name " + TEXT_RULE);
            else if (!seen.add(station.name()))
                problems.add(label + ": is listed twice");
            else if (!onLines.contains(station.name()))
                problems.add(label + ": is not a station of any line");
            if (station.dwell() != Stop.NONE && !isStopSeconds(station.dwell()))
                problems.add(label + ": the dwell " + STOP_RULE);
            if (station.minimumStop() != Stop.NONE && !isStopSeconds(station.minimumStop()))
                problems.add(label + ": the minimum stop " + STOP_RULE);
        }
    }

    // ------------------------------------------------------------------ trains

    private Map<String, Train> checkTrains(Map<String, Line> lineById, List<String> problems) {
        Map<String, Train> byNumber = new HashMap<>();
        for (int i = 0; i < trains.size(); i++) {
            Train train = trains.get(i);
            String label = label("train", "trains", i, train.number());
            if (!isIdentifier(train.number()))
                problems.add(label + ": " + idRule("number"));
            else if (byNumber.putIfAbsent(train.number(), train) != null)
                problems.add(label + ": another train has the same number");
            if (!train.type().isEmpty() && !isText(train.type()))
                problems.add(label + ": the type must not start or end with a space or contain a control character");
            Line line = lineById.get(train.line());
            if (line == null)
                problems.add(label + ": there is no line \"" + train.line() + "\"");
            if (train.stops().size() < 2)
                problems.add(label + ": needs at least 2 stops");
            else
                checkStops(train, line, label, problems);
        }
        return byNumber;
    }

    private static void checkStops(Train train, @Nullable Line line, String label, List<String> problems) {
        List<Stop> stops = train.stops();
        int lastIndex = stops.size() - 1;
        int previousDeparture = Stop.NONE;
        int previousPosition = -1;
        int direction = 0;
        boolean turnedBack = false;

        for (int k = 0; k <= lastIndex; k++) {
            Stop stop = stops.get(k);
            String where = label + ", stop " + (k + 1) + " \"" + stop.station() + "\"";

            // the station
            if (!isText(stop.station()))
                problems.add(where + ": a station name " + TEXT_RULE);
            int position = line == null ? -1 : line.stations().indexOf(stop.station());
            if (line != null && position < 0)
                problems.add(where + ": is not a station of line \"" + line.id() + "\"");
            if (k > 0 && stop.station().equals(stops.get(k - 1).station()))
                problems.add(where + ": is the same station as the stop before it");

            // which times it has
            if (k == 0 && stop.hasArrive())
                problems.add(where + ": is the first stop, which only has a departure time");
            if (k == lastIndex && stop.hasDepart())
                problems.add(where + ": is the last stop, which only has an arrival time");
            if (k != lastIndex && !stop.hasDepart())
                problems.add(where + ": needs a departure time");
            if (k == lastIndex && !stop.hasArrive())
                problems.add(where + ": needs an arrival time");
            if (stop.hasArrive() && !isTime(stop.arrive()))
                problems.add(where + ": the arrival time is not between 00:00 and 47:59:59");
            if (stop.hasDepart() && !isTime(stop.depart()))
                problems.add(where + ": the departure time is not between 00:00 and 47:59:59");
            if (stop.hasMinimumStop() && k == lastIndex)
                problems.add(where + ": is the last stop, which the train does not leave, so it has no minimum stop");
            else if (stop.hasMinimumStop() && !isStopSeconds(stop.minimumStop()))
                problems.add(where + ": the minimum stop " + STOP_RULE);

            // the times in order
            if (stop.hasArrive() && previousDeparture != Stop.NONE && stop.arrive() <= previousDeparture)
                problems.add(where + ": arrives at " + time(stop.arrive())
                    + ", which is not after the train leaves the stop before at " + time(previousDeparture));
            if (stop.hasArrive() && stop.hasDepart() && stop.depart() < stop.arrive())
                problems.add(where + ": leaves at " + time(stop.depart()) + ", before it arrives at " + time(stop.arrive()));
            if (!stop.hasArrive() && stop.hasDepart() && previousDeparture != Stop.NONE && stop.depart() <= previousDeparture)
                problems.add(where + ": leaves at " + time(stop.depart())
                    + ", which is not after the train leaves the stop before at " + time(previousDeparture));
            if (stop.hasDepart())
                previousDeparture = stop.depart();

            // one way along the line (a loop line has no end to turn back at)
            if (line != null && !line.loop() && position >= 0) {
                if (previousPosition >= 0 && position != previousPosition) {
                    int step = Integer.signum(position - previousPosition);
                    if (direction == 0)
                        direction = step;
                    else if (step != direction && !turnedBack) {
                        problems.add(where + ": turns back along line \"" + line.id()
                            + "\"; a train goes one way along a line, and a loop line is marked as one");
                        turnedBack = true;
                    }
                }
                previousPosition = position;
            }
        }
    }

    // ------------------------------------------------------------------ waits

    /**
     * The trains waited for. Each has to leave the station, or run through it, strictly before the train
     * that waits for it leaves; so waits never go round in a ring, each going back in time.
     */
    private void checkWaits(Map<String, Line> lineById, Map<String, Train> trainByNumber, List<String> problems) {
        for (int i = 0; i < trains.size(); i++) {
            Train train = trains.get(i);
            String label = label("train", "trains", i, train.number());
            List<Stop> stops = train.stops();
            for (int k = 0; k < stops.size(); k++) {
                Stop stop = stops.get(k);
                if (stop.waits().isEmpty())
                    continue;
                String where = label + ", stop " + (k + 1) + " \"" + stop.station() + "\"";
                if (k == stops.size() - 1) {
                    problems.add(where + ": is the last stop, which the train does not leave, so it waits for no train");
                    continue;
                }
                Set<String> seen = new HashSet<>();
                for (Wait wait : stop.waits()) {
                    String at = wait.station(stop.station());
                    String what = where + ": waits for train \"" + wait.train() + "\" at \"" + at + "\"";
                    if (!seen.add(wait.train() + "\n" + at)) {
                        problems.add(what + " twice");
                        continue;
                    }
                    if (wait.hasMaxWait() && (wait.maxWait() < 0 || wait.maxWait() > MAX_WAIT_SECONDS))
                        problems.add(what + ": the longest wait must be 0 to " + MAX_WAIT_SECONDS + " seconds");
                    if (wait.train().equals(train.number())) {
                        problems.add(what + ", which is itself");
                        continue;
                    }
                    Train other = trainByNumber.get(wait.train());
                    if (other == null) {
                        problems.add(what + ", which is not in the timetable");
                        continue;
                    }
                    Line line = lineById.get(other.line());
                    if (line == null || other.stops().size() < 2)
                        continue; // said of that train
                    if (!line.stations().contains(at)) {
                        problems.add(what + ", which is not a station of its line \"" + line.id() + "\"");
                        continue;
                    }
                    List<Integer> leaves = leavesOrRunsThrough(other, line, at);
                    if (leaves.isEmpty())
                        problems.add(what + ", which neither leaves it nor runs through it");
                    else if (stop.hasDepart() && leaves.stream().noneMatch(time -> time < stop.depart()))
                        problems.add(what + ", which does not leave it or run through it before this train leaves at " + time(stop.depart()));
                }
            }
        }
    }

    /**
     * When {@code train} leaves {@code station} of its {@code line}, or leaves the stop before it to run
     * through it, as {@code ServiceTime} seconds, in the order of its stops.
     */
    public static List<Integer> leavesOrRunsThrough(Train train, Line line, String station) {
        List<String> names = train.stops().stream().map(Stop::station).toList();
        List<Integer> leaves = new ArrayList<>();
        for (int s = 0; s + 1 < names.size(); s++) {
            Stop stop = train.stops().get(s);
            if (stop.hasDepart() && (stop.station().equals(station) || line.runsThrough(names, s, station)))
                leaves.add(stop.depart());
        }
        return leaves;
    }

    // ------------------------------------------------------------------ duties

    private void checkDuties(Map<String, Train> trainByNumber, List<String> problems) {
        Set<String> ids = new HashSet<>();
        Map<String, String> dutyOfTrain = new HashMap<>();
        for (int i = 0; i < duties.size(); i++) {
            Duty duty = duties.get(i);
            String label = label("duty", "duties", i, duty.id());
            if (!isIdentifier(duty.id()))
                problems.add(label + ": " + idRule("id"));
            else if (!ids.add(duty.id()))
                problems.add(label + ": another duty has the same id");
            if (!isText(duty.depot()))
                problems.add(label + ": the depot, a station name, " + TEXT_RULE);
            if (duty.trains().isEmpty())
                problems.add(label + ": needs at least 1 train");

            Train previous = null;
            for (String number : duty.trains()) {
                Train train = trainByNumber.get(number);
                if (train == null) {
                    problems.add(label + ": there is no train \"" + number + "\"");
                    previous = null;
                    continue;
                }
                String owner = dutyOfTrain.putIfAbsent(number, duty.id());
                if (owner != null)
                    problems.add(label + ": train \"" + number + "\" is "
                        + (owner.equals(duty.id()) ? "listed twice in this duty" : "also in duty \"" + owner + "\""));
                if (previous != null)
                    checkHandover(label, previous, train, problems);
                previous = train;
            }
            if (isText(duty.depot()))
                checkDepot(label, duty, trainByNumber, problems);
        }
    }

    /** The duty leaves the depot with its first train and comes back with its last; a deadhead train gets it there. */
    private static void checkDepot(String label, Duty duty, Map<String, Train> trainByNumber, List<String> problems) {
        if (duty.trains().isEmpty())
            return;
        Train first = trainByNumber.get(duty.trains().getFirst());
        if (first != null && !first.stops().isEmpty() && !first.stops().getFirst().station().equals(duty.depot()))
            problems.add(label + ": the first train, \"" + first.number() + "\", starts at \"" + first.stops().getFirst().station()
                + "\", not at the depot \"" + duty.depot() + "\"");
        Train last = trainByNumber.get(duty.trains().getLast());
        if (last != null && !last.stops().isEmpty() && !last.stops().getLast().station().equals(duty.depot()))
            problems.add(label + ": the last train, \"" + last.number() + "\", ends at \"" + last.stops().getLast().station()
                + "\", not at the depot \"" + duty.depot() + "\"");
    }

    /** The train that goes on from where {@code before} ended must be at that station when it leaves. */
    private static void checkHandover(String label, Train before, Train next, List<String> problems) {
        if (before.stops().isEmpty() || next.stops().isEmpty())
            return;
        Stop end = before.stops().getLast();
        Stop start = next.stops().getFirst();
        if (!end.station().equals(start.station()))
            problems.add(label + ": train \"" + next.number() + "\" starts at \"" + start.station()
                + "\" but the train before it, \"" + before.number() + "\", ends at \"" + end.station() + "\"");
        else if (end.hasArrive() && start.hasDepart() && start.depart() <= end.arrive())
            problems.add(label + ": train \"" + next.number() + "\" leaves at " + time(start.depart())
                + ", which is not after train \"" + before.number() + "\" arrives at " + time(end.arrive()));
    }

    // ------------------------------------------------------------------ small things

    private static final String TEXT_RULE = "must not be empty, start or end with a space, or contain a control character";

    private static final String STOP_RULE = "must be 0 to " + MAX_STOP_SECONDS + " seconds";

    private static String idRule(String what) {
        return "the " + what + " must be 1 to " + MAX_ID_LENGTH
            + " characters, with no space at either end and no control character";
    }

    private static boolean isText(String text) {
        return !text.isEmpty() && text.equals(text.strip()) && text.chars().noneMatch(Character::isISOControl);
    }

    private static boolean isIdentifier(String text) {
        return isText(text) && text.length() <= MAX_ID_LENGTH;
    }

    private static boolean isTime(int seconds) {
        return seconds >= 0 && seconds < ServiceTime.LIMIT;
    }

    private static boolean isStopSeconds(int seconds) {
        return seconds >= 0 && seconds <= MAX_STOP_SECONDS;
    }

    private static String time(int seconds) {
        return ServiceTime.show(seconds);
    }

    /** {@code line "central"}, or {@code lines[2]} for one that has no usable id to name it by. */
    private static String label(String singular, String plural, int index, String id) {
        return id.isBlank() ? plural + "[" + index + "]" : singular + " \"" + id + "\"";
    }
}
