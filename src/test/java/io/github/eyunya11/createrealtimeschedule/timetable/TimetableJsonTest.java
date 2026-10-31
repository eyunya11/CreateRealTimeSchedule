package io.github.eyunya11.createrealtimeschedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Duty;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Line;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Stop;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Train;
import io.github.eyunya11.createrealtimeschedule.timetable.TimetableJson.Result;

class TimetableJsonTest {

    private static final String EXAMPLE = resource("example.json");

    private static final String HEAD = "{\"format\": \"createrealtimeschedule.timetable\", \"version\": 2";

    private static String resource(String name) {
        try (InputStream in = TimetableJsonTest.class.getResourceAsStream(name)) {
            // A checkout on Windows may have turned the line ends into CR LF.
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The example with one piece of text replaced wherever it occurs. */
    private static String edit(String from, String to) {
        assertTrue(EXAMPLE.contains(from), () -> "the example has no \"" + from + "\"");
        return EXAMPLE.replace(from, to);
    }

    private static List<String> problems(String text) {
        Result result = TimetableJson.parse(text);
        assertFalse(result.ok(), "the text was accepted: " + text);
        assertEquals(null, result.timetable());
        return result.problems();
    }

    private static void assertProblem(String text, String fragment) {
        List<String> problems = problems(text);
        assertTrue(problems.stream().anyMatch(problem -> problem.contains(fragment)),
            () -> "no problem mentions \"" + fragment + "\" in " + problems);
    }

    /** Exactly one problem, so that a mistake is not reported as three. */
    private static void assertOnlyProblem(String text, String fragment) {
        List<String> problems = problems(text);
        assertEquals(1, problems.size(), () -> "expected the one problem \"" + fragment + "\", got " + problems);
        assertTrue(problems.get(0).contains(fragment), () -> "\"" + problems.get(0) + "\" does not mention \"" + fragment + "\"");
    }

    private static Timetable accepted(String text) {
        Result result = TimetableJson.parse(text);
        assertTrue(result.ok(), () -> "the text was refused: " + result.problems());
        assertEquals(List.of(), result.problems());
        return result.timetable();
    }

    // ------------------------------------------------------------------ the example

    @Test
    void readsTheExample() {
        assertEquals(TimetableExample.central(), accepted(EXAMPLE));
    }

    @Test
    void writesTheExampleBackIdenticallyToTheCharacter() {
        assertEquals(EXAMPLE, TimetableJson.write(TimetableExample.central()));
    }

    @Test
    void theExampleInTheProposalIsTheExampleHere() throws IOException {
        Path proposal = Path.of("docs", "proposal.md");
        Assumptions.assumeTrue(Files.exists(proposal), "the proposal is not where the tests run");
        String text = Files.readString(proposal, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(text.contains(EXAMPLE.strip()), "docs/proposal.md does not show the example of the timetable JSON as it is in example.json");
    }

    // ------------------------------------------------------------------ writing

    private static Timetable night() {
        Line loop = new Line("loop", "Loop", List.of("A", "B", "C"), true);
        Train train = new Train("N1", "loop", "night", List.of(
            Stop.leaving("A", ServiceTime.of("23:50")),
            Stop.calling("B", ServiceTime.of("24:05:30"), ServiceTime.of("24:06")),
            Stop.calling("C", ServiceTime.of("24:20"), ServiceTime.of("24:21")),
            Stop.arriving("A", ServiceTime.of("24:35"))));
        return new Timetable("Night", List.of(loop), List.of(train), List.of(new Duty("N", "A", List.of("N1"))));
    }

    @Test
    void whatIsWrittenReadsBackAsTheSameTimetable() {
        Timetable night = night();
        assertEquals(night, accepted(TimetableJson.write(night)));
    }

    @Test
    void writingIsStable() {
        String once = TimetableJson.write(night());
        assertEquals(once, TimetableJson.write(accepted(once)));
    }

    @Test
    void leavesOutWhatHasItsDefault() {
        Line line = new Line("l", "Line", List.of("A", "B"), false);
        Train train = new Train("1", "l", "", List.of(Stop.leaving("A", 8 * 3600), Stop.arriving("B", 8 * 3600 + 600)));
        String json = TimetableJson.write(new Timetable("", List.of(line), List.of(train), List.of()));
        assertFalse(json.contains("\"loop\""), json);
        assertFalse(json.contains("\"type\""), json);
        assertFalse(json.contains("\"deadhead\""), json);
        assertTrue(json.contains("{\"station\": \"A\", \"depart\": \"08:00\"}"), json);
        assertTrue(json.contains("{\"station\": \"B\", \"arrive\": \"08:10\"}"), json);
    }

    @Test
    void writesWhatIsNotTheDefault() {
        String json = TimetableJson.write(night());
        assertTrue(json.contains("\"loop\": true"), json);
        assertTrue(json.contains("\"type\": \"night\""), json);
        assertTrue(json.contains("\"arrive\": \"24:05:30\""), json);
    }

    @Test
    void readsAndWritesADeadheadTrain() {
        Timetable timetable = accepted(EXAMPLE);
        assertTrue(timetable.train("D101").orElseThrow().deadhead());
        assertFalse(timetable.train("101").orElseThrow().deadhead());
        assertTrue(TimetableJson.write(timetable).contains("\"number\": \"D101\",\n      \"line\": \"central\",\n      \"deadhead\": true,"));
    }

    /**
     * The rule that a duty starts and ends at its depot holds for version 1 too: nothing ran a version 1
     * duty, and the operation cannot run one that does not come back.
     */
    @Test
    void aVersionOneDutyHasToComeBackToItsDepotToo() {
        String one = "{\"format\": \"createrealtimeschedule.timetable\", \"version\": 1, "
            + "\"lines\": [{\"id\": \"l\", \"stations\": [\"A\", \"B\"]}], "
            + "\"trains\": [{\"number\": \"1\", \"line\": \"l\", \"stops\": ["
            + "{\"station\": \"A\", \"depart\": \"08:00\"}, {\"station\": \"B\", \"arrive\": \"08:10\"}]}], "
            + "\"duties\": [{\"id\": \"1A\", \"depot\": \"A\", \"trains\": [\"1\"]}]}";
        assertOnlyProblem(one, "duty \"1A\": the last train, \"1\", ends at \"B\", not at the depot \"A\"");
    }

    /** Version 1 had no deadhead trains; it reads as it did, with none. */
    @Test
    void readsVersionOne() {
        Timetable timetable = accepted("{\"format\": \"createrealtimeschedule.timetable\", \"version\": 1, "
            + "\"lines\": [{\"id\": \"l\", \"stations\": [\"A\", \"B\"]}], "
            + "\"trains\": [{\"number\": \"1\", \"line\": \"l\", \"stops\": ["
            + "{\"station\": \"A\", \"depart\": \"08:00\"}, {\"station\": \"B\", \"arrive\": \"08:10\"}]}]}");
        assertFalse(timetable.trains().get(0).deadhead());
    }

    /** The stations' times and a stop's minimum stop, which version 3 added; a timetable with them is written as version 3. */
    private static Timetable withStopTimes() {
        Timetable night = night();
        Train train = night.trains().getFirst();
        List<Stop> stops = new ArrayList<>(train.stops());
        Stop b = stops.get(1);
        stops.set(1, new Stop(b.station(), b.arrive(), b.depart(), 0));
        return new Timetable(night.name(), night.lines(),
            List.of(new Timetable.Station("B", 30, Stop.NONE), new Timetable.Station("C", Stop.NONE, 45), new Timetable.Station("A", 20, 10)),
            List.of(new Train(train.number(), train.line(), train.type(), stops, train.deadhead())), night.duties());
    }

    @Test
    void readsAndWritesTheTimesOfStationsAndStopsAsVersionThree() {
        Timetable timetable = withStopTimes();
        String json = TimetableJson.write(timetable);
        assertTrue(json.contains("\"version\": 3,"), json);
        assertTrue(json.contains("  \"stations\": [\n"
            + "    {\"name\": \"B\", \"dwell\": 30},\n"
            + "    {\"name\": \"C\", \"minimum_stop\": 45},\n"
            + "    {\"name\": \"A\", \"dwell\": 20, \"minimum_stop\": 10}\n"
            + "  ],\n  \"trains\""), json);
        assertTrue(json.contains("{\"station\": \"B\", \"arrive\": \"24:05:30\", \"depart\": \"24:06\", \"minimum_stop\": 0}"), json);
        assertEquals(timetable, accepted(json));
        assertEquals(json, TimetableJson.write(accepted(json)));
    }

    /** The example the editor's tests read too (src/test/js), which both have to write back as it is. */
    @Test
    void writesTheStopTimesExampleBackIdenticallyToTheCharacter() {
        String text = resource("example-stop-times.json");
        Timetable timetable = accepted(text);
        assertEquals(List.of(new Timetable.Station("A", 30, 15), new Timetable.Station("B", 45, Stop.NONE), new Timetable.Station("Depot", Stop.NONE, 0)),
            timetable.stations());
        assertEquals(10, timetable.train("1").orElseThrow().stops().get(1).minimumStop());
        assertEquals(text, TimetableJson.write(timetable));
    }

    /** One with none of what version 3 added is version 2, which a mod that reads only version 2 still takes. */
    @Test
    void aTimetableWithoutStopTimesIsWrittenAsVersionTwo() {
        assertTrue(TimetableJson.write(night()).contains("\"version\": 2,"));
        assertEquals(2, night().version());
        assertEquals(3, withStopTimes().version());
    }

    /** A train that is never cancelled, which version 4 added. */
    private static Timetable kept(Timetable timetable) {
        Train train = timetable.trains().getFirst();
        return new Timetable(timetable.name(), timetable.lines(), timetable.stations(),
            List.of(new Train(train.number(), train.line(), train.type(), train.stops(), train.deadhead(), true)), timetable.duties());
    }

    @Test
    void readsAndWritesATrainThatIsNeverCancelledAsVersionFour() {
        Timetable timetable = kept(night());
        String json = TimetableJson.write(timetable);
        assertTrue(json.contains("\"version\": 4,"), json);
        assertTrue(json.contains("\"type\": \"night\",\n      \"keep\": true,\n      \"stops\""), json);
        assertEquals(timetable, accepted(json));
        assertTrue(accepted(json).trains().getFirst().keep());
        assertEquals(4, timetable.version());
        // Version 4 comes before what version 3 added, which it holds too.
        assertEquals(4, kept(withStopTimes()).version());
        assertFalse(accepted(EXAMPLE).trains().getFirst().keep());
    }

    /** The trains a stop waits for, which version 5 added; the example the editor's tests read too. */
    @Test
    void readsAndWritesTheTrainsAStopWaitsForAsVersionFive() {
        String text = resource("example-waits.json");
        Timetable timetable = accepted(text);
        assertEquals(List.of(new Timetable.Wait("3001", "S1", Stop.NONE), new Timetable.Wait("2", "", 120)),
            timetable.train("1").orElseThrow().stops().get(1).waits());
        assertEquals("S1", timetable.train("1").orElseThrow().stops().get(1).waits().get(0).station("S2"));
        assertEquals("S2", timetable.train("1").orElseThrow().stops().get(1).waits().get(1).station("S2"), "no at is the stop's own station");
        assertEquals(5, timetable.version());
        assertEquals(text, TimetableJson.write(timetable));
        assertEquals(4, kept(night()).version(), "a timetable whose stops wait for nothing is no version 5");
    }

    @Test
    void refusesALongestWaitThatIsNotAWholeNumberOfSeconds() {
        String text = resource("example-waits.json");
        assertOnlyProblem(text.replace("\"max_wait\": 120", "\"max_wait\": -1"),
            "trains[0].stops[1].wait_for[1].max_wait: must be 0 to 3600 seconds");
        assertOnlyProblem(text.replace("\"max_wait\": 120", "\"max_wait\": 1.5"),
            "trains[0].stops[1].wait_for[1].max_wait: must be a whole number of seconds");
        assertOnlyProblem(text.replace("{\"train\": \"3001\", \"at\": \"S1\"}", "{\"at\": \"S1\"}"),
            "trains[0].stops[1].wait_for[0].train: is missing");
    }

    /** The types' colours and the stops that change a train's type, which version 6 added; the example the editor's tests read too. */
    @Test
    void readsAndWritesTheTypesAndTheStopsThatChangeThemAsVersionSix() {
        String text = resource("example-types.json");
        Timetable timetable = accepted(text);
        assertEquals(List.of(new Timetable.TrainType("local", "#3a9d5d"), new Timetable.TrainType("rapid", "#d9822b")), timetable.types());
        Train train = timetable.train("1").orElseThrow();
        assertEquals("rapid", train.stops().get(1).type());
        assertEquals(List.of("local", "rapid", "rapid"), List.of(train.typeAt(0), train.typeAt(1), train.typeAt(2)));
        assertEquals("special", timetable.train("2").orElseThrow().typeAt(1));
        assertEquals("#d9822b", timetable.colour("rapid"));
        assertEquals("#b59a1c", timetable.colour("special"), "a type not in the list gets the colour picked from its name");
        assertNull(timetable.colour(""), "no type has no colour");
        assertEquals(6, timetable.version());
        assertEquals(text, TimetableJson.write(timetable));
        Timetable waits = accepted(resource("example-waits.json"));
        assertEquals(5, waits.version(), "a timetable with no types of its own is no version 6");
        Train local = waits.trains().getFirst();
        List<Stop> stops = new ArrayList<>(local.stops());
        Stop stop = stops.get(1);
        stops.set(1, new Stop(stop.station(), stop.arrive(), stop.depart(), stop.minimumStop(), "rapid", stop.waits()));
        List<Train> trains = new ArrayList<>(waits.trains());
        trains.set(0, new Train(local.number(), local.line(), local.type(), stops));
        assertEquals(6, new Timetable(waits.name(), waits.lines(), waits.stations(), trains, waits.duties()).version(),
            "a stop that changes the type is version 6");
    }

    /** The example the editor's tests read too (src/test/js), which both have to write back as it is. */
    @Test
    void writesTheKeepExampleBackIdenticallyToTheCharacter() {
        String text = resource("example-keep.json");
        Timetable timetable = accepted(text);
        assertEquals(List.of(false, false, true, false), timetable.trains().stream().map(Train::keep).toList());
        assertEquals(text, TimetableJson.write(timetable));
    }

    @Test
    void refusesSecondsThatAreNotAWholeNumber() {
        String json = TimetableJson.write(withStopTimes());
        assertOnlyProblem(json.replace("\"dwell\": 30", "\"dwell\": \"30\""), "stations[0].dwell: must be a whole number of seconds");
        assertOnlyProblem(json.replace("\"minimum_stop\": 45", "\"minimum_stop\": 4.5"), "stations[1].minimum_stop: must be a whole number of seconds");
        assertOnlyProblem(json.replace("\"minimum_stop\": 45", "\"minimum_stop\": -1"), "stations[1].minimum_stop: must be 0 to 600 seconds");
        assertOnlyProblem(json.replace("\"minimum_stop\": 0", "\"minimum_stop\": null, \"wait\": 1"), "trains[0].stops[1].wait: is not a member of this format");
        assertOnlyProblem(json.replace("{\"name\": \"B\", \"dwell\": 30}", "{\"dwell\": 30}"), "stations[0].name: is missing");
    }

    @Test
    void writesAnEmptyTimetable() {
        assertEquals("{\n"
            + "  \"format\": \"createrealtimeschedule.timetable\",\n"
            + "  \"version\": 2,\n"
            + "  \"name\": \"\",\n"
            + "  \"lines\": [],\n"
            + "  \"trains\": [],\n"
            + "  \"duties\": []\n"
            + "}\n", TimetableJson.write(new Timetable("", List.of(), List.of(), List.of())));
    }

    @Test
    void keepsNonAsciiAsItIsAndEscapesQuotesAndBackslashes() {
        Line line = new Line("l", "中央線", List.of("中央駅", "Say \"hi\" \\ there"), false);
        String json = TimetableJson.write(new Timetable("", List.of(line), List.of(), List.of()));
        assertTrue(json.contains("中央駅"), json);
        assertTrue(json.contains("\"Say \\\"hi\\\" \\\\ there\""), json);
        assertEquals(line, accepted(json).lines().get(0));
    }

    @Test
    void putsAListOfNamesThatIsTooLongForOneLineOneToALine() {
        List<String> stations = new ArrayList<>();
        for (int i = 1; i <= 30; i++)
            stations.add(String.format("Station %02d", i));
        Line line = new Line("long", "Long", stations, false);
        String json = TimetableJson.write(new Timetable("", List.of(line), List.of(), List.of()));
        assertTrue(json.contains("\"stations\": [\n"), json);
        assertTrue(json.lines().allMatch(row -> row.length() <= 100), json);
        assertEquals(line, accepted(json).lines().get(0));
    }

    // ------------------------------------------------------------------ text that is not JSON

    @Test
    void refusesTextThatIsNotJson() {
        for (String text : List.of("", "   ", "{", "{\"format\": ", "hello", "{\"a\" 1}"))
            assertOnlyProblem(text, "not valid JSON");
    }

    @Test
    void refusesWhatStrictJsonDoesNotAllow() {
        List<String> lenient = List.of(
            "{'format': 'x'}",
            "{format: 1}",
            "{/* a comment */ \"version\": 1}",
            "{\"version\": 1,}",
            "{\"version\": NaN}",
            "{} {}",
            "{} x");
        for (String text : lenient) {
            List<String> problems = problems(text);
            assertTrue(problems.get(problems.size() - 1).contains("not valid JSON"), problems.toString());
            assertFalse(problems.toString().contains("setLenient"), problems.toString());
        }
    }

    /** Some editors put one at the start of a file they save as UTF-8. Gson's reader skips it, and this keeps it so. */
    @Test
    void acceptsAByteOrderMarkAtTheStart() {
        assertEquals(TimetableExample.central(), accepted((char) 0xFEFF + EXAMPLE));
    }

    @Test
    void refusesWhatIsNotAnObjectAtTheTop() {
        for (String text : List.of("[]", "42", "\"x\"", "null", "true"))
            assertOnlyProblem(text, "the timetable must be a JSON object");
    }

    @Test
    void refusesNestingFarDeeperThanTheFormatNeeds() {
        assertOnlyProblem("[".repeat(40) + "]".repeat(40), "nested more than");
    }

    // ------------------------------------------------------------------ the shape

    @Test
    void acceptsAMinimalTimetableAndFillsInTheDefaults() {
        Timetable empty = accepted(HEAD + "}");
        assertEquals(new Timetable("", List.of(), List.of(), List.of()), empty);

        Timetable plain = accepted(HEAD + ", \"lines\": [{\"id\": \"l\", \"stations\": [\"A\", \"B\"]}], "
            + "\"trains\": [{\"number\": \"1\", \"line\": \"l\", \"stops\": ["
            + "{\"station\": \"A\", \"depart\": \"8:00\"}, {\"station\": \"B\", \"arrive\": \"08:10:00\"}]}]}");
        assertEquals("l", plain.lines().get(0).name());
        assertFalse(plain.lines().get(0).loop());
        assertEquals("", plain.trains().get(0).type());
        assertEquals(8 * 3600, plain.trains().get(0).stops().get(0).depart());
        assertEquals(8 * 3600 + 600, plain.trains().get(0).stops().get(1).arrive());
    }

    @Test
    void readsEscapesInNames() {
        Timetable timetable = accepted(HEAD + ", \"lines\": [{\"id\": \"l\", \"name\": \"\\u4e2d\\u592e\", "
            + "\"stations\": [\"A\\\"B\", \"駅\"]}]}");
        assertEquals("中央", timetable.lines().get(0).name());
        assertEquals(List.of("A\"B", "駅"), timetable.lines().get(0).stations());
    }

    @Test
    void refusesAMemberThatAppearsTwice() {
        assertProblem(edit("\"version\": 2,", "\"version\": 2,\n  \"version\": 2,"), "version: appears more than once");
        assertProblem(edit("{\"station\": \"Central 1\", \"depart\": \"08:10\"}",
            "{\"station\": \"Central 1\", \"station\": \"x\", \"depart\": \"08:10\"}"),
            "trains[1].stops[0].station: appears more than once");
    }

    @Test
    void namesAMemberTheFormatDoesNotHaveWhereverItIs() {
        List<String> problems = problems(edit("\"type\": \"local\",", "\"type\": \"local\", \"colour\": \"red\","));
        assertTrue(problems.contains("trains[1].colour: is not a member of this format"), problems.toString());
        assertTrue(problems.contains("trains[2].colour: is not a member of this format"), problems.toString());
        assertProblem(edit("\"duties\": [", "\"extra\": 1, \"duties\": ["), "extra: is not a member of this format");
        assertProblem(edit("\"id\": \"central\",", "\"id\": \"central\", \"colour\": 1,"), "lines[0].colour: is not a member");
        assertProblem(edit("{\"station\": \"Harbor\", \"arrive\": \"08:41\"}", "{\"station\": \"Harbor\", \"arrive\": \"08:41\", \"track\": 2}"),
            "trains[1].stops[2].track: is not a member");
        assertProblem(edit("\"depot\": \"Central Depot\",", "\"depot\": \"Central Depot\", \"shift\": \"early\","), "duties[0].shift: is not a member");
    }

    @Test
    void refusesValuesOfTheWrongKind() {
        assertProblem(edit("\"name\": \"Central Line, weekdays\"", "\"name\": 5"), "name: must be a string");
        assertProblem(edit("\"lines\": [", "\"lines\": {}, \"x\": ["), "lines: must be an array");
        assertProblem(HEAD + ", \"lines\": [5]}", "lines[0]: must be an object");
        assertProblem(edit("\"id\": \"central\",", "\"id\": \"central\", \"loop\": \"yes\","), "lines[0].loop: must be true or false");
        assertProblem(edit("\"stations\": [\"Central Depot\", \"Central 1\", \"North Gate\", \"Harbor\"]", "\"stations\": \"Central 1\""),
            "lines[0].stations: must be an array of strings");
        assertProblem(edit("\"stations\": [\"Central Depot\", \"Central 1\", \"North Gate\", \"Harbor\"]", "\"stations\": [\"Central 1\", 5]"),
            "lines[0].stations[1]: must be a string");
        assertProblem(edit("\"number\": \"101\"", "\"number\": 101"), "trains[1].number: must be a string");
        assertProblem(edit("\"deadhead\": true", "\"deadhead\": \"yes\""), "trains[0].deadhead: must be true or false");
        assertProblem(HEAD + ", \"trains\": [{\"number\": \"1\", \"line\": \"l\", \"stops\": {}}]}", "trains[0].stops: must be an array");
    }

    @Test
    void namesWhatIsMissing() {
        List<String> problems = problems(HEAD + ", \"lines\": [{}], \"trains\": [{\"stops\": [{}]}], \"duties\": [{}]}");
        for (String expected : List.of("lines[0].id: is missing", "lines[0].stations: is missing",
            "trains[0].number: is missing", "trains[0].line: is missing", "trains[0].stops[0].station: is missing",
            "duties[0].id: is missing", "duties[0].depot: is missing", "duties[0].trains: is missing"))
            assertTrue(problems.contains(expected), expected + " is not in " + problems);
        assertProblem(HEAD + ", \"trains\": [{\"number\": \"1\", \"line\": \"l\"}]}", "trains[0].stops: is missing");
    }

    @Test
    void refusesATextThatIsNotOfThisFormat() {
        assertOnlyProblem("{\"version\": 1}", "format: is missing");
        assertOnlyProblem("{\"format\": \"other\", \"version\": 1}", "format: must be \"createrealtimeschedule.timetable\"");
        assertOnlyProblem("{\"format\": 1, \"version\": 1}", "format: must be a string");
    }

    @Test
    void saysNothingElseAboutAJsonThatIsNotOfThisFormat() {
        String foreign = "{\"name\": \"plan\", \"stations\": [1, 2], \"routes\": {}, \"x\": 1, \"y\": 2}";
        assertOnlyProblem(foreign, "format: is missing");
        assertOnlyProblem("{\"format\": \"other\", \"version\": \"x\", \"lines\": 5, \"colour\": 1}",
            "format: must be \"createrealtimeschedule.timetable\"");
    }

    @Test
    void writesATimeOutsideTheServiceDayAsTextNoReaderTakesForATime() {
        Line line = new Line("l", "Line", List.of("A", "B"), false);
        Train train = new Train("1", "l", "", List.of(new Stop("A", Stop.NONE, -5), new Stop("B", ServiceTime.LIMIT, Stop.NONE)));
        String json = TimetableJson.write(new Timetable("", List.of(line), List.of(train), List.of()));
        assertTrue(json.contains("\"depart\": \"-5 s\""), json);
        assertTrue(json.contains("\"arrive\": \"" + ServiceTime.LIMIT + " s\""), json);
        assertProblem(json, "trains[0].stops[0].depart: must be a time");
        assertProblem(json, "trains[0].stops[1].arrive: must be a time");
    }

    @Test
    void refusesAVersionItCannotReadAndSaysNothingElse() {
        assertOnlyProblem("{\"format\": \"createrealtimeschedule.timetable\"}", "version: is missing");
        for (String version : List.of("\"1\"", "1.5", "1e0", "true", "123456789012"))
            assertOnlyProblem("{\"format\": \"createrealtimeschedule.timetable\", \"version\": " + version + "}", "version: must be a whole number");
        for (String version : List.of("0", "-1"))
            assertOnlyProblem("{\"format\": \"createrealtimeschedule.timetable\", \"version\": " + version + "}", "version: must be 1 or more");
        // A newer version may have members this one has never heard of; they are not worth listing.
        assertOnlyProblem("{\"format\": \"createrealtimeschedule.timetable\", \"version\": 7, \"colours\": [1, 2]}",
            "version: is 7, but this mod reads timetables up to version 6");
    }

    @Test
    void refusesATimeThatIsNotATimeAndSaysWhere() {
        assertProblem(edit("\"depart\": \"08:26\"", "\"depart\": \"8:26pm\""),
            "trains[1].stops[1].depart: must be a time such as \"08:10\" or \"08:10:30\", up to 47:59:59");
        assertProblem(edit("\"arrive\": \"09:21\"", "\"arrive\": \"25:99\""), "trains[2].stops[2].arrive: must be a time");
        assertProblem(edit("\"depart\": \"08:26\"", "\"depart\": 826"), "trains[1].stops[1].depart: must be a string");
    }

    @Test
    void collectsTheProblemsOfTheWholeTextNotJustTheFirst() {
        String text = edit("\"version\": 2,", "\"version\": 2, \"colour\": 1,")
            .replace("\"depart\": \"08:26\"", "\"depart\": \"late\"")
            .replace("\"number\": \"102\"", "\"number\": 102");
        List<String> problems = problems(text);
        assertEquals(3, problems.size(), problems.toString());
    }

    @Test
    void reportsAtMostFiftyProblemsAndCountsTheRest() {
        StringBuilder text = new StringBuilder(HEAD);
        for (int i = 0; i < 60; i++)
            text.append(", \"u").append(i).append("\": 1");
        List<String> problems = problems(text.append('}').toString());
        assertEquals(51, problems.size());
        assertEquals("... and 10 more", problems.get(50));
    }

    // ------------------------------------------------------------------ what the timetable itself finds

    @Test
    void reportsWhatTheTimetableFindsWrongWithTheNumberOfTheTrainAndTheStop() {
        assertOnlyProblem(edit("\"depart\": \"08:26\"", "\"depart\": \"08:20\""),
            "train \"101\", stop 2 \"North Gate\": leaves at 08:20, before it arrives at 08:25");
    }

    @Test
    void doesNotLookAtWhatTheTimetableFindsWhileTheShapeIsWrong() {
        String text = edit("\"depart\": \"08:26\"", "\"depart\": \"08:20\"").replace("\"version\": 2,", "\"version\": 2, \"colour\": 1,");
        assertOnlyProblem(text, "colour: is not a member of this format");
    }

    @Test
    void aLineThatNamesNoLineIsFoundByTheTimetableCheck() {
        assertOnlyProblem(edit("\"line\": \"central\",\n      \"type\": \"local\",\n      \"stops\": [\n        {\"station\": \"Central 1\"",
            "\"line\": \"nowhere\",\n      \"type\": \"local\",\n      \"stops\": [\n        {\"station\": \"Central 1\""),
            "there is no line \"nowhere\"");
    }
}
