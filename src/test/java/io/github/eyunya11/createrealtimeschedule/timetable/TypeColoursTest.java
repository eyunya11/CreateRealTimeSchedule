package io.github.eyunya11.createrealtimeschedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TypeColoursTest {

    /** The same names and colours as the editor's test (timetable.test.js), so that both pick alike. */
    @Test
    void picksTheSameColourFromANameAsTheEditor() {
        assertEquals("#3a9d5d", TypeColours.byName("local"));
        assertEquals("#2f9e9e", TypeColours.byName("rapid"));
        assertEquals("#d9822b", TypeColours.byName("快速"));
        assertEquals("#b59a1c", TypeColours.byName("special"));
        assertEquals("#3b7dd8", TypeColours.byName(""));
    }

    @Test
    void takesOnlyAHashAndSixHexadecimalDigitsForAColour() {
        assertTrue(TypeColours.isColour("#d9822b"));
        assertTrue(TypeColours.isColour("#ABCDEF"));
        for (String bad : new String[] {"d9822b", "#d9822", "#d9822bb", "#g9822b", " #d9822b", "#d9822b ", ""})
            assertFalse(TypeColours.isColour(bad), bad);
    }

    @Test
    void makesAnOpaqueColourOfOne() {
        assertEquals(0xFFD9822B, TypeColours.argb("#d9822b"));
        assertEquals(0xFF000000, TypeColours.argb("#000000"));
    }
}
