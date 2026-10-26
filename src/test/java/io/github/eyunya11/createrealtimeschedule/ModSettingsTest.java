package io.github.eyunya11.createrealtimeschedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;

class ModSettingsTest {

    @Test
    void whileTheConfigIsNotLoadedEverySettingIsItsDefault() {
        // This is what a client outside a world sees, and what unit tests see.
        assertFalse(ModSettings.SPEC.isLoaded());
        assertTrue(ModSettings.syncSky());
        assertFalse(ModSettings.alignOnStart());
        assertEquals(RealClock.ticksOf(6, 0, 0), ModSettings.startTimeTicks());
        assertEquals(20, ModSettings.defaultMinimumStopSeconds());
        assertEquals(60, ModSettings.dutyLeadSeconds());
        assertEquals(600, ModSettings.recoveryThresholdSeconds());
    }

    @Test
    void theShippedDefaultsAreWhatTheReadmeSays() {
        assertEquals("06:00", ModSettings.DEFAULT_START_TIME);
        assertEquals(20, ModSettings.DEFAULT_MINIMUM_STOP_SECONDS);
        assertEquals(600, ModSettings.MAX_MINIMUM_STOP_SECONDS);
        assertEquals(ModSettings.MAX_MINIMUM_STOP_SECONDS, Timetable.MAX_STOP_SECONDS, "a timetable's minimum stop goes as far as the config's");
        assertEquals(60, ModSettings.DEFAULT_DUTY_LEAD_SECONDS);
        assertEquals(3600, ModSettings.MAX_DUTY_LEAD_SECONDS);
        assertEquals(600, ModSettings.DEFAULT_RECOVERY_THRESHOLD_SECONDS);
        assertEquals(7200, ModSettings.MAX_RECOVERY_THRESHOLD_SECONDS);
    }

    @Test
    void aLoadedConfigIsReadThroughToItsValue() {
        assertEquals(45, (int) ModSettings.read(true, () -> 45, 20));
    }

    @Test
    void aConfigThatIsNotLoadedIsNotAskedAtAll() {
        assertEquals(20, (int) ModSettings.read(false, () -> fail("the value must not be read"), 20));
    }

    @Test
    void aConfigThatUnloadsWhileItIsReadAnswersWithTheFallback() {
        // NeoForge's get() throws IllegalStateException for a config that is gone and has no cached value.
        assertEquals(20, (int) ModSettings.read(true, () -> {
            throw new IllegalStateException("Cannot get config value before config is loaded.");
        }, 20));
    }

    @Test
    void otherFailuresAreNotHidden() {
        assertThrows(UnsupportedOperationException.class, () -> ModSettings.read(true, () -> {
            throw new UnsupportedOperationException();
        }, 20));
    }
}
