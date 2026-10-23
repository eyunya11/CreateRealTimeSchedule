package io.github.eyunya11.createrealtimeschedule.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.trains.entity.Train;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.ModSettings;
import io.github.eyunya11.createrealtimeschedule.clock.ClockService;
import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.gametest.TrackLine.Loop;
import io.github.eyunya11.createrealtimeschedule.operation.DutyRun;
import io.github.eyunya11.createrealtimeschedule.operation.OperationData;
import io.github.eyunya11.createrealtimeschedule.operation.OperationService;
import io.github.eyunya11.createrealtimeschedule.operation.TrainDriver;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.TimetableJson;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The operation of a loaded timetable as the server runs it ({@link OperationService}), on real track,
 * with {@code ./gradlew runGameTestServer}. The sets wait at the depot of a loop line with no schedule
 * of Create's; the operation gives each duty a set and takes it round. Two loops, one south of the
 * other, are two track graphs, each with its own depot, A and B, so two sets can run at once without
 * meeting.
 *
 * <p>The config is the default one: a duty is given its set 60 seconds before it leaves, and a set
 * stops for 20 seconds at the least. Each test has a batch of its own (see {@link TrackLine}), and
 * leaves no timetable loaded.
 */
@GameTestHolder(Createrealtimeschedule.MODID)
@PrefixGameTestTemplate(false)
public class OperationTests {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String TURN = "track_turn";
    private static final Loop NORTH = new Loop(100, 8, TrackLine.TRACK_Z, 24);
    private static final Loop SOUTH = new Loop(100, 8, 32, 54);
    private static final String[] NAMES = {"Depot", "A", "B"};
    private static final int[] STATION_X = {20, 50, 80};
    /** The clock when the test starts. */
    private static final long START = TestTimes.clock(10, 12, 0, 0);
    /** The service day the duties run on. */
    private static final long DAY = 10;

    /**
     * M5's goal: two sets at the depot take the duties in the order the duties leave, and come back to
     * the depot after them. The set on the north loop is free first, so it takes 1A, which leaves at
     * 12:02; the other takes 2A, which leaves half a minute later. Each leaves every call on time.
     */
    @GameTest(template = TURN, batch = "realtimeoperation_two_sets", timeoutTicks = 6000)
    public static void twoSetsAtTheDepotTakeTheDutiesInTheOrderTheyLeaveAndComeBack(GameTestHelper helper) {
        Timetable timetable = timetable(duty("1A", "1", 0), duty("2A", "2", 30));
        TrackLine north = new TrackLine(helper, NORTH, STATION_X, NAMES);
        TrackLine south = new TrackLine(helper, SOUTH, STATION_X, NAMES);
        new Operation(helper, timetable, north, south) {
            @Nullable DutyRun first;
            @Nullable DutyRun second;
            @Nullable UUID firstSet;
            @Nullable UUID secondSet;

            @Override
            boolean watch(OperationData data, long now) {
                if (first == null && data.trainOf("1A").isPresent()) {
                    firstSet = data.trainOf("1A").get();
                    first = data.runs().get(firstSet);
                }
                if (second == null && data.trainOf("2A").isPresent()) {
                    secondSet = data.trainOf("2A").get();
                    second = data.runs().get(secondSet);
                }
                if (first == null || second == null || !first.finished() || !second.finished())
                    return false;
                log(first);
                log(second);
                helper.assertTrue(firstSet.equals(north.train().id),
                    "1A, which leaves first, was not given the set that was free first, on the north loop");
                helper.assertTrue(secondSet.equals(south.train().id), "2A was not given the other set");
                assertOnTime(first);
                assertOnTime(second);
                for (TrackLine line : List.of(north, south)) {
                    helper.assertTrue(TrainDriver.standsAt(line.train(), "Depot"), "a set is not back at the depot (" + line.describe() + ")");
                    helper.assertTrue(data.freeSince().containsKey(line.train().id), "a set back at the depot is not free again");
                }
                for (DutyRun run : List.of(first, second)) {
                    TrackLine line = run == first ? north : south;
                    helper.assertTrue(data.history().stream().anyMatch(record -> record.run() == run
                            && record.ending() == OperationData.Ending.FINISHED && record.train().equals(line.train().name.getString())),
                        "the times of duty " + run.duty() + " are not kept as finished by its train: " + data.history());
                }
                helper.assertTrue(newNotes(data).isEmpty(), "something went wrong: " + newNotes(data));
                return true;
            }
        }.begin();
    }

