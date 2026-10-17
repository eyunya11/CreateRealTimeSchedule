package io.github.eyunya11.createrealtimeschedule.clock;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;

/**
 * Keeps the overworld's sky on the clock.
 *
 * <p>The way vanilla moves the sun is by adding one to the day time every tick while the game rule
 * {@code doDaylightCycle} is on, and by jumping to the next morning when everyone is asleep, which
 * also needs the rule on. So while the sync is enabled the rule is held off and the day time is set
 * from the clock instead: sleeping then skips nothing, and {@code /time set} is put back on the next
 * tick. The rule's value from before is remembered in the world and restored when the sync is turned
 * off, so the setting is the player's again afterwards.
 *
 * <p>Takes the on/off state as an argument rather than reading the config, so that it can be tried
 * both ways.
 */
public final class SkySync {

    private SkySync() {}

    /** Call once per server tick. */
    public static void apply(MinecraftServer server, boolean enabled) {
        RealClockData data = ClockService.data(server);
        GameRules.BooleanValue daylightCycle = server.getGameRules().getRule(GameRules.RULE_DAYLIGHT);

        if (enabled) {
            if (data.skyPreviousRule() == null)
                data.rememberSkyRule(daylightCycle.get());
            // Checked every tick: anyone can switch the rule back on, and a rule left on would move
            // the sun twice, once by vanilla and once by us.
            if (daylightCycle.get())
                daylightCycle.set(false, server);

            ServerLevel overworld = server.overworld();
            long skyTime = RealClock.skyDayTime(ClockService.now(server));
            if (overworld.getDayTime() != skyTime)
                overworld.setDayTime(skyTime);
        } else if (data.skyPreviousRule() != null) {
            boolean previous = data.skyPreviousRule();
            if (daylightCycle.get() != previous)
                daylightCycle.set(previous, server);
            data.forgetSkyRule();
        }
    }
}
