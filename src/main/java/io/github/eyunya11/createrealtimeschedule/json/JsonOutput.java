package io.github.eyunya11.createrealtimeschedule.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonPrimitive;

/**
 * JSON text laid out the same way every time, with no Minecraft types in it so that it can be
 * tested without a game. What is written is a tree of {@link Node}s, so that the layout can be
 * decided with the whole of a node in view: two spaces of indent, the members in the order they
 * were put, and a list or an object that holds only plain values on one line when it fits in
 * {@value #WIDTH} columns, one member to a line when it does not.
 */
public final class JsonOutput {

    /** Wider than this and a list of values goes one to a line. */
    public static final int WIDTH = 100;

    private JsonOutput() {}

    public sealed interface Node permits Leaf, ListNode, MapNode {}

    /** A value on one line, already in JSON. */
    public record Leaf(String json) implements Node {}

    public record ListNode(List<Node> items) implements Node {
        public ListNode() {
            this(new ArrayList<>());
        }

        public ListNode add(Node item) {
            items.add(item);
            return this;
        }
    }

    public record MapNode(Map<String, Node> entries) implements Node {
        public MapNode() {
            this(new LinkedHashMap<>());
        }

        public MapNode put(String key, Node value) {
            entries.put(key, value);
            return this;
        }
    }

    /** A string, quoted and escaped. Non-ASCII characters are kept as they are. */
    public static Leaf text(String value) {
        return new Leaf(quote(value));
    }

    /** A value written as it is: a number, {@code true}. */
    public static Leaf raw(String json) {
        return new Leaf(json);
    }

    public static ListNode texts(List<String> values) {
        ListNode list = new ListNode();
        for (String value : values)
            list.add(text(value));
        return list;
    }

    /**
     * A node that holds only plain values (and lists of them) as one value on one line, however wide
     * it is, so that an object it is a member of can still be laid out on one line.
     *
     * @throws IllegalArgumentException if the node holds an object
     */
    public static Leaf inline(Node node) {
        String flat = flat(node);
        if (flat == null)
            throw new IllegalArgumentException("a node with objects in it cannot be put on one line");
        return new Leaf(flat);
    }

    public static String quote(String value) {
        return new JsonPrimitive(value).toString();
    }

    /** The node as text, with a line end after it. */
    public static String render(Node root) {
        StringBuilder out = new StringBuilder();
        render(root, 0, out);
        return out.append('\n').toString();
    }

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
}
