package io.github.eyunya11.createrealtimeschedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Duty;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Line;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Stop;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Train;

class TimetableTest {

    /** A line of four stations, which the trains of these tests run on. */
    private static final Line L = new Line("l", "Line", List.of("A", "B", "C", "D"), false);

    private static int t(String time) {
        return ServiceTime.of(time);
    }

    private static Stop leave(String station, String time) {
        return Stop.leaving(station, t(time));
    }

    private static Stop arrive(String station, String time) {
        return Stop.arriving(station, t(time));
    }

    private static Stop call(String station, String arrival, String departure) {
        return Stop.calling(station, t(arrival), t(departure));
    }

    private static Train train(String number, Stop... stops) {
        return new Train(number, "l", "", List.of(stops));
    }

    /** A train from A to B, which is all a duty needs of a train. */
    private static Train ab(String number, String leaves, String arrives) {
        return train(number, leave("A", leaves), arrive("B", arrives));
    }

    private static Train ba(String number, String leaves, String arrives) {
        return train(number, leave("B", leaves), arrive("A", arrives));
    }

    private static Timetable of(Train... trains) {
        return new Timetable("", List.of(L), List.of(trains), List.of());
    }

    private static Timetable running(List<Train> trains, Duty... duties) {
        return new Timetable("", List.of(L), trains, List.of(duties));
    }

    private static Duty duty(String id, String... trains) {
        return new Duty(id, "A", List.of(trains));
    }

    private static Timetable withLine(Line line) {
        return new Timetable("", List.of(line), List.of(), List.of());
    }

    private static void assertNoProblems(Timetable timetable) {
        assertEquals(List.of(), timetable.problems());
    }

    private static void assertProblem(Timetable timetable, String fragment) {
        List<String> problems = timetable.problems();
        assertTrue(problems.stream().anyMatch(problem -> problem.contains(fragment)),
            () -> "no problem mentions \"" + fragment + "\" in " + problems);
    }

    /** Exactly one problem, so that a mistake is not reported as three. */
    private static void assertOnlyProblem(Timetable timetable, String fragment) {
        List<String> problems = timetable.problems();
        assertEquals(1, problems.size(), () -> "expected the one problem \"" + fragment + "\", got " + problems);
        assertTrue(problems.get(0).contains(fragment), () -> "\"" + problems.get(0) + "\" does not mention \"" + fragment + "\"");
    }

    // ------------------------------------------------------------------ the whole

    @Test
    void theExampleHasNoProblems() {
        assertNoProblems(TimetableExample.central());
    }

    @Test
    void anEmptyTimetableHasNoProblems() {
        assertNoProblems(new Timetable("", List.of(), List.of(), List.of()));
    }

    @Test
    void aNameMayBeEmptyButNotPadded() {
        assertOnlyProblem(new Timetable(" weekdays", List.of(), List.of(), List.of()), "the name must not start or end with a space");
    }

    @Test
    void problemsAreReportedTogether() {
        Timetable timetable = of(
            train("1", leave("A", "08:00"), arrive("Z", "08:10")),
            train("1", leave("A", "08:00"), arrive("B", "08:10")));
        assertProblem(timetable, "is not a station of line");
        assertProblem(timetable, "another train has the same number");
    }

    @Test
    void aLineAndATrainCanBeLookedUpByTheNameTheyAreReferredToBy() {
        Timetable timetable = TimetableExample.central();
        assertEquals("Central Line", timetable.line("central").orElseThrow().name());
        assertEquals("102", timetable.train("102").orElseThrow().number());
        assertTrue(timetable.line("nowhere").isEmpty());
        assertTrue(timetable.train("999").isEmpty());
    }

    // ------------------------------------------------------------------ lines

    @Test
    void aLineIdIsOneToThirtyTwoCharactersWithoutPaddingOrControlCharacters() {
        for (String id : List.of("", " x", "x ", "a".repeat(33), "tab\there"))
            assertProblem(withLine(new Line(id, "Line", List.of("A", "B"), false)), "the id must be 1 to 32");
        assertNoProblems(withLine(new Line("a".repeat(32), "Line", List.of("A", "B"), false)));
    }

    @Test
    void twoLinesCannotShareAnId() {
        assertOnlyProblem(new Timetable("", List.of(L, L), List.of(), List.of()), "another line has the same id");
    }

    @Test
    void aLineNeedsAName() {
        assertOnlyProblem(withLine(new Line("l", "", List.of("A", "B"), false)), "the name must not be empty");
    }

