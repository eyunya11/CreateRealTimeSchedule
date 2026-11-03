package io.github.eyunya11.createrealtimeschedule.map;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Lamp;

/**
 * What a signal shows, as the lamps a map draws for it. Create's signals show one lamp: red, yellow
 * or green. The colour light signals of Create Train Next show an aspect with one or two lamps, from
 * stop to high-speed proceed. Both come here by the names of their enums, so that this does not
 * depend on either mod.
 */
public final class SignalLooks {

    /** The kind of a side shown by Create's own signals. */
    public static final String CREATE = "create";

    private SignalLooks() {}

    /**
     * The lamp of a state of Create's signal ({@code SignalBlockEntity.SignalState}). A signal that is
     * not set up right ({@code INVALID}) flashes red in the game, and is drawn red.
     */
    public static List<Lamp> create(String state) {
        return switch (state) {
            case "GREEN" -> List.of(Lamp.GREEN);
            case "YELLOW" -> List.of(Lamp.YELLOW);
            default -> List.of(Lamp.RED);
        };
    }

    /**
     * The lamps of an aspect of Create Train Next's colour light signal ({@code SignalAspect}), from the
     * top; null for one this does not know.
     */
    public static @Nullable List<Lamp> aspect(String aspect) {
        return switch (aspect) {
            case "STOP" -> List.of(Lamp.RED);
            case "RESTRICTED" -> List.of(Lamp.YELLOW, Lamp.YELLOW);
            case "APPROACH" -> List.of(Lamp.YELLOW);
            case "REDUCED_SPEED" -> List.of(Lamp.YELLOW, Lamp.GREEN);
            case "PROCEED" -> List.of(Lamp.GREEN);
            case "HIGH_SPEED_PROCEED" -> List.of(Lamp.GREEN, Lamp.GREEN);
            default -> null;
        };
    }

    /**
     * The kind of a colour light signal of Create Train Next by its type ({@code SignalType}, which
     * counts its aspects): {@code light2} to {@code light6}; null for a type this does not know.
     */
    public static @Nullable String light(String type) {
        return switch (type) {
            case "TWO" -> "light2";
            case "THREE" -> "light3";
            case "FOUR" -> "light4";
            case "FIVE" -> "light5";
            case "SIX" -> "light6";
            default -> null;
        };
    }
}
