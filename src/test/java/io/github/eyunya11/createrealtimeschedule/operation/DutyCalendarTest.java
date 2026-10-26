package io.github.eyunya11.createrealtimeschedule.operation;

import static io.github.eyunya11.createrealtimeschedule.operation.OperationTimetables.t;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.operation.DutyCalendar.Start;

class DutyCalendarTest {

    private static final long DAY = RealClock.TICKS_PER_DAY;
    private static final long MINUTE = RealClock.TICKS_PER_MINUTE;
    private static final long LEAD = MINUTE;

    private static long at(long day, String time) {
        return day * DAY + t(time) * (long) RealClock.TICKS_PER_SECOND;
    }

    @Test
    void aDutyIsDueALeadBeforeItLeavesAndNotBefore() {
        assertEquals(List.of(), DutyCalendar.due("1A", t("07:50"), 4, at(5, "07:49") - 1, LEAD));
        assertEquals(List.of(new Start("1A", 5, at(5, "07:50"))), DutyCalendar.due("1A", t("07:50"), 4, at(5, "07:49"), LEAD));
    }

    @Test
    void aDutyDealtWithTodayIsNotDueAgain() {
        assertEquals(List.of(), DutyCalendar.due("1A", t("07:50"), 5, at(5, "07:49"), LEAD));
        assertEquals(List.of(), DutyCalendar.due("1A", t("07:50"), 5, at(5, "23:00"), LEAD));
        assertEquals(List.of(new Start("1A", 6, at(6, "07:50"))), DutyCalendar.due("1A", t("07:50"), 5, at(6, "07:49"), LEAD));
    }

    @Test
    void aStartThatHasGoneByIsMissed() {
        List<Start> due = DutyCalendar.due("1A", t("07:50"), 4, at(5, "07:50") + 1, LEAD);
        assertEquals(List.of(new Start("1A", 5, at(5, "07:50"))), due);
        assertTrue(due.getFirst().missedBy(at(5, "07:50") + 1));
        assertFalse(due.getFirst().missedBy(at(5, "07:50")));
    }

    /**
     * A duty may start late until the tick before the same duty of the next day is due, a lead before
     * it leaves: on that tick due() still has the day before, to be given up, and from the next it has not.
     */
    @Test
    void aDutyMayStartLateUntilTheNextDaysIsAboutToBeDue() {
        assertTrue(DutyCalendar.mayStartLate(5, t("07:50"), at(5, "07:50") + 1, LEAD));
        assertTrue(DutyCalendar.mayStartLate(5, t("07:50"), at(6, "07:49") - 2, LEAD));
        assertFalse(DutyCalendar.mayStartLate(5, t("07:50"), at(6, "07:49") - 1, LEAD));
        assertEquals(List.of(new Start("1A", 5, at(5, "07:50"))), DutyCalendar.due("1A", t("07:50"), 4, at(6, "07:49") - 1, LEAD));
        // With no lead, the day before is gone from due() once the next day's start has come.
        assertFalse(DutyCalendar.mayStartLate(5, t("07:50"), at(6, "07:50") - 1, 0));
        assertEquals(List.of(new Start("1A", 5, at(5, "07:50"))), DutyCalendar.due("1A", t("07:50"), 4, at(6, "07:50") - 1, 0));
        assertEquals(List.of(new Start("1A", 6, at(6, "07:50"))), DutyCalendar.due("1A", t("07:50"), 4, at(6, "07:50"), 0));
    }

    @Test
    void afterALongStopADutyIsMissedOnceNotOnceADay() {
        assertEquals(List.of(new Start("1A", 9, at(9, "07:50"))), DutyCalendar.due("1A", t("07:50"), 4, at(9, "12:00"), LEAD));
    }

    @Test
    void aStartPastMidnightBelongsToTheDayBefore() {
        // 24:30 of day 5 is 00:30 on day 6 by the clock.
        assertEquals(List.of(new Start("N", 5, at(6, "00:30"))), DutyCalendar.due("N", t("24:30"), 4, at(6, "00:29"), LEAD));
        assertEquals(5, DutyCalendar.lastDayStartedBy(at(6, "00:30"), t("24:30")));
        assertEquals(4, DutyCalendar.lastDayStartedBy(at(6, "00:29"), t("24:30")));
    }

    @Test
    void aLeadThatReachesOverMidnightFindsTheNextDaysStart() {
        assertEquals(List.of(new Start("1A", 6, at(6, "00:00:30"))), DutyCalendar.due("1A", t("00:00:30"), 5, at(5, "23:59:45"), LEAD));
    }

    @Test
    void theNextStartIsTheFirstNotDealtWith() {
        assertEquals(at(5, "07:50"), DutyCalendar.nextStart(t("07:50"), 4, at(5, "07:00")));
        assertEquals(at(6, "07:50"), DutyCalendar.nextStart(t("07:50"), 5, at(5, "07:00")), "day 5 was dealt with");
        // Not dealt with for days, the next is the last start gone by: the one due() reports as missed.
        assertEquals(at(9, "07:50"), DutyCalendar.nextStart(t("07:50"), 4, at(9, "12:00")));
        assertEquals(DutyCalendar.due("1A", t("07:50"), 4, at(9, "12:00"), LEAD).getFirst().at(),
            DutyCalendar.nextStart(t("07:50"), 4, at(9, "12:00")));
    }

    @Test
    void theSetThatHasWaitedLongestIsChosen() {
        UUID early = new UUID(0, 9);
        UUID late = new UUID(0, 1);
        Map<UUID, Long> free = new LinkedHashMap<>();
        free.put(late, 200L);
        free.put(early, 100L);
        assertEquals(Optional.of(early), DutyCalendar.longestWaiting(free));
        free.put(late, 100L);
        assertEquals(Optional.of(late), DutyCalendar.longestWaiting(free), "of two that became free together, the lower id");
        assertEquals(Optional.empty(), DutyCalendar.longestWaiting(Map.of()));
    }
}