    @Test
    void aLineNeedsTwoStations() {
        assertOnlyProblem(withLine(new Line("l", "Line", List.of("A"), false)), "needs at least 2 stations");
    }

    @Test
    void aLineCannotListAStationTwice() {
        assertOnlyProblem(withLine(new Line("l", "Line", List.of("A", "B", "A"), false)), "station \"A\" is listed twice");
    }

    @Test
    void aStationNameCannotBeEmptyOrPadded() {
        assertOnlyProblem(withLine(new Line("l", "Line", List.of("A", " B"), false)), "a station name must not be empty");
    }

    // ------------------------------------------------------------------ trains

    @Test
    void aTrainRunsOnALineThatExists() {
        Train train = new Train("1", "nowhere", "", List.of(leave("A", "08:00"), arrive("B", "08:10")));
        assertOnlyProblem(new Timetable("", List.of(L), List.of(train), List.of()), "there is no line \"nowhere\"");
    }

    @Test
    void trainNumbersAreUnique() {
        assertOnlyProblem(of(ab("1", "08:00", "08:10"), ab("1", "09:00", "09:10")), "another train has the same number");
    }

    @Test
    void aTrainNumberIsOneToThirtyTwoCharactersWithoutPadding() {
        for (String number : List.of("", " 1", "1 ", "9".repeat(33)))
            assertProblem(of(train(number, leave("A", "08:00"), arrive("B", "08:10"))), "the number must be 1 to 32");
    }

    @Test
    void aTypeMayBeEmptyOrTextButNotPadded() {
        Timetable plain = new Timetable("", List.of(L), List.of(new Train("1", "l", "rapid", ab("1", "08:00", "08:10").stops())), List.of());
        assertNoProblems(plain);
        Timetable padded = new Timetable("", List.of(L), List.of(new Train("1", "l", "rapid ", ab("1", "08:00", "08:10").stops())), List.of());
        assertOnlyProblem(padded, "the type must not start or end with a space");
    }

    @Test
    void aTrainNeedsTwoStops() {
        assertOnlyProblem(of(train("1", leave("A", "08:00"))), "needs at least 2 stops");
    }

    @Test
    void everyStopIsAStationOfTheLine() {
        assertOnlyProblem(of(train("1", leave("A", "08:00"), arrive("Z", "08:10"))), "stop 2 \"Z\": is not a station of line \"l\"");
    }

    @Test
    void theSameStationTwiceInARowIsRefused() {
        assertOnlyProblem(of(train("1", leave("A", "08:00"), call("A", "08:05", "08:06"), arrive("B", "08:10"))),
            "is the same station as the stop before it");
    }

    // ------------------------------------------------------------------ the times of a train

    @Test
    void theFirstStopOnlyDeparts() {
        assertOnlyProblem(of(train("1", call("A", "07:50", "08:00"), arrive("B", "08:10"))),
            "is the first stop, which only has a departure time");
    }

    @Test
    void theLastStopOnlyArrives() {
        assertOnlyProblem(of(train("1", leave("A", "08:00"), call("B", "08:10", "08:11"))),
            "is the last stop, which only has an arrival time");
    }

    @Test
    void everyStopButTheLastNeedsADeparture() {
        assertOnlyProblem(of(train("1", leave("A", "08:00"), arrive("B", "08:10"), arrive("C", "08:20"))),
            "stop 2 \"B\": needs a departure time");
        Train bare = train("2", new Stop("A", Stop.NONE, Stop.NONE), arrive("B", "08:10"));
        assertOnlyProblem(of(bare), "stop 1 \"A\": needs a departure time");
    }

    @Test
    void theLastStopNeedsAnArrival() {
        assertOnlyProblem(of(train("1", leave("A", "08:00"), new Stop("B", Stop.NONE, Stop.NONE))),
            "stop 2 \"B\": needs an arrival time");
    }

    @Test
    void anArrivalMustComeAfterTheDepartureBefore() {
        assertOnlyProblem(of(train("1", leave("A", "08:00"), arrive("B", "08:00"))),
            "arrives at 08:00, which is not after the train leaves the stop before at 08:00");
        assertOnlyProblem(of(train("2", leave("A", "08:00"), arrive("B", "07:59"))),
            "arrives at 07:59, which is not after the train leaves the stop before at 08:00");
    }

    @Test
    void aTrainCannotLeaveBeforeItArrives() {
        assertOnlyProblem(of(train("1", leave("A", "08:00"), call("B", "08:20", "08:10"), arrive("C", "08:30"))),
            "leaves at 08:10, before it arrives at 08:20");
    }

