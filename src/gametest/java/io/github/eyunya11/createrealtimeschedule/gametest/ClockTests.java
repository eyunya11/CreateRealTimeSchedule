package io.github.eyunya11.createrealtimeschedule.gametest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.clock.ClockService;
import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.ModSettings;
import io.github.eyunya11.createrealtimeschedule.clock.RealClockData;
import io.github.eyunya11.createrealtimeschedule.clock.SkySync;
import io.github.eyunya11.createrealtimeschedule.network.ClockSyncPayload;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks on the clock and the sky sync against a real server, run with
 * {@code ./gradlew runGameTestServer}.
 *
 * <p>The clock, the game rule and the remembered rule are all shared with every other test and with
 * the per-tick hook, so each test that changes them puts them back exactly as it found them. The
 * hook is live while these run (the config defaults to syncing the sky), which is also what lets
 * {@link #theTickHookKeepsTheSkyOnTheClock} see it work.
 */
@GameTestHolder(Createrealtimeschedule.MODID)
@PrefixGameTestTemplate(false)
public class ClockTests {

    private static final String EMPTY = "empty";

    /** The state these tests borrow from the server, taken once and put back once. */
    private record Saved(long offset, Boolean skyRule, boolean daylightCycle) {

        static Saved of(MinecraftServer server) {
            RealClockData data = ClockService.data(server);
            return new Saved(data.offset(), data.skyPreviousRule(),
                server.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT));
        }

        void restore(MinecraftServer server) {
            RealClockData data = ClockService.data(server);
            data.setOffset(offset);
            if (skyRule == null)
                data.forgetSkyRule();
            else
                data.rememberSkyRule(skyRule);
            server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(daylightCycle, server);
        }
    }

    // ------------------------------------------------------------------ the clock

    /**
     * The clock moves exactly as far as the game time does, which is what keeps it in step with trains.
     *
     * <p>The two tests that wait share the clock, so each has a batch of its own: tests in one batch
     * run side by side, and one would move the clock under the other.
     */
    @GameTest(template = EMPTY, batch = "realtimeclock_follows", timeoutTicks = 100)
    public static void clockFollowsGameTime(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Saved saved = Saved.of(server);
        long gameBefore = server.overworld().getGameTime();
        long clockBefore = ClockService.now(server);
        helper.runAfterDelay(20, () -> {
            long gameDelta = server.overworld().getGameTime() - gameBefore;
            long clockDelta = ClockService.now(server) - clockBefore;
            saved.restore(server);
            helper.assertTrue(gameDelta >= 20, "at least 20 game ticks passed, got " + gameDelta);
            helper.assertValueEqual(gameDelta, clockDelta, "clock ticks passed against game ticks passed");
            helper.succeed();
        });
    }

    /** With the sync on, the per-tick hook is what puts the sky at the clock's time. */
    @GameTest(template = EMPTY, batch = "realtimeclock_hook", timeoutTicks = 100)
    public static void theTickHookKeepsTheSkyOnTheClock(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Saved saved = Saved.of(server);
        ClockService.setTimeOfDay(server, RealClock.ticksOf(18, 0, 0));
        helper.runAfterDelay(20, () -> {
            long expected = RealClock.skyDayTime(ClockService.now(server));
            long actual = server.overworld().getDayTime();
            boolean cycleOn = server.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
            saved.restore(server);
            helper.assertTrue(ModSettings.syncSky(), "sync_sky should default to on");
            // The hook runs at the end of a tick and this at some point in the next, so the sky can
            // be one step behind the clock but no more.
            helper.assertTrue(Math.abs(actual - expected) <= 1, "sky at " + actual + ", clock says " + expected);
            helper.assertTrue(actual % RealClock.VANILLA_TICKS_PER_DAY >= 11_990 && actual % RealClock.VANILLA_TICKS_PER_DAY <= 12_010,
                "18:00 is vanilla 12000, got " + actual);
            helper.assertFalse(cycleOn, "doDaylightCycle is held off while the sky is synced");
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY)
    public static void settingTheTimeKeepsTheDayAndSetsTheTime(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Saved saved = Saved.of(server);
        long dayBefore = RealClock.day(ClockService.now(server));
        ClockService.setTimeOfDay(server, RealClock.ticksOf(12, 34, 56));
        long now = ClockService.now(server);
        saved.restore(server);
        helper.assertValueEqual(dayBefore, RealClock.day(now), "day after setting the time");
        helper.assertValueEqual(RealClock.ticksOf(12, 34, 56), RealClock.timeOfDay(now), "time of day after setting it");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void alignTakesTheTimeFromTheWallClockItIsGiven(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Saved saved = Saved.of(server);
        Clock wall = Clock.fixed(Instant.parse("2026-10-17T03:04:05Z"), ZoneOffset.UTC);
        long returned = ClockService.alignToWallClock(server, wall);
        long now = ClockService.now(server);
        saved.restore(server);
        helper.assertValueEqual(RealClock.ticksOf(3, 4, 5), returned, "time align reports");
        helper.assertValueEqual(RealClock.ticksOf(3, 4, 5), RealClock.timeOfDay(now), "time of day after align");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aNewClockStartsAtTheConfiguredTime(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        RealClockData fresh = new RealClockData();
        helper.assertFalse(fresh.isInitialized(), "a new clock has not been started");
        long gameTime = 4_321_000L;
        ClockService.initialize(fresh, gameTime);
        helper.assertTrue(fresh.isInitialized(), "starting it marks it started");
        helper.assertValueEqual(ModSettings.startTimeTicks(), RealClock.clockTicks(gameTime, fresh.offset()),
            "clock at the moment it is started");
        // Starting it again does not move it.
        long offset = fresh.offset();
        ClockService.initialize(fresh, gameTime + 999);
        helper.assertValueEqual(offset, fresh.offset(), "offset after a second start");
        helper.succeed();
    }

    // ------------------------------------------------------------------ the sky

    @GameTest(template = EMPTY)
    public static void skySyncPutsNoonAtTwelve(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Saved saved = Saved.of(server);
        ServerLevel overworld = server.overworld();
        ClockService.setTimeOfDay(server, RealClock.ticksOf(12, 0, 0));
        SkySync.apply(server, true);
        long sky = overworld.getDayTime();
        // /time set is put back, not obeyed.
        overworld.setDayTime(0L);
        SkySync.apply(server, true);
        long skyAfterTimeSet = overworld.getDayTime();
        boolean cycleOn = server.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
        saved.restore(server);
        helper.assertValueEqual(6_000L, sky % RealClock.VANILLA_TICKS_PER_DAY, "vanilla day time at 12:00");
        helper.assertValueEqual(sky, skyAfterTimeSet, "sky after a /time set");
        helper.assertFalse(cycleOn, "doDaylightCycle while synced");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void skySyncGivesTheRuleBackAsItWas(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Saved saved = Saved.of(server);
        GameRules.BooleanValue rule = server.getGameRules().getRule(GameRules.RULE_DAYLIGHT);
        RealClockData data = ClockService.data(server);

        // Start from a world where the rule is on and nothing is remembered.
        data.forgetSkyRule();
        rule.set(true, server);
        SkySync.apply(server, true);
        boolean heldOff = !rule.get();
        Boolean remembered = data.skyPreviousRule();

        // Someone turns it back on; it is taken off again and the memory is not overwritten.
        rule.set(true, server);
        SkySync.apply(server, true);
        boolean heldOffAgain = !rule.get();
        Boolean rememberedAfter = data.skyPreviousRule();

        SkySync.apply(server, false);
        boolean restored = rule.get();
        Boolean forgotten = data.skyPreviousRule();

        // And a rule that was off before stays off when the sync ends.
        rule.set(false, server);
        SkySync.apply(server, true);
        SkySync.apply(server, false);
        boolean stillOff = !rule.get();

        saved.restore(server);
        helper.assertTrue(heldOff, "the rule is taken off when the sync starts");
        helper.assertValueEqual(Boolean.TRUE, remembered, "remembered rule");
        helper.assertTrue(heldOffAgain, "the rule is taken off again after someone turns it on");
        helper.assertValueEqual(Boolean.TRUE, rememberedAfter, "remembered rule after being turned on again");
        helper.assertTrue(restored, "the rule is back on when the sync ends");
        helper.assertTrue(forgotten == null, "nothing is remembered once the rule is restored");
        helper.assertTrue(stillOff, "a rule that was off stays off");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void withTheSyncOffTheSkyIsLeftAlone(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Saved saved = Saved.of(server);
        ServerLevel overworld = server.overworld();
        RealClockData data = ClockService.data(server);
        data.forgetSkyRule();
        SkySync.apply(server, false);
        overworld.setDayTime(5_555L);
        SkySync.apply(server, false);
        long sky = overworld.getDayTime();
        Boolean remembered = data.skyPreviousRule();
        saved.restore(server);
        helper.assertValueEqual(5_555L, sky, "day time with the sync off");
        helper.assertTrue(remembered == null, "nothing is remembered while the sync is off");
        helper.succeed();
    }

    // -------------------------------------------------------- saving, sending, commands

    @GameTest(template = EMPTY)
    public static void theClockSurvivesBeingSaved(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        var registries = server.registryAccess();

        RealClockData data = new RealClockData();
        data.setOffset(-123_456_789L);
        data.rememberSkyRule(true);
        RealClockData loaded = RealClockData.load(data.save(new CompoundTag(), registries), registries);
        helper.assertTrue(loaded.isInitialized(), "a saved clock is still started");
        helper.assertValueEqual(-123_456_789L, loaded.offset(), "offset after a save and load");
        helper.assertValueEqual(Boolean.TRUE, loaded.skyPreviousRule(), "remembered rule after a save and load");

        RealClockData plain = new RealClockData();
        plain.setOffset(42L);
        RealClockData plainLoaded = RealClockData.load(plain.save(new CompoundTag(), registries), registries);
        helper.assertTrue(plainLoaded.skyPreviousRule() == null, "no remembered rule stays none");

        RealClockData blank = RealClockData.load(new CompoundTag(), registries);
        helper.assertFalse(blank.isInitialized(), "an empty save is a clock that has not been started");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theOffsetTravelsOverTheWire(GameTestHelper helper) {
        for (long offset : new long[] {0L, 1L, -1L, 1_728_000L, -987_654_321_098L, Long.MAX_VALUE, Long.MIN_VALUE}) {
            ByteBuf buffer = Unpooled.buffer();
            ClockSyncPayload.STREAM_CODEC.encode(buffer, new ClockSyncPayload(offset));
            ClockSyncPayload decoded = ClockSyncPayload.STREAM_CODEC.decode(buffer);
            helper.assertValueEqual(offset, decoded.offset(), "offset after encode and decode");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theCommandReadsAndSetsTheClock(GameTestHelper helper) throws CommandSyntaxException {
        MinecraftServer server = helper.getLevel().getServer();
        Saved saved = Saved.of(server);
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        CommandSourceStack operator = server.createCommandSourceStack().withPermission(2).withSuppressedOutput();
        CommandSourceStack player = server.createCommandSourceStack().withPermission(0).withSuppressedOutput();

        int got = dispatcher.execute("rtclock get", player);
        int set = dispatcher.execute("rtclock set 03:04:05", operator);
        long afterSet = ClockService.now(server);
        int badTime = dispatcher.execute("rtclock set 25:00", operator);
        long afterBad = ClockService.now(server);
        int aligned = dispatcher.execute("rtclock align", operator);

        boolean playerRefused = false;
        try {
            dispatcher.execute("rtclock set 03:04:05", player);
        } catch (CommandSyntaxException expected) {
            playerRefused = true;
        }
        saved.restore(server);

        helper.assertValueEqual(1, got, "result of /rtclock get");
        helper.assertValueEqual(1, set, "result of /rtclock set");
        helper.assertValueEqual(RealClock.ticksOf(3, 4, 5), RealClock.timeOfDay(afterSet), "time of day after /rtclock set");
        helper.assertValueEqual(0, badTime, "result of /rtclock set with a time that is not one");
        helper.assertValueEqual(afterSet, afterBad, "clock after a refused /rtclock set");
        helper.assertValueEqual(1, aligned, "result of /rtclock align");
        helper.assertTrue(playerRefused, "a player without permission cannot set the clock");
        helper.succeed();
    }
}