    /** Two duties leave at the same time, and there is one set: 1A gets it, and 2A does not run. */
    @GameTest(template = TURN, batch = "realtimeoperation_one_set_short", timeoutTicks = 4000)
    public static void aDutyWithNoFreeSetByItsTimeDoesNotRun(GameTestHelper helper) {
        Timetable timetable = timetable(duty("1A", "1", 0), duty("2A", "2", 0));
        TrackLine north = new TrackLine(helper, NORTH, STATION_X, NAMES);
        new Operation(helper, timetable, north) {
            @Override
            boolean watch(OperationData data, long now) {
                // The operation runs after the test in a tick, so it has seen the time go by two ticks on.
                if (now <= departure(0) + 2)
                    return false;
                helper.assertTrue(data.trainOf("1A").equals(Optional.of(north.train().id)), "1A did not get the one set");
                helper.assertTrue(data.trainOf("2A").isEmpty(), "2A got a set");
                List<OperationData.Note> notes = newNotes(data);
                helper.assertTrue(notes.size() == 1 && notes.getFirst().duty().equals("2A") && notes.getFirst().day() == DAY
                    && notes.getFirst().what().startsWith("did not run"), "2A is not noted as not run: " + notes);
                return true;
            }
        }.begin();
    }

    /** A player gives the set a schedule of Create's while it runs a duty: the operation lets go of it, and does not take it again. */
    @GameTest(template = TURN, batch = "realtimeoperation_let_go", timeoutTicks = 4000)
    public static void aSetGivenAScheduleIsLetGo(GameTestHelper helper) {
        Timetable timetable = timetable(duty("1A", "1", 0));
        TrackLine north = new TrackLine(helper, NORTH, STATION_X, NAMES);
        new Operation(helper, timetable, north) {
            long letGoAt = -1;

            @Override
            boolean watch(OperationData data, long now) {
                Train train = north.train();
                if (letGoAt < 0) {
                    DutyRun run = data.runs().get(train.id);
                    if (run == null || run.phase() != DutyRun.Phase.RUNNING)
                        return false;
                    train.runtime.setSchedule(ScheduleTests.schedule(ScheduleTests.stop("B")), false);
                    letGoAt = now;
                    return false;
                }
                helper.assertTrue(data.runs().isEmpty(), "the operation still runs the set it was to let go");
                if (now < letGoAt + 2 * RealClock.TICKS_PER_SECOND)
                    return false;
                List<OperationData.Note> notes = newNotes(data);
                helper.assertTrue(notes.size() == 1 && notes.getFirst().what().equals("given up: the train has been given a schedule"),
                    "the duty is not noted as given up: " + notes);
                helper.assertTrue(!data.freeSince().containsKey(train.id), "a set with a schedule of Create's is counted as free");
                List<OperationData.Record> history = data.history();
                helper.assertTrue(history.size() == 1 && history.getFirst().ending() == OperationData.Ending.GIVEN_UP
                        && history.getFirst().run().departures().getFirst() != DutyRun.NOT_YET,
                    "the times of the duty given up are not kept: " + history);
                return true;
            }
        }.begin();
    }

    // ------------------------------------------------------------------ the timetable

    /** A duty of the loop: out of the depot to A, train {@code number} on to B, and round to the depot, {@code later} seconds after 1A's times. */
    private static String duty(String id, String number, int later) {
        return id + "/" + number + "/" + later;
    }

    /** The clock value at which a duty {@code later} seconds after 1A first leaves. */
    private static long departure(int later) {
        return TestTimes.clock(DAY, 12, 2, 0) + later * (long) RealClock.TICKS_PER_SECOND;
    }

    private static Timetable timetable(String... duties) {
        StringBuilder trains = new StringBuilder();
        StringBuilder dutyList = new StringBuilder();
        for (String duty : duties) {
            String[] parts = duty.split("/");
            String id = parts[0];
            String number = parts[1];
            int later = Integer.parseInt(parts[2]);
            String out = "D" + number + "1";
            String in = "D" + number + "2";
            if (trains.length() > 0) {
                trains.append(",\n");
                dutyList.append(",\n");
            }
            trains.append(train(out, true, "Depot", at(later, 0), "A", at(later, 7))).append(",\n")
                .append(train(number, false, "A", at(later, 30), "B", at(later, 37))).append(",\n")
                .append(train(in, true, "B", at(later, 60), "Depot", at(later, 90)));
            dutyList.append("{\"id\": \"").append(id).append("\", \"depot\": \"Depot\", \"trains\": [\"")
                .append(out).append("\", \"").append(number).append("\", \"").append(in).append("\"]}");
        }
        String json = "{\"format\": \"createrealtimeschedule.timetable\", \"version\": 2, \"name\": \"Loops\",\n"
            + "\"lines\": [{\"id\": \"loop\", \"name\": \"Loop\", \"stations\": [\"Depot\", \"A\", \"B\"], \"loop\": true}],\n"
            + "\"trains\": [\n" + trains + "\n],\n\"duties\": [\n" + dutyList + "\n]}";
        TimetableJson.Result result = TimetableJson.parse(json);
        if (!result.ok())
            throw new IllegalStateException(result.problems() + " in " + json);
        return result.timetable();
    }

