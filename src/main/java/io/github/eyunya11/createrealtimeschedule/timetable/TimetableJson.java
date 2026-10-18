package io.github.eyunya11.createrealtimeschedule.timetable;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/**
 * Reads and writes a {@link Timetable} as JSON, with no Minecraft types in it so that it can be
 * tested without a game. The format is described in the proposal, in the section on the timetable
 * JSON.
 *
 * <p>The reader is strict, because the text is often written by hand or pasted from another tool and
 * a mistake that is let through turns up later as a train that does not run: comments, single quotes,
 * unquoted names, trailing commas and text after the end are errors; so is a member that appears
 * twice, a member the format does not have, and a value of the wrong kind. It does not stop at the first
 * mistake but collects them, each with its place in the text, and only when the shape is right does it
 * go on to what {@link Timetable#problems()} checks. A text is accepted or refused as a whole.
 */
public final class TimetableJson {

    /** The most problems reported at once; the rest are counted. */
    private static final int MAX_PROBLEMS = 50;

    /** The deepest nesting read; the format needs 4. */
    private static final int MAX_DEPTH = 16;

    private static final Pattern WHOLE_NUMBER = Pattern.compile("-?\\d{1,9}");

    private TimetableJson() {}

    /**
     * What reading a text came to.
     *
     * @param timetable the timetable, or null if there is anything wrong with the text
     * @param problems  what is wrong, in words and with the place in the JSON, e.g.
     *                  {@code trains[2].stops[1].depart: ...}; empty if nothing is
     */
    public record Result(@Nullable Timetable timetable, List<String> problems) {
        public Result {
            problems = List.copyOf(problems);
        }

        public boolean ok() {
            return timetable != null;
        }
    }

    // ------------------------------------------------------------------ reading

    public static Result parse(String text) {
        Problems problems = new Problems();
        Object root;
        try (JsonReader in = new JsonReader(new StringReader(text))) {
            root = readValue(in, "", 0, problems);
            if (in.peek() != JsonToken.END_DOCUMENT)
                problems.add("", "there is more text after the end of the timetable");
        } catch (IOException | IllegalStateException e) {
            // Nothing after a syntax error can be trusted, so this is the last problem.
            problems.add("", "not valid JSON: " + firstLine(String.valueOf(e.getMessage())));
            return new Result(null, problems.all());
        }

        Timetable timetable = build(root, problems);
        if (timetable != null && problems.isEmpty())
            for (String problem : timetable.problems())
                problems.add("", problem);
        return problems.isEmpty() ? new Result(timetable, List.of()) : new Result(null, problems.all());
    }

    private static String firstLine(String message) {
        // Gson's own wording tells to switch lenient mode on, which is not an option offered here.
        String line = message.lines().findFirst().orElse(message);
        return line.replace("Use JsonReader.setLenient(true) to accept malformed JSON",
            "this is not strict JSON (no comments, single quotes, unquoted names, trailing commas or text after the end)");
    }

    /** A JSON number as the text it was written with; the only number the format has is the version. */
    private record NumberText(String text) {}

    /** Reads one value into maps, lists, strings, booleans, {@link NumberText}s and null. */
    private static @Nullable Object readValue(JsonReader in, String path, int depth, Problems problems) throws IOException {
        if (depth > MAX_DEPTH)
            throw new IOException("nested more than " + MAX_DEPTH + " levels deep at " + in.getPath());
        return switch (in.peek()) {
            case BEGIN_OBJECT -> readObject(in, path, depth, problems);
            case BEGIN_ARRAY -> readArray(in, path, depth, problems);
            case STRING -> in.nextString();
            case NUMBER -> new NumberText(in.nextString());
            case BOOLEAN -> in.nextBoolean();
            case NULL -> {
                in.nextNull();
                yield null;
            }
            default -> throw new IOException("unexpected " + in.peek() + " at " + in.getPath());
        };
    }

    private static Map<String, Object> readObject(JsonReader in, String path, int depth, Problems problems) throws IOException {
        Map<String, Object> object = new LinkedHashMap<>();
        in.beginObject();
        while (in.hasNext()) {
            String key = in.nextName();
            String keyPath = path.isEmpty() ? key : path + "." + key;
            Object value = readValue(in, keyPath, depth + 1, problems);
            if (object.containsKey(key))
                problems.add(keyPath, "appears more than once");
            else
                object.put(key, value);
        }
        in.endObject();
        return object;
    }

