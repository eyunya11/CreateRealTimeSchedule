package io.github.eyunya11.createrealtimeschedule.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Lamp;

class SignalLooksTest {

    @Test
    void createsSignalsShowOneLampAndAnInvalidOneRed() {
        assertEquals(List.of(Lamp.GREEN), SignalLooks.create("GREEN"));
        assertEquals(List.of(Lamp.YELLOW), SignalLooks.create("YELLOW"));
        assertEquals(List.of(Lamp.RED), SignalLooks.create("RED"));
        assertEquals(List.of(Lamp.RED), SignalLooks.create("INVALID"));
    }

    @Test
    void eachAspectOfAColourLightSignalHasItsLampsFromTheTop() {
        assertEquals(List.of(Lamp.RED), SignalLooks.aspect("STOP"));
        assertEquals(List.of(Lamp.YELLOW, Lamp.YELLOW), SignalLooks.aspect("RESTRICTED"));
        assertEquals(List.of(Lamp.YELLOW), SignalLooks.aspect("APPROACH"));
        assertEquals(List.of(Lamp.YELLOW, Lamp.GREEN), SignalLooks.aspect("REDUCED_SPEED"));
        assertEquals(List.of(Lamp.GREEN), SignalLooks.aspect("PROCEED"));
        assertEquals(List.of(Lamp.GREEN, Lamp.GREEN), SignalLooks.aspect("HIGH_SPEED_PROCEED"));
        assertNull(SignalLooks.aspect("FLASHING_YELLOW"));
    }

    @Test
    void theKindOfAColourLightSignalCountsItsAspects() {
        assertEquals("light2", SignalLooks.light("TWO"));
        assertEquals("light6", SignalLooks.light("SIX"));
        assertNull(SignalLooks.light("SEVEN"));
    }
}
