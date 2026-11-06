package io.github.eyunya11.createrealtimeschedule.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void theMiddleTypedIsTheOneChecked() {
        // Out of the world as typed, though the screen's own middle is in it: the settings hold Done back.
        assertFalse(ScreenSettings.isTypedCentre("40000000", 0));
        assertFalse(ScreenSettings.isTypedCentre("-30000001", 0));
        assertTrue(ScreenSettings.isTypedCentre("-120.5", 0));
        // In it as typed, though the screen's own middle is not.
        assertTrue(ScreenSettings.isTypedCentre("30000000", 40_000_000));
    }

    @Test
    void textThatIsNoNumberStandsForTheMiddleTheScreenHas() {
        assertEquals(64, ScreenSettings.typed("", 64));
        assertEquals(64, ScreenSettings.typed("-", 64));
        assertEquals(64, ScreenSettings.typed(".", 64));
        assertEquals(-12.5, ScreenSettings.typed("-12.5", 64));
        assertTrue(ScreenSettings.isTypedCentre("", 64));
        assertFalse(ScreenSettings.isTypedCentre("", 40_000_000));
    }
}