    private static List<Object> readArray(JsonReader in, String path, int depth, Problems problems) throws IOException {
        List<Object> array = new ArrayList<>();
        in.beginArray();
        while (in.hasNext())
            array.add(readValue(in, path + "[" + array.size() + "]", depth + 1, problems));
        in.endArray();
        return array;
    }

    // ------------------------------------------------------------------ from the values to the records

    private static @Nullable Timetable build(@Nullable Object root, Problems problems) {
        Fields top = Fields.of(root, "", problems);
        if (top == null)
            return null;

        String format = top.string("format", true);
        if (format != null && !format.equals(Timetable.FORMAT))
            problems.add("format", "must be \"" + Timetable.FORMAT + "\"; this is not a timetable of this mod");
        if (!readVersion(top, problems))
            return null;

        String name = top.string("name", false);
        List<Timetable.Line> lines = new ArrayList<>();
        for (Fields line : top.objects("lines", false))
            lines.add(readLine(line));
        List<Timetable.Train> trains = new ArrayList<>();
        for (Fields train : top.objects("trains", false))
            trains.add(readTrain(train));
        List<Timetable.Duty> duties = new ArrayList<>();
        for (Fields duty : top.objects("duties", false))
            duties.add(readDuty(duty));
        top.finish();
        return new Timetable(name == null ? "" : name, lines, trains, duties);
    }

    /** False if the text is of a newer version: nothing else in it can be read, and that is all there is to say. */
    private static boolean readVersion(Fields top, Problems problems) {
        Object version = top.get("version");
        if (version == null) {
            problems.add("version", "is missing");
            return true;
        }
        if (!(version instanceof NumberText number) || !WHOLE_NUMBER.matcher(number.text()).matches()) {
            problems.add("version", "must be a whole number");
            return true;
        }
        int value = Integer.parseInt(number.text());
        if (value > Timetable.VERSION) {
            problems.add("version", "is " + value + ", but this mod reads timetables up to version " + Timetable.VERSION
                + "; the mod needs updating to read this one");
            return false;
        }
        if (value < 1)
            problems.add("version", "must be 1 or more");
        return true;
    }

    private static Timetable.Line readLine(Fields f) {
        String id = orEmpty(f.string("id", true));
        String name = f.string("name", false);
        List<String> stations = f.strings("stations");
        boolean loop = f.bool("loop", false);
        f.finish();
        return new Timetable.Line(id, name == null ? id : name, stations, loop);
    }

    private static Timetable.Train readTrain(Fields f) {
        String number = orEmpty(f.string("number", true));
        String line = orEmpty(f.string("line", true));
        String type = orEmpty(f.string("type", false));
        List<Timetable.Stop> stops = new ArrayList<>();
        for (Fields stop : f.objects("stops", true))
            stops.add(readStop(stop));
        f.finish();
        return new Timetable.Train(number, line, type, stops);
    }

    private static Timetable.Stop readStop(Fields f) {
        String station = orEmpty(f.string("station", true));
        int arrive = readTime(f, "arrive");
        int depart = readTime(f, "depart");
        f.finish();
        return new Timetable.Stop(station, arrive, depart);
    }

    private static int readTime(Fields f, String key) {
        String text = f.string(key, false);
        if (text == null)
            return Timetable.Stop.NONE;
        OptionalInt seconds = ServiceTime.parse(text);
        if (seconds.isEmpty()) {
            f.problem(key, "must be a time such as \"08:10\" or \"08:10:30\", up to 47:59:59");
            return Timetable.Stop.NONE;
        }
        return seconds.getAsInt();
    }

    private static Timetable.Duty readDuty(Fields f) {
        String id = orEmpty(f.string("id", true));
        String depot = orEmpty(f.string("depot", true));
        List<String> trains = f.strings("trains");
        f.finish();
        return new Timetable.Duty(id, depot, trains);
    }

    private static String orEmpty(@Nullable String text) {
        return text == null ? "" : text;
    }

    /** The members of one JSON object, which are checked off as they are read so that the rest can be reported. */
    private static final class Fields {
        private final Map<String, Object> values;
        private final String path;
        private final Problems problems;
        private final Set<String> used = new HashSet<>();

