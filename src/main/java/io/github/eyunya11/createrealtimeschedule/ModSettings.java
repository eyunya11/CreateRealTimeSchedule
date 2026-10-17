package io.github.eyunya11.createrealtimeschedule;

import java.util.function.Supplier;

import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The mod's per-world settings (the clock, the sky and the wait condition), in
 * {@code serverconfig/createrealtimeschedule-server.toml} of the world.
 *
 * <p>NeoForge sends the server's config files to a client when it joins, so a client reads the
 * server's values while it is connected. This has been read in NeoForge's code but not tried with a
 * real client.
 *
 * <p>The getters answer with the default while the config is not loaded, which is the case in unit
 * tests, on a client that is not connected to a world, and for the instant before a server has read
 * its files. They can be called from any thread (Create builds conditions while it decodes packets),
 * and the config can unload between the check and the read, so a read that finds the config gone
 * answers with the default as well.
 */
public final class ModSettings {

    public static final String DEFAULT_START_TIME = "06:00";
    /** The minimum stop a new real_time_of_day condition starts with, until the config says otherwise. */
    public static final int DEFAULT_MINIMUM_STOP_SECONDS = 20;
    /** The longest minimum stop the schedule screen and the config allow. */
    public static final int MAX_MINIMUM_STOP_SECONDS = 600;

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue SYNC_SKY = BUILDER
        .comment("Make the sky follow the real-time clock.",
            "While this is on, the game rule doDaylightCycle is kept off and the day time is set from the",
            "clock every tick, so sleeping does not skip the night and /time set is overwritten.",
            "Turning it off hands the sky back to vanilla and puts doDaylightCycle back as it was.")
        .define("sync_sky", true);

    private static final ModConfigSpec.ConfigValue<String> START_TIME = BUILDER
        .comment("The time of day, as H:MM or H:MM:SS, the clock starts at in a world that has no saved clock yet.")
        .define("start_time", DEFAULT_START_TIME, value -> value instanceof String text && RealClock.parseTimeOfDay(text).isPresent());

    private static final ModConfigSpec.BooleanValue ALIGN_ON_START = BUILDER
        .comment("When the server starts, set the clock to the server's own time of day.",
            "The clock only runs while the server does, so a restart leaves it behind real time.")
        .define("align_on_start", false);

    private static final ModConfigSpec.IntValue DEFAULT_MINIMUM_STOP = BUILDER
        .comment("The minimum stop, in seconds, that a new real_time_of_day condition starts with in the schedule screen.",
            "It is only the starting value: a condition that has been made keeps the value it was given.",
            "A train that arrives late stops for the minimum stop at least, however late it is.")
        .defineInRange("default_minimum_stop_seconds", DEFAULT_MINIMUM_STOP_SECONDS, 0, MAX_MINIMUM_STOP_SECONDS);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ModSettings() {}

    public static boolean syncSky() {
        return read(SPEC.isLoaded(), SYNC_SKY, true);
    }

    /** The configured start time as ticks since 00:00. */
    public static long startTimeTicks() {
        String text = read(SPEC.isLoaded(), START_TIME, DEFAULT_START_TIME);
        return RealClock.parseTimeOfDay(text).orElseGet(() -> RealClock.parseTimeOfDay(DEFAULT_START_TIME).getAsLong());
    }

    public static boolean alignOnStart() {
        return read(SPEC.isLoaded(), ALIGN_ON_START, false);
    }

    /** The minimum stop, in seconds, that a new real_time_of_day condition starts with. */
    public static int defaultMinimumStopSeconds() {
        return read(SPEC.isLoaded(), DEFAULT_MINIMUM_STOP, DEFAULT_MINIMUM_STOP_SECONDS);
    }

    /**
     * The value, or {@code fallback} if the config is not loaded or is unloaded while it is read.
     * NeoForge's {@code get()} throws {@link IllegalStateException} for a config that is gone when
     * it has no cached value yet, and the config unloads on another thread when a client leaves.
     */
    static <T> T read(boolean loaded, Supplier<? extends T> value, T fallback) {
        if (!loaded)
            return fallback;
        try {
            return value.get();
        } catch (IllegalStateException unloadedMeanwhile) {
            return fallback;
        }
    }
}