    @Test
    void aStopWithoutAnArrivalLeavesAfterTheDepartureBefore() {
        assertOnlyProblem(of(train("1", leave("A", "08:00"), leave("B", "08:00"), arrive("C", "08:10"))),
            "leaves at 08:00, which is not after the train leaves the stop before at 08:00");
    }

    @Test
    void aTrainMayLeaveTheSecondItArrives() {
        assertNoProblems(of(train("1", leave("A", "08:00"), call("B", "08:10", "08:10"), arrive("C", "08:20"))));
    }

    @Test
    void timesRunOnPastMidnight() {
        assertNoProblems(of(train("N", leave("A", "23:50"), call("B", "24:05:30", "24:06"), arrive("C", "24:20"))));
        // Written as 00:10 a train that left at 23:50 would arrive before it left.
        assertOnlyProblem(of(train("N", leave("A", "23:50"), arrive("B", "00:10"))),
            "arrives at 00:10, which is not after the train leaves the stop before at 23:50");
    }

    @Test
    void aTimeOutsideTheServiceDayIsRefused() {
        assertProblem(of(train("1", leave("A", "08:00"), new Stop("B", -5, Stop.NONE))),
            "the arrival time is not between 00:00 and 47:59:59");
        assertProblem(of(train("1", new Stop("A", Stop.NONE, ServiceTime.LIMIT), arrive("B", "08:10"))),
            "the departure time is not between 00:00 and 47:59:59");
    }

    // ------------------------------------------------------------------ one way along a line

    @Test
    void aTrainMayGoEitherWayAlongALineAndSkipStations() {
        assertNoProblems(of(
            train("1", leave("A", "08:00"), call("B", "08:10", "08:11"), arrive("D", "08:30")),
            train("2", leave("D", "09:00"), arrive("A", "09:30"))));
    }

    @Test
    void aTrainThatTurnsBackIsRefused() {
        assertOnlyProblem(of(train("1", leave("A", "08:00"), call("C", "08:10", "08:11"), arrive("B", "08:20"))),
            "stop 3 \"B\": turns back along line \"l\"");
    }

    @Test
    void aTrainThatTurnsBackSeveralTimesIsReportedOnce() {
        Timetable timetable = of(train("1", leave("A", "08:00"), call("C", "08:10", "08:11"),
            call("B", "08:20", "08:21"), arrive("D", "08:30")));
        assertEquals(1, timetable.problems().stream().filter(problem -> problem.contains("turns back")).count());
    }

    @Test
    void aLoopLineHasNoEndToTurnBackAt() {
        Line loop = new Line("l", "Line", List.of("A", "B", "C", "D"), true);
        Train train = train("1", leave("A", "08:00"), call("C", "08:10", "08:11"), call("B", "08:20", "08:21"), arrive("D", "08:30"));
        assertNoProblems(new Timetable("", List.of(loop), List.of(train), List.of()));
        Train strayed = train("2", leave("A", "08:00"), arrive("Z", "08:10"));
        assertOnlyProblem(new Timetable("", List.of(loop), List.of(strayed), List.of()), "is not a station of line");
    }

    // ------------------------------------------------------------------ duties

    @Test
    void aDutyStringsTrainsTogether() {
        List<Train> trains = List.of(ab("1", "08:00", "08:10"), ba("2", "08:20", "08:30"), ab("3", "08:40", "08:50"), ba("4", "09:00", "09:10"));
        assertNoProblems(running(trains, duty("1A", "1", "2", "3", "4")));
    }

    @Test
    void aDutyIdIsOneToThirtyTwoCharactersWithoutPadding() {
        assertProblem(running(List.of(ab("1", "08:00", "08:10")), duty("", "1")), "the id must be 1 to 32");
        assertProblem(running(List.of(ab("1", "08:00", "08:10")), duty("1A ", "1")), "the id must be 1 to 32");
    }

    @Test
    void dutyIdsAreUnique() {
        List<Train> trains = List.of(ab("1", "08:00", "08:10"), ba("2", "08:20", "08:30"), ab("3", "09:00", "09:10"), ba("4", "09:20", "09:30"));
        assertOnlyProblem(running(trains, duty("1A", "1", "2"), duty("1A", "3", "4")), "another duty has the same id");
    }

    @Test
    void aDutyNeedsADepot() {
        List<Train> trains = List.of(ab("1", "08:00", "08:10"));
        assertOnlyProblem(running(trains, new Duty("1A", " ", List.of("1"))), "the depot, a station name, must not be empty");
    }

    @Test
    void aDutyNeedsATrain() {
        assertOnlyProblem(running(List.of(), duty("1A")), "needs at least 1 train");
    }

