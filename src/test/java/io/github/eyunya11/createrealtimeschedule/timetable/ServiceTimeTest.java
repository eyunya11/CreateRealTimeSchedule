package io.github.eyunya11.createrealtimeschedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ServiceTimeTest {

    @Test
    void readsAnHourAndAMinute() {
        assertEquals(8 * 3600 + 10 * 60, ServiceTime.of("08:10"));
        assertEquals(8 * 3600 + 10 * 60, ServiceTime.of("8:10"));
        assertEquals(0, ServiceTime.of("00:00"));
    }

    @Test
    void readsSeconds() {
        assertEquals(8 * 3600 + 10 * 60 + 30, ServiceTime.of("08:10:30"));
    }

    @Test
    void goesOnPastMidnightUpToFortySevenFiftyNine() {
        assertEquals(24 * 3600 + 10 * 60, ServiceTime.of("24:10"));
        assertEquals(ServiceTime.LIMIT - 1, ServiceTime.of("47:59:59"));
    }

    @Test
    void refusesWhatIsNotATime() {
        // The last one is written in Arabic-Indic digits: digits to Character.isDigit, but not to \d.
        List<String> bad = List.of("", "8", "08", "08:5", "08:60", "48:00", "47:60", "24:00:60", " 08:10", "08:10 ",
            "08.10", "ab:cd", "-1:00", "+8:10", "008:10", "8:10:5", "08:10:30:00", "08:10pm",
            "٠٨:١٠");
        for (String text : bad)
            assertTrue(ServiceTime.parse(text).isEmpty(), "\"" + text + "\" was read as a time");
    }

    @Test
    void ofRefusesWhatParseRefuses() {
        assertThrows(IllegalArgumentException.class, () -> ServiceTime.of("48:00"));
    }

    @Test
    void formatsWithTwoDigitsAndSecondsOnlyWhenThereAreSome() {
        assertEquals("08:10", ServiceTime.format(8 * 3600 + 10 * 60));
        assertEquals("08:10:30", ServiceTime.format(8 * 3600 + 10 * 60 + 30));
        assertEquals("00:00", ServiceTime.format(0));
        assertEquals("24:10", ServiceTime.format(24 * 3600 + 10 * 60));
        assertEquals("47:59:59", ServiceTime.format(ServiceTime.LIMIT - 1));
    }

    @Test
    void formatAndParseAgreeOnEveryTimeOfTheDay() {
        for (int seconds = 0; seconds < ServiceTime.LIMIT; seconds++)
            assertEquals(seconds, ServiceTime.of(ServiceTime.format(seconds)));
    }

    @Test
    void showIsFormatForATimeAndPlainSecondsForWhatIsNoTime() {
        assertEquals("08:10", ServiceTime.show(8 * 3600 + 10 * 60));
        assertEquals("-5 s", ServiceTime.show(-5));
        assertEquals("172800 s", ServiceTime.show(ServiceTime.LIMIT));
        assertTrue(ServiceTime.parse(ServiceTime.show(-5)).isEmpty());
    }

    @Test
    void formatRefusesATimeOutsideTheServiceDay() {
        assertThrows(IllegalArgumentException.class, () -> ServiceTime.format(-1));
        assertThrows(IllegalArgumentException.class, () -> ServiceTime.format(ServiceTime.LIMIT));
    }
}