        private Fields(Map<String, Object> values, String path, Problems problems) {
            this.values = values;
            this.path = path;
            this.problems = problems;
        }

        @SuppressWarnings("unchecked")
        static @Nullable Fields of(@Nullable Object value, String path, Problems problems) {
            if (value instanceof Map<?, ?> map)
                return new Fields((Map<String, Object>) map, path, problems);
            problems.add(path, path.isEmpty() ? "the timetable must be a JSON object" : "must be an object");
            return null;
        }

        private String at(String key) {
            return path.isEmpty() ? key : path + "." + key;
        }

        @Nullable Object get(String key) {
            used.add(key);
            return values.get(key);
        }

        @Nullable String string(String key, boolean required) {
            Object value = get(key);
            if (value == null) {
                if (required)
                    problems.add(at(key), "is missing");
                return null;
            }
            if (value instanceof String text)
                return text;
            problems.add(at(key), "must be a string");
            return null;
        }

        boolean bool(String key, boolean fallback) {
            Object value = get(key);
            if (value == null)
                return fallback;
            if (value instanceof Boolean flag)
                return flag;
            problems.add(at(key), "must be true or false");
            return fallback;
        }

        /** The elements of an array of objects; none if the array is not there. */
        List<Fields> objects(String key, boolean required) {
            List<Fields> out = new ArrayList<>();
            Object value = get(key);
            if (value == null) {
                if (required)
                    problems.add(at(key), "is missing");
                return out;
            }
            if (!(value instanceof List<?> list)) {
                problems.add(at(key), "must be an array");
                return out;
            }
            for (int i = 0; i < list.size(); i++) {
                Fields element = Fields.of(list.get(i), at(key) + "[" + i + "]", problems);
                if (element != null)
                    out.add(element);
            }
            return out;
        }