    private static String train(String number, boolean deadhead, String from, String leaves, String to, String arrives) {
        return "{\"number\": \"" + number + "\", \"line\": \"loop\"" + (deadhead ? ", \"deadhead\": true" : "") + ", \"stops\": ["
            + "{\"station\": \"" + from + "\", \"depart\": \"" + leaves + "\"}, {\"station\": \"" + to + "\", \"arrive\": \"" + arrives + "\"}]}";
    }

    /** 12:02:00, plus {@code later} and {@code seconds}, as the timetable writes it. */
    private static String at(int later, int seconds) {
        int total = 12 * 3600 + 2 * 60 + later + seconds;
        return String.format("%02d:%02d:%02d", total / 3600, total / 60 % 60, total % 60);
    }

    /** Every call left on the timetable's time: the tick after it, when Create moves the train it was sent (see {@link DutyRunTests}). */
    private static void assertOnTime(DutyRun run) {
        for (int call = 0; call < run.calls().size() - 1; call++)
            if (run.departures().get(call) != run.plannedDeparture(call) + DutyRunTests.SENT_TO_GONE)
                throw new GameTestAssertException("duty " + run.duty() + " left "
                    + run.calls().get(call).station() + " at " + TestTimes.show(run.departures().get(call)) + ", not at "
                    + TestTimes.show(run.plannedDeparture(call)));
    }

    private static void log(DutyRun run) {
        List<String> calls = new ArrayList<>();
        for (int call = 0; call < run.calls().size(); call++)
            calls.add(run.calls().get(call).station() + " arrived " + show(run.arrivals().get(call)) + " left " + show(run.departures().get(call)));
        LOGGER.info("Duty {}: {}", run.duty(), calls);
    }

    private static String show(long clock) {
        return clock == DutyRun.NOT_YET ? "-" : TestTimes.show(clock);
    }

    // ------------------------------------------------------------------ the test's run

    /**
     * The lines set up one after another, each once the one before has a free set, so that the sets
     * have waited for different times; then the timetable loaded, and {@link #watch} asked once a tick.
     */
    private abstract static class Operation {
        final GameTestHelper helper;
        private final Timetable timetable;
        private final List<TrackLine> lines;
        private final MinecraftServer server;
        private int ready;
        private boolean loaded;
        /** The notes there were when the timetable was loaded: the server keeps those of the tests before. */
        private int notesBefore;

        Operation(GameTestHelper helper, Timetable timetable, TrackLine... lines) {
            this.helper = helper;
            this.timetable = timetable;
            this.lines = List.of(lines);
            this.server = helper.getLevel().getServer();
        }

        /** True once the test has seen what it is for; it fails by throwing. */
        abstract boolean watch(OperationData data, long now);

        /** The notes made since the timetable was loaded. */
        List<OperationData.Note> newNotes(OperationData data) {
            List<OperationData.Note> notes = data.notes();
            return notes.subList(Math.min(notesBefore, notes.size()), notes.size());
        }

        void begin() {
            for (TrackLine line : lines)
                line.cleanUpWhenTheTestEnds();
            helper.testInfo.addListener(new GameTestListener() {
                @Override
                public void testStructureLoaded(GameTestInfo test) {}

                @Override
                public void testPassed(GameTestInfo test, GameTestRunner runner) {
                    unload();
                }

                @Override
                public void testFailed(GameTestInfo test, GameTestRunner runner) {
                    unload();
                }

                @Override
                public void testAddedForRerun(GameTestInfo oldTest, GameTestInfo newTest, GameTestRunner runner) {}
            });
            long next = server.overworld().getGameTime() + 1;
            ClockService.data(server).setOffset(RealClock.offsetFor(next, START));
            helper.onEachTick(this::tick);
        }

        private void unload() {
            OperationData.get(server).unloadTimetable(ClockService.now(server));
        }

        private void tick() {
            OperationData data = OperationData.get(server);
            long now = ClockService.now(server);
            if (!loaded) {
                notesBefore = data.notes().size();
                data.loadTimetable(TimetableJson.write(timetable), timetable, now);
                loaded = true;
            }
            while (ready < lines.size()) {
                TrackLine line = lines.get(ready);
                if (!line.setUp() || !data.freeSince().containsKey(line.train().id))
                    return;
                ready++;
                if (ready == lines.size())
                    helper.assertTrue(now < departure(0) - ModSettings.dutyLeadSeconds() * (long) RealClock.TICKS_PER_SECOND,
                        "the sets were not all free before the first duty was to be given one, at " + TestTimes.show(now));
            }
            if (watch(data, now))
                helper.succeed();
        }
    }
}
