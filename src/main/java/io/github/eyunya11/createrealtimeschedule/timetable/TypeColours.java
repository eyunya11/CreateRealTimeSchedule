package io.github.eyunya11.createrealtimeschedule.timetable;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The colours of the types of train: the ones the editor offers to pick from, and the one a type that
 * has no colour of its own is drawn in, picked from its name. The editor's {@code timetable.js} has the
 * same list and picks the same way, so that a type has the same colour in the game and in the editor.
 * They are as dark as reads on the game's dark diagram and on the editor's light and dark pages alike.
 */
public final class TypeColours {

    /** The colours, in the order they are offered. */
    public static final List<String> TEMPLATE = List.of("#3b7dd8", "#3a9d5d", "#d9822b", "#c94c4c", "#2f9e9e", "#b59a1c", "#a65fb0");

    private static final Pattern COLOUR = Pattern.compile("#[0-9a-fA-F]{6}");

    private TypeColours() {}

    /** The colour of the template picked from {@code type}'s name, the same for the same name. */
    public static String byName(String type) {
        return TEMPLATE.get(Math.floorMod(type.hashCode(), TEMPLATE.size()));
    }

    /** Whether {@code text} is a colour as a type has it: {@code #} and six hexadecimal digits. */
    public static boolean isColour(String text) {
        return COLOUR.matcher(text).matches();
    }

    /** {@code colour}, as {@code #rrggbb}, as an opaque ARGB colour; it has to be one ({@link #isColour}). */
    public static int argb(String colour) {
        return 0xFF000000 | Integer.parseInt(colour.substring(1), 16);
    }
}