        /** The elements of an array of strings; the array has to be there. */
        List<String> strings(String key) {
            List<String> out = new ArrayList<>();
            Object value = get(key);
            if (value == null) {
                problems.add(at(key), "is missing");
                return out;
            }
            if (!(value instanceof List<?> list)) {
                problems.add(at(key), "must be an array of strings");
                return out;
            }
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) instanceof String text)
                    out.add(text);
                else
                    problems.add(at(key) + "[" + i + "]", "must be a string");
            }
            return out;
        }

        void problem(String key, String message) {
            problems.add(at(key), message);
        }

        /** Reports every member that nothing asked for. */
        void finish() {
            for (String key : values.keySet())
                if (!used.contains(key))
                    problems.add(at(key), "is not a member of this format");
        }
    }

    private static final class Problems {
        private final List<String> list = new ArrayList<>();
        private int dropped;

        void add(String path, String message) {
            if (list.size() >= MAX_PROBLEMS)
                dropped++;
            else
                list.add(path.isEmpty() ? message : path + ": " + message);
        }

        boolean isEmpty() {
            return list.isEmpty();
        }

        List<String> all() {
            if (dropped == 0)
                return list;
            List<String> all = new ArrayList<>(list);
            all.add("... and " + dropped + " more");
            return all;
        }
    }

    // ------------------------------------------------------------------ writing

    /**
     * The timetable as JSON, laid out the same way every time: two spaces of indent, the members in a
     * fixed order, and a stop or a list of names on one line. Members that have their default value
     * ({@code loop} false, {@code type} empty, a time a stop does not have) are left out. A timetable that
     * {@link Timetable#problems()} finds fault with is written as it is, and is refused when read back.
     */
    public static String write(Timetable timetable) {
        MapNode root = new MapNode();
        root.put("format", text(Timetable.FORMAT));
        root.put("version", new Leaf(Integer.toString(Timetable.VERSION)));
        root.put("name", text(timetable.name()));

        ListNode lines = new ListNode();
        for (Timetable.Line line : timetable.lines()) {
            MapNode node = new MapNode();
            node.put("id", text(line.id()));
            node.put("name", text(line.name()));
            node.put("stations", texts(line.stations()));
            if (line.loop())
                node.put("loop", new Leaf("true"));
            lines.add(node);
        }
        root.put("lines", lines);

        ListNode trains = new ListNode();
        for (Timetable.Train train : timetable.trains()) {
            MapNode node = new MapNode();
            node.put("number", text(train.number()));
            node.put("line", text(train.line()));
            if (!train.type().isEmpty())
                node.put("type", text(train.type()));
            ListNode stops = new ListNode();
            for (Timetable.Stop stop : train.stops()) {
                MapNode stopNode = new MapNode();
                stopNode.put("station", text(stop.station()));
                if (stop.hasArrive())
                    stopNode.put("arrive", text(ServiceTime.format(stop.arrive())));
                if (stop.hasDepart())
                    stopNode.put("depart", text(ServiceTime.format(stop.depart())));
                stops.add(stopNode);
            }
            node.put("stops", stops);
            trains.add(node);
        }
        root.put("trains", trains);

        ListNode duties = new ListNode();
        for (Timetable.Duty duty : timetable.duties()) {
            MapNode node = new MapNode();
            node.put("id", text(duty.id()));
            node.put("depot", text(duty.depot()));
            node.put("trains", texts(duty.trains()));
            duties.add(node);
        }
        root.put("duties", duties);

        StringBuilder out = new StringBuilder();
        render(root, 0, out);
        return out.append('\n').toString();
    }

    /** What is written is a tree of these, so that the layout can be decided with the whole of a node in view. */
    private sealed interface Node permits Leaf, ListNode, MapNode {}

    /** A value on one line, already in JSON. */
    private record Leaf(String json) implements Node {}

    private record ListNode(List<Node> items) implements Node {
        ListNode() {
            this(new ArrayList<>());
        }

        void add(Node item) {
            items.add(item);
        }
    }

    private record MapNode(Map<String, Node> entries) implements Node {
        MapNode() {
            this(new LinkedHashMap<>());
        }

        void put(String key, Node value) {
            entries.put(key, value);
        }
    }

    private static Leaf text(String value) {
        return new Leaf(new JsonPrimitive(value).toString());
    }

    private static ListNode texts(List<String> values) {
        ListNode list = new ListNode();
        for (String value : values)
            list.add(text(value));
        return list;
    }

    /** Wider than this and a list of values goes one to a line. */
    private static final int WIDTH = 100;

    private static void render(Node node, int depth, StringBuilder out) {
        String flat = flat(node);
        if (flat != null && depth * 2 + flat.length() <= WIDTH) {
            out.append(flat);
            return;
        }
        switch (node) {
            case Leaf leaf -> out.append(leaf.json());
            case ListNode list -> {
                out.append("[\n");
                for (int i = 0; i < list.items().size(); i++) {
                    out.append("  ".repeat(depth + 1));
                    render(list.items().get(i), depth + 1, out);
                    out.append(i < list.items().size() - 1 ? ",\n" : "\n");
                }
                out.append("  ".repeat(depth)).append(']');
            }
            case MapNode map -> {
                out.append("{\n");
                int left = map.entries().size();
                for (Map.Entry<String, Node> entry : map.entries().entrySet()) {
                    out.append("  ".repeat(depth + 1)).append(quote(entry.getKey())).append(": ");
                    render(entry.getValue(), depth + 1, out);
                    out.append(--left > 0 ? ",\n" : "\n");
                }
                out.append("  ".repeat(depth)).append('}');
            }
        }
    }

    /** The node on one line, or null if there is anything but plain values in it. */
    private static @Nullable String flat(Node node) {
        return switch (node) {
            case Leaf leaf -> leaf.json();
            case ListNode list -> {
                StringJoiner joiner = new StringJoiner(", ", "[", "]");
                for (Node item : list.items()) {
                    if (!(item instanceof Leaf leaf))
                        yield null;
                    joiner.add(leaf.json());
                }
                yield joiner.toString();
            }
            case MapNode map -> {
                StringJoiner joiner = new StringJoiner(", ", "{", "}");
                for (Map.Entry<String, Node> entry : map.entries().entrySet()) {
                    if (!(entry.getValue() instanceof Leaf leaf))
                        yield null;
                    joiner.add(quote(entry.getKey()) + ": " + leaf.json());
                }
                yield joiner.toString();
            }
        };
    }

    private static String quote(String value) {
        return new JsonPrimitive(value).toString();
    }
}
