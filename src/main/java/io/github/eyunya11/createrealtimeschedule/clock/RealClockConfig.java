package io.github.eyunya11.createrealtimeschedule.clock;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Per-world settings of the clock, in {@code serverconfig/} of the world.
 *
 * <p>The getters answer with the default while the config has not loaded, which is the case in
 * unit tests and for the instant before a server has read its files.
 */
public final class RealClockConfig {

    public static final String DEFAULT_START_TIME = "06:00";

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

    public static final ModConfigSpec SPEC = BUILDER.build();

    private RealClockConfig() {}

    public static boolean syncSky() {
        return SPEC.isLoaded() ? SYNC_SKY.get() : true;
    }

    /** The configured start time as ticks since 00:00. */
    public static long startTimeTicks() {
        String text = SPEC.isLoaded() ? START_TIME.get() : DEFAULT_START_TIME;
        return RealClock.parseTimeOfDay(text).orElseGet(() -> RealClock.parseTimeOfDay(DEFAULT_START_TIME).getAsLong());
    }

    public static boolean alignOnStart() {
        return SPEC.isLoaded() && ALIGN_ON_START.get();
    }
}