    @Test
    void aDutyCannotUseATrainThatIsNotThere() {
        assertOnlyProblem(running(List.of(ab("1", "08:00", "08:10")), duty("1A", "9")), "there is no train \"9\"");
    }

    @Test
    void aTrainBelongsToOneDutyOnly() {
        List<Train> trains = List.of(ab("1", "08:00", "08:10"), ba("2", "08:20", "08:30"), ba("3", "09:20", "09:30"));
        assertOnlyProblem(running(trains, duty("1A", "1", "2"), duty("2B", "1", "3")), "train \"1\" is also in duty \"1A\"");
    }

    @Test
    void aTrainCannotBeInADutyTwice() {
        assertProblem(running(List.of(ab("1", "08:00", "08:10")), duty("1A", "1", "1")), "train \"1\" is listed twice in this duty");
    }

    @Test
    void theNextTrainStartsWhereTheOneBeforeEnded() {
        List<Train> trains = List.of(ab("1", "08:00", "08:10"), ab("2", "08:20", "08:30"), ba("3", "08:40", "08:50"));
        assertOnlyProblem(running(trains, duty("1A", "1", "2", "3")),
            "train \"2\" starts at \"A\" but the train before it, \"1\", ends at \"B\"");
    }

    @Test
    void theNextTrainLeavesAfterTheOneBeforeArrived() {
        assertOnlyProblem(running(List.of(ab("1", "08:00", "08:10"), ba("2", "08:10", "08:20")), duty("1A", "1", "2")),
            "train \"2\" leaves at 08:10, which is not after train \"1\" arrives at 08:10");
        assertOnlyProblem(running(List.of(ab("1", "08:00", "08:10"), ba("2", "08:09", "08:20")), duty("1A", "1", "2")),
            "train \"2\" leaves at 08:09");
        assertNoProblems(running(List.of(ab("1", "08:00", "08:10"), ba("2", "08:11", "08:20")), duty("1A", "1", "2")));
    }

    @Test
    void aMissingTrainDoesNotMakeTheTrainsAroundItLookLikeTheyDoNotMeet() {
        List<Train> trains = List.of(ab("1", "08:00", "08:10"), ab("2", "08:20", "08:30"), ba("3", "08:40", "08:50"));
        assertOnlyProblem(running(trains, duty("1A", "1", "9", "2", "3")), "there is no train \"9\"");
    }

    @Test
    void aDutyMayGoFromOneLineToAnother() {
        Line first = new Line("first", "First", List.of("A", "B"), false);
        Line second = new Line("second", "Second", List.of("B", "C"), false);
        Train out = new Train("1", "first", "", List.of(leave("A", "08:00"), arrive("B", "08:10")));
        Train on = new Train("2", "second", "", List.of(leave("B", "08:15"), arrive("C", "08:25")));
        Train back = new Train("3", "second", "", List.of(leave("C", "08:35"), arrive("B", "08:45")));
        Train home = new Train("4", "first", "", List.of(leave("B", "08:50"), arrive("A", "09:00")));
        assertNoProblems(new Timetable("", List.of(first, second), List.of(out, on, back, home), List.of(duty("1A", "1", "2", "3", "4"))));
    }

    @Test
    void aDutyStartsAndEndsAtItsDepot() {
        assertOnlyProblem(running(List.of(ab("1", "08:00", "08:10")), duty("1A", "1")),
            "duty \"1A\": the last train, \"1\", ends at \"B\", not at the depot \"A\"");
        assertOnlyProblem(running(List.of(ba("1", "08:00", "08:10")), duty("1A", "1")),
            "duty \"1A\": the first train, \"1\", starts at \"B\", not at the depot \"A\"");
    }

    @Test
    void aDeadheadTrainTakesADutyFromItsDepotAndBack() {
        Line line = new Line("l", "Line", List.of("Depot", "A", "B"), false);
        Train out = new Train("D1", "l", "", List.of(leave("Depot", "07:50"), arrive("A", "07:55")), true);
        Train there = new Train("1", "l", "", List.of(leave("A", "08:00"), arrive("B", "08:05")));
        Train again = new Train("2", "l", "", List.of(leave("B", "08:10"), arrive("A", "08:15")));
        Train back = new Train("D2", "l", "", List.of(leave("A", "08:20"), arrive("Depot", "08:25")), true);
        assertNoProblems(new Timetable("", List.of(line), List.of(out, there, again, back),
            List.of(new Duty("1A", "Depot", List.of("D1", "1", "2", "D2")))));
    }
}
