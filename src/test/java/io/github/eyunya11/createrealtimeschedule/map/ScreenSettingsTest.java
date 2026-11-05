package io.github.eyunya11.createrealtimeschedule.map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** What a map screen's settings may be. */
class ScreenSettingsTest {

    @Test
    void theMiddleMayBeAnywhereInsideTheWidestWorldBorder() {
        assertTrue(ScreenSettings.isCentre(0));
        assertTrue(ScreenSettings.isCentre(30_000_000), "the border itself");
        assertTrue(ScreenSettings.isCentre(-30_000_000));
        assertFalse(ScreenSettings.isCentre(30_000_000.5), "past it");
        assertFalse(ScreenSettings.isCentre(-40_000_000));
        assertFalse(ScreenSettings.isCentre(Double.NaN));
        assertFalse(ScreenSettings.isCentre(Double.POSITIVE_INFINITY));
    }
}
