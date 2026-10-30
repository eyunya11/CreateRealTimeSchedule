package io.github.eyunya11.createrealtimeschedule.gametest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.ModSettings;
import io.github.eyunya11.createrealtimeschedule.clock.ClockService;
import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.diagram.DutyTimes;
import io.github.eyunya11.createrealtimeschedule.gametest.TrackLine.Loop;
import io.github.eyunya11.createrealtimeschedule.gametest.TrackLine.Siding;
import io.github.eyunya11.createrealtimeschedule.gametest.TrackLine.Signal;
import io.github.eyunya11.createrealtimeschedule.operation.DutyRun;
import io.github.eyunya11.createrealtimeschedule.operation.OperationData;
import io.github.eyunya11.createrealtimeschedule.operation.OperationService;
import io.github.eyunya11.createrealtimeschedule.operation.TrainDriver;
import io.github.eyunya11.createrealtimeschedule.timetable.ServiceTime;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.TimetableJson;
import net.minecraft.core.BlockPos;
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
 * of Create's; the operation gives each duty a set and takes it round. Two loops, one inside the
 * other as the two tracks of a double line, are two track graphs, each with its own depot, A and B
 * side by side, so two sets can run at once without meeting.
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
    private static final Loop OUTER = new Loop(100, 8, TrackLine.TRACK_Z, 24);
    /** The other track of the double line, 4 blocks inside the outer all the way round. */
    private static final Loop INNER = OUTER.inside(4);
    private static final String[] NAMES = {"Depot", "A", "B"};
    private static final int[] STATION_X = {20, 50, 80};
    /** The clock when the test starts. */
    private static final long START = TestTimes.clock(10, 12, 0, 0);
    /** The service day the duties run on. */
    private static final long DAY = 10;

    /**
     * M5's goal: two sets at the depot take the duties in the order the duties leave, and come back to
     * the depot after them. The set on the outer loop is free first, so it takes 1A, which leaves at
     * 12:02; the other takes 2A, which leaves half a minute later. Each leaves every call on time.
     */
    @GameTest(template = TURN, batch = "realtimeoperation_two_sets", timeoutTicks = 6000)
    public static void twoSetsAtTheDepotTakeTheDutiesInTheOrderTheyLeaveAndComeBack(GameTestHelper helper) {
        Timetable timetable = timetable(duty("1A", "1", 0), duty("2A", "2", 30));
        TrackLine outer = new TrackLine(helper, OUTER, STATION_X, NAMES);
        TrackLine inner = new TrackLine(helper, INNER, STATION_X, NAMES);
        new Operation(helper, timetable, outer, inner) {
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
                helper.assertTrue(firstSet.equals(outer.train().id),
                    "1A, which leaves first, was not given the set that was free first, on the outer loop");
                helper.assertTrue(secondSet.equals(inner.train().id), "2A was not given the other set");
                assertOnTime(first);
                assertOnTime(second);
                for (TrackLine line : List.of(outer, inner)) {
                    helper.assertTrue(TrainDriver.standsAt(line.train(), "Depot"), "a set is not back at the depot (" + line.describe() + ")");
                    helper.assertTrue(data.freeSince().containsKey(line.train().id), "a set back at the depot is not free again");
                }
                for (DutyRun run : List.of(first, second)) {
                    TrackLine line = run == first ? outer : inner;
                    helper.assertTrue(data.history().stream().anyMatch(record -> record.run() == run
                            && record.status() == DutyTimes.Status.FINISHED && record.train().equals(line.train().name.getString())),
                        "the times of duty " + run.duty() + " are not kept as finished by its train: " + data.history());
                }
                helper.assertTrue(newNotes(data).isEmpty(), "something went wrong: " + newNotes(data));
                return true;
            }
        }.begin();
    }

    /**
     * Two duties leave at the same time, and there is one set: 1A gets it, and 2A starts late with it
     * once 1A has brought it back to the depot, a minute and a half later. That gets 2A back on time
     * well within the threshold of 10 minutes, so it waits for the set until then instead of not
     * running, and it is noted as started late.
     */
    @GameTest(template = TURN, batch = "realtimeoperation_one_set_short", timeoutTicks = 6000)
    public static void aDutyWithNoFreeSetByItsTimeStartsLateWhenTheSetComesFree(GameTestHelper helper) {
        helper.assertTrue(ModSettings.recoveryThresholdSeconds() == 600, "the test is for the default threshold of 10 minutes");
        Timetable timetable = timetable(duty("1A", "1", 0), duty("2A", "2", 0));
        TrackLine outer = new TrackLine(helper, OUTER, STATION_X, NAMES);
        new Operation(helper, timetable, outer) {
            @Nullable DutyRun first;
            @Nullable DutyRun second;

            @Override
            boolean watch(OperationData data, long now) {
                if (first == null) {
                    if (data.trainOf("1A").isEmpty())
                        return false;
                    helper.assertTrue(data.trainOf("1A").equals(Optional.of(outer.train().id)), "1A did not get the one set");
                    first = data.runs().get(outer.train().id);
                    return false;
                }
                if (second == null) {
                    if (data.trainOf("2A").isEmpty()) {
                        helper.assertTrue(newNotes(data).isEmpty(), "2A is noted while it waits for a set: " + newNotes(data));
                        return false;
                    }
                    helper.assertTrue(first.finished(), "2A got a set while 1A still ran with the only one");
                    helper.assertTrue(data.trainOf("2A").equals(Optional.of(outer.train().id)), "2A did not get the set 1A brought back");
                    second = data.runs().get(outer.train().id);
                    List<OperationData.Note> notes = newNotes(data);
                    helper.assertTrue(notes.size() == 1 && notes.getFirst().duty().equals("2A") && notes.getFirst().day() == DAY
                            && notes.getFirst().what().equals("started late: no free set at Depot by 12:02:00"),
                        "2A is not noted as started late: " + notes);
                    return false;
                }
                if (!second.finished())
                    return false;
                log(second);
                helper.assertTrue(second.departures().getFirst() > departure(0) + 60 * RealClock.TICKS_PER_SECOND,
                    "2A left the depot at " + TestTimes.show(second.departures().getFirst()));
                helper.assertTrue(second.skipped().isEmpty(), "2A, late by less than the threshold, was held: " + second.skipped());
                helper.assertTrue(newNotes(data).size() == 1, "something else went wrong: " + newNotes(data));
                return true;
            }
        }.begin();
    }

    /**
     * As above, but while 1A runs with the one set, the clock jumps 12 minutes on: 2A could get to A
     * only that late, more than the threshold, and does not come back to A to be held there. So it does
     * not run that day, and does not take the set when 1A brings it back.
     */
    @GameTest(template = TURN, batch = "realtimeoperation_too_late_to_start", timeoutTicks = 6000)
    public static void aDutyThatCanNoLongerGetBackOnTimeDoesNotRun(GameTestHelper helper) {
        helper.assertTrue(ModSettings.recoveryThresholdSeconds() == 600, "the test is for the default threshold of 10 minutes");
        Timetable timetable = timetable(duty("1A", "1", 0), duty("2A", "2", 0));
        TrackLine outer = new TrackLine(helper, OUTER, STATION_X, NAMES);
        new Operation(helper, timetable, outer) {
            @Nullable DutyRun first;
            boolean jumped;

            @Override
            boolean watch(OperationData data, long now) {
                if (first == null) {
                    first = data.runs().get(outer.train().id);
                    return false;
                }
                if (!jumped) {
                    if (first.phase() != DutyRun.Phase.RUNNING)
                        return false;
                    helper.assertTrue(newNotes(data).isEmpty(), "2A is noted while it can still start late: " + newNotes(data));
                    outer.clockOnNextTick(now + 12 * RealClock.TICKS_PER_MINUTE);
                    jumped = true;
                    return false;
                }
                if (!first.finished() || data.freeSince().get(outer.train().id) == null
                    || now < data.freeSince().get(outer.train().id) + 2 * RealClock.TICKS_PER_SECOND)
                    return false;
                helper.assertTrue(data.trainOf("2A").isEmpty(), "2A took the set when it was back");
                List<OperationData.Note> notes = newNotes(data);
                helper.assertTrue(notes.size() == 1 && notes.getFirst().duty().equals("2A")
                        && notes.getFirst().what().equals("did not run: no free set at Depot by its time, nor in time to start late"),
                    "2A is not noted as not run: " + notes);
                return true;
            }
        }.begin();
    }

    /**
     * Getting a late set back on time. The set of 1A goes round the loop from A to B and back to A every
     * two minutes. While it runs out of the depot to A, the clock jumps 15 minutes on, so it comes into
     * A 15 minutes late: more than the threshold of 10 minutes, and it would make up only about a
     * minute a lap. It is held at A until the first later call there that it can leave within the
     * threshold, and the laps in between are cancelled; it stands at A until then, and leaves at that
     * time.
     */
    @GameTest(template = TURN, batch = "realtimeoperation_held", timeoutTicks = 6000)
    public static void aLateSetIsHeldAtAStationUntilItCanLeaveWithinTheThreshold(GameTestHelper helper) {
        helper.assertTrue(ModSettings.recoveryThresholdSeconds() == 600, "the test is for the default threshold of 10 minutes");
        Timetable timetable = laps("12:02:00", 4);
        TrackLine outer = new TrackLine(helper, OUTER, STATION_X, NAMES);
        new Operation(helper, timetable, outer) {
            @Nullable DutyRun run;
            boolean jumped;
            int heldTo = -1;
            long leaves;

            @Override
            boolean watch(OperationData data, long now) {
                if (run == null) {
                    run = data.runs().get(outer.train().id);
                    return false;
                }
                if (!jumped) {
                    if (run.phase() != DutyRun.Phase.RUNNING)
                        return false;
                    outer.clockOnNextTick(now + 15 * RealClock.TICKS_PER_MINUTE);
                    jumped = true;
                    return false;
                }
                if (heldTo < 0) {
                    if (run.call() < 1 || run.phase() == DutyRun.Phase.RUNNING)
                        return false;
                    log(run);
                    // The first later call at A that the set can leave within 10 minutes of its time, standing 20 seconds at the least.
                    long arrived = run.arrivals().get(1);
                    for (int call = 3; call < run.calls().size() - 1 && heldTo < 0; call++) {
                        long at = Math.max(run.plannedDeparture(call), arrived + 20 * RealClock.TICKS_PER_SECOND);
                        if (run.calls().get(call).station().equals("A") && at - run.plannedDeparture(call) <= 10 * RealClock.TICKS_PER_MINUTE) {
                            heldTo = call;
                            leaves = at;
                        }
                    }
                    helper.assertTrue(heldTo == 7, "the set is not to be held to the A of 12:08:30 but to call " + heldTo
                        + ", having come into A at " + TestTimes.show(arrived));
                    helper.assertTrue(run.call() == heldTo && run.phase() == DutyRun.Phase.STANDING && run.departure() == leaves,
                        "the set is at call " + run.call() + ", to leave at " + TestTimes.show(run.departure()) + ", not held to call "
                            + heldTo + " to leave at " + TestTimes.show(leaves));
                    helper.assertTrue(run.skipped().equals(List.of(2, 3, 4, 5, 6)), "the calls left out are " + run.skipped());
                    List<OperationData.Note> notes = newNotes(data);
                    helper.assertTrue(notes.size() == 1 && notes.getFirst().what().startsWith("held at A, ")
                            && notes.getFirst().what().contains("trains L0, R0, L1, R1, L2, R2 cancelled"),
                        "the hold is not noted: " + notes);
                    return false;
                }
                if (run.departures().get(heldTo) == DutyRun.NOT_YET) {
                    helper.assertTrue(TrainDriver.standsAt(outer.train(), "A") || now >= leaves,
                        "the set has gone from A before " + TestTimes.show(leaves));
                    return false;
                }
                log(run);
                helper.assertTrue(run.departures().get(heldTo) == leaves + DutyRunTests.SENT_TO_GONE,
                    "the set left A at " + TestTimes.show(run.departures().get(heldTo)) + ", not at " + TestTimes.show(leaves));
                helper.assertTrue(run.departures().get(1) == DutyRun.NOT_YET && run.arrivals().get(heldTo) == DutyRun.NOT_YET,
                    "the set has a time for leaving the call it was held at, or for coming into the one it was held to");
                return true;
            }
        }.begin();
    }

    /**
     * The timetable is loaded at 12:00, after 1A was to start at 11:47. It still starts, late, with the
     * set that is free at the depot, and since it comes into A 13 minutes late, it is held there until
     * its first later call it can leave within 10 minutes; the laps in between are cancelled.
     */
    @GameTest(template = TURN, batch = "realtimeoperation_loaded_late", timeoutTicks = 6000)
    public static void aDutyThatWasToStartBeforeTheTimetableWasLoadedStartsLate(GameTestHelper helper) {
        helper.assertTrue(ModSettings.recoveryThresholdSeconds() == 600, "the test is for the default threshold of 10 minutes");
        Timetable timetable = laps("11:47:00", 4);
        TrackLine outer = new TrackLine(helper, OUTER, STATION_X, NAMES);
        new Operation(helper, timetable, outer) {
            @Nullable DutyRun run;
            long leaves;

            @Override
            boolean watch(OperationData data, long now) {
                if (run == null) {
                    run = data.runs().get(outer.train().id);
                    if (run == null)
                        return false;
                    List<OperationData.Note> notes = newNotes(data);
                    helper.assertTrue(notes.size() == 1 && notes.getFirst().what().equals("started late: the timetable was loaded after 11:47:00"),
                        "1A is not noted as started late: " + notes);
                    return false;
                }
                if (leaves == 0) {
                    if (run.call() < 1 || run.phase() == DutyRun.Phase.RUNNING)
                        return false;
                    log(run);
                    helper.assertTrue(run.call() == 5 && run.skipped().equals(List.of(2, 3, 4)),
                        "1A is not held at A from its first call to the A of 11:51:30, but at call " + run.call() + " leaving out " + run.skipped());
                    List<OperationData.Note> notes = newNotes(data);
                    helper.assertTrue(notes.size() == 2 && notes.getLast().what().startsWith("held at A, ")
                        && notes.getLast().what().contains("trains L0, R0, L1, R1 cancelled"), "the hold is not noted: " + notes);
                    leaves = run.departure();
                    return false;
                }
                if (run.departures().get(5) == DutyRun.NOT_YET)
                    return false;
                helper.assertTrue(run.departures().get(5) == leaves + DutyRunTests.SENT_TO_GONE,
                    "1A left A at " + TestTimes.show(run.departures().get(5)) + ", not at " + TestTimes.show(leaves));
                return true;
            }
        }.begin();
    }

    // ------------------------------------------------------------------ waiting for a train

    /*
     * The waits as a local stands aside on a siding of a double line are below (standing aside on a
     * siding). This one runs on, once it has seen what it is for, until the set is back at the depot, so
     * that the train can be watched going on after the wait on a client too (/test run).
     */

    /** With one set, 2A does not run, and the local does not wait for its express: it leaves A on its time. */
    @GameTest(template = TURN, batch = "realtimeoperation_waits_for_none", timeoutTicks = 8000)
    public static void aLocalDoesNotWaitForATrainThatDoesNotRun(GameTestHelper helper) {
        TrackLine outer = new TrackLine(helper, OUTER, STATION_X, NAMES);
        new Operation(helper, overtaking(), outer) {
            @Nullable DutyRun local;

            @Override
            boolean watch(OperationData data, long now) {
                if (local == null) {
                    local = runOf(data, "1A");
                    return false;
                }
                if (local.departures().get(1) == DutyRun.NOT_YET)
                    return false;
                helper.assertTrue(data.trainOf("2A").isEmpty(), "2A got a set");
                helper.assertTrue(local.departures().get(1) == local.plannedDeparture(1) + DutyRunTests.SENT_TO_GONE,
                    "the local left A at " + TestTimes.show(local.departures().get(1)) + ", not on its time");
                return true;
            }
        }.toTheEnd().begin();
    }

    /** A player gives the set a schedule of Create's while it runs a duty: the operation lets go of it, and does not take it again. */
    @GameTest(template = TURN, batch = "realtimeoperation_let_go", timeoutTicks = 4000)
    public static void aSetGivenAScheduleIsLetGo(GameTestHelper helper) {
        Timetable timetable = timetable(duty("1A", "1", 0));
        TrackLine outer = new TrackLine(helper, OUTER, STATION_X, NAMES);
        new Operation(helper, timetable, outer) {
            long letGoAt = -1;

            @Override
            boolean watch(OperationData data, long now) {
                Train train = outer.train();
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
                helper.assertTrue(history.size() == 1 && history.getFirst().status() == DutyTimes.Status.GIVEN_UP
                        && history.getFirst().run().departures().getFirst() != DutyRun.NOT_YET,
                    "the times of the duty given up are not kept: " + history);
                return true;
            }
        }.begin();
    }

    /**
     * M7's goal: a timetable made with the editor's own code runs as it says. The timetable is the one
     * src/test/js/editor-made-timetable.js makes with the editor's changes only (a line, two trains
     * drawn from A to B, two duties, and the deadheads the editor adds to them), written out beside
     * this class. Both duties leave every call on time and come back to the depot.
     */
    @GameTest(template = TURN, batch = "realtimeoperation_editor_made", timeoutTicks = 6000)
    public static void aTimetableMadeInTheEditorRunsAsItSays(GameTestHelper helper) {
        Timetable timetable = editorMade();
        TrackLine outer = new TrackLine(helper, OUTER, STATION_X, NAMES);
        TrackLine inner = new TrackLine(helper, INNER, STATION_X, NAMES);
        new Operation(helper, timetable, outer, inner) {
            final List<DutyRun> seen = new ArrayList<>();

            @Override
            boolean watch(OperationData data, long now) {
                for (DutyRun run : data.runs().values())
                    if (!seen.contains(run))
                        seen.add(run);
                if (seen.size() < 2 || seen.stream().anyMatch(run -> !run.finished()))
                    return false;
                for (DutyRun run : seen) {
                    log(run);
                    assertOnTime(run);
                }
                helper.assertTrue(seen.stream().map(DutyRun::duty).sorted().toList().equals(List.of("1A", "2A")),
                    "the duties run are not the editor's two: " + seen.stream().map(DutyRun::duty).toList());
                for (TrackLine line : List.of(outer, inner))
                    helper.assertTrue(TrainDriver.standsAt(line.train(), "Depot") && data.freeSince().containsKey(line.train().id),
                        "a set is not back and free at the depot (" + line.describe() + ")");
                helper.assertTrue(newNotes(data).isEmpty(), "something went wrong: " + newNotes(data));
                return true;
            }
        }.begin();
    }

    /** The timetable that src/test/js/editor-made-timetable.js makes with the editor's own code. */
    private static Timetable editorMade() {
        try (InputStream in = OperationTests.class.getResourceAsStream("editor-made-timetable.json")) {
            if (in == null)
                throw new IllegalStateException("editor-made-timetable.json is not beside OperationTests");
            TimetableJson.Result result = TimetableJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (!result.ok())
                throw new IllegalStateException("the timetable the editor made is refused: " + result.problems());
            return result.timetable();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ standing aside on a siding

    /** The siding beside the main line at A, 3 blocks south of it, from x = 40 out to 56 and from 82 back to 98. */
    private static final Siding SIDING = new Siding(40, 56, 82, 98, 3);
    /** The two depots behind each other, A on the main line and A2 on the siding beside it, and B. */
    private static final String[] SIDING_NAMES = {"Dx", "Dl", "A", "A2", "B"};
    /**
     * Signals for trains running east: before the junction out, at the start and the end of the siding,
     * before the junction back on the main line, and past it. Between them, the junctions and the main
     * line beside the siding are one block, the siding another, and the junction back a third.
     */
    private static final List<Signal> SIDING_SIGNALS = List.of(new Signal(36, true), new Signal(60, true, SIDING.z()),
        new Signal(78, true, SIDING.z()), new Signal(92, true), new Signal(104, true));
    /**
     * When the late express is back at full throttle: some 35 blocks out of Dx, short of the junction out
     * and A. A late train runs on at the line's speed. Run at {@link #SLOW} past A, it would not yet have
     * the block of the junction back when its last carriage went past A, and the local, let go then,
     * could take that block first and hold it up.
     */
    private static final long SIDING_FULL_THROTTLE = TestTimes.clock(DAY, 12, 2, 40);
    /** How long the express may take from past A to B, at the most, if it is not held up on the way. */
    private static final long EXPRESS_A_TO_B = 10 * RealClock.TICKS_PER_SECOND;
    /** How long the local may stand after the express has run through A: until the junction back is clear of it. */
    private static final long JUNCTION_CLEAR = 10 * RealClock.TICKS_PER_SECOND;
    /** How long the test runs on after the local has left, so that it can be watched going on a client too. */
    private static final long WATCHED_ON = 5 * RealClock.TICKS_PER_SECOND;

    /*
     * Each timetable here has, on time, the express past A, or gone from it, before the local's time to
     * leave, as a timetable is made: a test that has the local wait makes the express late.
     */

    /** The throttle a late express runs at: some 1.4 blocks a second. */
    private static final double SLOW = 0.05;

    /** Has the train that runs {@code duty} run at {@link #SLOW} until {@code fast}, and at full throttle after. */
    private static void slowUntil(OperationData data, String duty, boolean fast) {
        Train train = data.trainOf(duty).map(Create.RAILWAYS.trains::get).orElse(null);
        if (train != null)
            train.throttle = fast ? 1 : SLOW;
    }

    /**
     * The line of the siding, with the express's set at Dx and the local's parked at Dl behind it: the
     * one track of a double line that runs east, as a local and an express share it, with a siding at
     * the station A where the local stands aside.
     */
    private static TrackLine sidingLine(GameTestHelper helper) {
        return new TrackLine(helper, 115, SIDING,
            new BlockPos[] {onTheMainLine(8), onTheMainLine(24), onTheMainLine(72), new BlockPos(72, TrackLine.TRACK_Y, SIDING.z()),
                onTheMainLine(110)},
            SIDING_NAMES, SIDING_SIGNALS).parkATrainAt(1);
    }

    /**
     * A timetable is made so that, on time, no train waits past its time: the local of 1A, out of its
     * depot Dl at 12:02:00 and at A2 on the siding by 12:02:12, is to leave at 12:02:35, and the express
     * E of 2A, out of its depot Dx behind it at 12:02:15, runs through A on the main line well before
     * then. The local leaves on its time.
     */
    @GameTest(template = TURN, batch = "realtimeoperation_siding_on_time", timeoutTicks = 6000)
    public static void aLocalLeavesTheSidingOnItsTimeWhenTheExpressRunsThroughOnTime(GameTestHelper helper) {
        new Operation(helper, sidingTimetable(false, Timetable.Stop.NONE), sidingLine(helper)) {
            @Nullable DutyRun local;
            @Nullable DutyRun express;

            @Override
            boolean watch(OperationData data, long now) {
                if (local == null || express == null) {
                    local = runOf(data, "1A");
                    express = runOf(data, "2A");
                    return false;
                }
                if (local.departures().get(1) == DutyRun.NOT_YET)
                    return false;
                log(local);
                log(express);
                helper.assertTrue(local.arrivals().get(1) != DutyRun.NOT_YET, "the local did not stand at A2 on the siding");
                List<DutyRun.Pass> passes = express.passes();
                helper.assertTrue(passes.size() == 1 && passes.getFirst().station().equals("A") && passes.getFirst().at() < local.plannedDeparture(1),
                    "the express did not run through A before the local's time: " + passes);
                helper.assertTrue(local.departures().get(1) == local.plannedDeparture(1) + DutyRunTests.SENT_TO_GONE,
                    "the local left A2 at " + TestTimes.show(local.departures().get(1)) + ", not on its time");
                helper.assertTrue(newNotes(data).isEmpty(), "something went wrong: " + newNotes(data));
                return true;
            }
        }.runOn(WATCHED_ON).begin();
    }

    /**
     * M9's goal: the express is late, run at {@link #SLOW} until {@link #SIDING_FULL_THROTTLE}, so it
     * runs through A after the local's 12:02:35. The local waits at A2 until the express has run
     * through A on the main line beside it, its last carriage gone past, and leaves on the next tick.
     * The signals keep the two apart from then on: the express is not held up by the local standing
     * aside, and the local goes back onto the main line once the express has cleared the junction back.
     */
    @GameTest(template = TURN, batch = "realtimeoperation_siding", timeoutTicks = 6000)
    public static void aLocalStandsAsideOnASidingWhileALateExpressRunsThrough(GameTestHelper helper) {
        new Operation(helper, sidingTimetable(false, Timetable.Stop.NONE), sidingLine(helper)) {
            @Nullable DutyRun local;
            @Nullable DutyRun express;
            long leftAt = Long.MIN_VALUE;

            @Override
            boolean watch(OperationData data, long now) {
                if (local == null || express == null) {
                    local = runOf(data, "1A");
                    express = runOf(data, "2A");
                    return false;
                }
                slowUntil(data, "2A", now >= SIDING_FULL_THROTTLE);
                if (local.departures().get(1) == DutyRun.NOT_YET) {
                    helper.assertTrue(local.call() <= 1, "the local left A2 without its time being recorded");
                    return false;
                }
                if (leftAt == Long.MIN_VALUE) {
                    leftAt = now;
                    log(local);
                    helper.assertTrue(local.arrivals().get(1) != DutyRun.NOT_YET, "the local did not stand at A2 on the siding");
                    List<DutyRun.Pass> passes = express.passes();
                    helper.assertTrue(passes.size() == 1 && passes.getFirst().way() == 0 && passes.getFirst().station().equals("A"),
                        "the express is not seen to run through A once: " + passes);
                    long passed = passes.getFirst().at();
                    helper.assertTrue(passed > local.plannedDeparture(1), "the express ran through A by " + TestTimes.show(passed)
                        + ", before the local was to leave: the test shows no wait");
                    long gone = local.departures().get(1);
                    helper.assertTrue(gone > passed && gone <= passed + JUNCTION_CLEAR, "the local left A2 at " + TestTimes.show(gone)
                        + ", not soon after the express ran through A at " + TestTimes.show(passed));
                    helper.assertTrue(newNotes(data).isEmpty(), "something went wrong: " + newNotes(data));
                }
                long passed = express.passes().getFirst().at();
                long atB = express.arrivals().get(1);
                if (now < leftAt + WATCHED_ON || atB == DutyRun.NOT_YET && now <= passed + EXPRESS_A_TO_B)
                    return false;
                log(express);
                helper.assertTrue(atB != DutyRun.NOT_YET && atB - passed <= EXPRESS_A_TO_B, "the express ran through A at " + TestTimes.show(passed)
                    + " and was " + (atB == DutyRun.NOT_YET ? "not at B by " + TestTimes.show(now) : "at B at " + TestTimes.show(atB)) + ": held up on the way");
                return true;
            }
        }.begin();
    }

    /**
     * The express calls at A on the main line instead, from 12:02:23 to 12:02:27, and leaves on its
     * time: the local on the siding, which keeps the timetable's 8 seconds after it, leaves on its own
     * time too, not a tick later.
     */
    @GameTest(template = TURN, batch = "realtimeoperation_siding_leaving_on_time", timeoutTicks = 6000)
    public static void aLocalLeavesTheSidingOnItsTimeAfterAnExpressThatCallsAndLeavesOnTime(GameTestHelper helper) {
        new Operation(helper, sidingTimetable(true, Timetable.Stop.NONE), sidingLine(helper)) {
            @Nullable DutyRun local;
            @Nullable DutyRun express;

            @Override
            boolean watch(OperationData data, long now) {
                if (local == null || express == null) {
                    local = runOf(data, "1A");
                    express = runOf(data, "2A");
                    return false;
                }
                if (local.departures().get(1) == DutyRun.NOT_YET)
                    return false;
                log(local);
                log(express);
                helper.assertTrue(express.departures().get(1) == express.plannedDeparture(1) + DutyRunTests.SENT_TO_GONE,
                    "the express left A at " + show(express.departures().get(1)) + ", not on its time: the test shows nothing");
                helper.assertTrue(local.departures().get(1) == local.plannedDeparture(1) + DutyRunTests.SENT_TO_GONE,
                    "the local left A2 at " + TestTimes.show(local.departures().get(1)) + ", not on its time");
                helper.assertTrue(newNotes(data).isEmpty(), "something went wrong: " + newNotes(data));
                return true;
            }
        }.runOn(WATCHED_ON).begin();
    }

    /**
     * The express calls at A, late, run at {@link #SLOW} until {@link #SIDING_FULL_THROTTLE}: it leaves
     * A later than the local's 12:02:35. The local keeps the timetable's 8 seconds from the express's
     * leaving to its own, counted from the second the express left in, and so leaves as late as the
     * express did.
     */
    @GameTest(template = TURN, batch = "realtimeoperation_siding_leaving", timeoutTicks = 6000)
    public static void aLocalOnTheSidingKeepsTheTimetablesGapAfterALateExpressThatCalls(GameTestHelper helper) {
        new Operation(helper, sidingTimetable(true, Timetable.Stop.NONE), sidingLine(helper)) {
            @Nullable DutyRun local;
            @Nullable DutyRun express;

            @Override
            boolean watch(OperationData data, long now) {
                if (local == null || express == null) {
                    local = runOf(data, "1A");
                    express = runOf(data, "2A");
                    return false;
                }
                slowUntil(data, "2A", now >= SIDING_FULL_THROTTLE);
                if (local.departures().get(1) == DutyRun.NOT_YET)
                    return false;
                log(local);
                log(express);
                long left = express.departures().get(1);
                helper.assertTrue(left != DutyRun.NOT_YET && left > local.plannedDeparture(1),
                    "the express left A at " + show(left) + ": the test shows no wait");
                long gone = local.departures().get(1);
                long kept = left - Math.floorMod(left, RealClock.TICKS_PER_SECOND) + (local.plannedDeparture(1) - express.plannedDeparture(1));
                helper.assertTrue(gone == kept + DutyRunTests.SENT_TO_GONE, "the local left A2 at " + TestTimes.show(gone)
                    + ", not the timetable's gap after the express left A at " + TestTimes.show(left));
                helper.assertTrue(newNotes(data).isEmpty(), "something went wrong: " + newNotes(data));
                return true;
            }
        }.runOn(WATCHED_ON).begin();
    }

    /**
     * The local's wait says it waits no time past its own (max_wait 0): when the late express is not
     * through A by then, it gives the wait up, notes so, and leaves the siding before the express runs
     * through A.
     */
    @GameTest(template = TURN, batch = "realtimeoperation_siding_waits_no_longer", timeoutTicks = 6000)
    public static void aLocalOnTheSidingGivesUpAWaitPastItsLimit(GameTestHelper helper) {
        new Operation(helper, sidingTimetable(false, 0), sidingLine(helper)) {
            @Nullable DutyRun local;
            @Nullable DutyRun express;

            @Override
            boolean watch(OperationData data, long now) {
                if (local == null || express == null) {
                    local = runOf(data, "1A");
                    express = runOf(data, "2A");
                    return false;
                }
                slowUntil(data, "2A", now >= SIDING_FULL_THROTTLE);
                if (local.departures().get(1) == DutyRun.NOT_YET)
                    return false;
                long gone = local.departures().get(1);
                helper.assertTrue(gone <= local.plannedDeparture(1) + 3, "the local left A2 at " + TestTimes.show(gone) + ", not as soon as its time was past");
                helper.assertTrue(express.passes().isEmpty(), "the express ran through A before the local left: the test shows nothing");
                List<OperationData.Note> notes = newNotes(data);
                helper.assertTrue(notes.size() == 1 && notes.getFirst().duty().equals("1A")
                    && notes.getFirst().what().startsWith("did not wait longer for train E to leave A"), "the wait is not noted as given up: " + notes);
                return true;
            }
        }.runOn(WATCHED_ON).begin();
    }

    private static BlockPos onTheMainLine(int x) {
        return new BlockPos(x, TrackLine.TRACK_Y, TrackLine.TRACK_Z);
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

    /**
     * The local of 1A out of Dl to the siding at A2, and from there, at 12:02:35, once the express E of
     * 2A has left A or run through it, to B; it waits as long as {@code maxWait} seconds past its time
     * (or the server's, for {@link Timetable.Stop#NONE}). E runs out of Dx at 12:02:15 to B, calling at
     * A on the main line from 12:02:23 to 12:02:27 if {@code callsAtA}. As a timetable is made, E on time
     * is past A, or gone from it 8 seconds, by the local's time: the local waits for E only when E is
     * late. The deadheads back to the depots are never run: the sets cannot turn round, and the test
     * ends before.
     */
    private static Timetable sidingTimetable(boolean callsAtA, int maxWait) {
        int start = ServiceTime.of("12:02:00");
        Timetable.Stop waits = new Timetable.Stop("A2", Timetable.Stop.NONE, start + 35, Timetable.Stop.NONE,
            List.of(new Timetable.Wait("E", "A", maxWait)));
        List<Timetable.Stop> express = new ArrayList<>();
        express.add(Timetable.Stop.leaving("Dx", start + 15));
        if (callsAtA)
            express.add(new Timetable.Stop("A", start + 23, start + 27, 2));
        express.add(Timetable.Stop.arriving("B", start + (callsAtA ? 35 : 30)));
        Timetable timetable = new Timetable("Siding", List.of(new Timetable.Line("main", "Main", List.of(SIDING_NAMES), false)), List.of(
            new Timetable.Train("D1", "main", "", List.of(Timetable.Stop.leaving("Dl", start), Timetable.Stop.arriving("A2", start + 12)), true),
            new Timetable.Train("L", "main", "local", List.of(waits, Timetable.Stop.arriving("B", start + 50))),
            new Timetable.Train("D2", "main", "", List.of(Timetable.Stop.leaving("B", start + 70), Timetable.Stop.arriving("Dl", start + 100)), true),
            new Timetable.Train("E", "main", "rapid", express),
            new Timetable.Train("R", "main", "", List.of(Timetable.Stop.leaving("B", start + 60), Timetable.Stop.arriving("Dx", start + 90)), true)),
            List.of(new Timetable.Duty("1A", "Dl", List.of("D1", "L", "D2")), new Timetable.Duty("2A", "Dx", List.of("E", "R"))));
        if (!timetable.problems().isEmpty())
            throw new IllegalStateException(timetable.problems().toString());
        return timetable;
    }

    /**
     * 1A, a local: out of the depot at 12:02 to A, on to B from 12:02:40, back to the depot. At A it
     * waits for the express E of 2A at A. 2A runs E out of the depot at 12:02:25 round to B, and back to
     * the depot.
     */
    private static Timetable overtaking() {
        int start = ServiceTime.of("12:02:00");
        Timetable.Stop waits = new Timetable.Stop("A", Timetable.Stop.NONE, start + 40, Timetable.Stop.NONE,
            List.of(new Timetable.Wait("E")));
        Timetable.Train out = new Timetable.Train("D1", "loop", "",
            List.of(Timetable.Stop.leaving("Depot", start), Timetable.Stop.arriving("A", start + 10)), true);
        Timetable.Train local = new Timetable.Train("L", "loop", "local", List.of(waits, Timetable.Stop.arriving("B", start + 50)));
        Timetable.Train in = new Timetable.Train("D2", "loop", "",
            List.of(Timetable.Stop.leaving("B", start + 70), Timetable.Stop.arriving("Depot", start + 100)), true);
        // Out to B and round to the depot again in one train, so that it runs down the line through A.
        Timetable.Train express = new Timetable.Train("E", "loop", "rapid", List.of(Timetable.Stop.leaving("Depot", start + 25),
            Timetable.Stop.calling("B", start + 42, start + 62), Timetable.Stop.arriving("Depot", start + 100)));
        Timetable timetable = new Timetable("Overtaking", List.of(new Timetable.Line("loop", "Loop", List.of("Depot", "A", "B"), true)),
            List.of(out, local, in, express),
            List.of(new Timetable.Duty("1A", "Depot", List.of("D1", "L", "D2")), new Timetable.Duty("2A", "Depot", List.of("E"))));
        if (!timetable.problems().isEmpty())
            throw new IllegalStateException(timetable.problems().toString());
        return timetable;
    }

    /**
     * The duty 1A of the loop: out of the depot at {@code from} to A, {@code count} laps from A to B and
     * round to A again, one every two minutes from half a minute after {@code from}, the trains L0 and
     * R0, L1 and R1, and so on, and into the depot again. The set stands 20 seconds at B and 40 at A.
     */
    private static Timetable laps(String from, int count) {
        int start = ServiceTime.of(from);
        List<Timetable.Train> trains = new ArrayList<>();
        trains.add(new Timetable.Train("D1", "loop", "", List.of(Timetable.Stop.leaving("Depot", start), Timetable.Stop.arriving("A", start + 10)),
            true));
        for (int lap = 0; lap < count; lap++) {
            int leaves = start + 30 + 120 * lap;
            trains.add(new Timetable.Train("L" + lap, "loop", "local",
                List.of(Timetable.Stop.leaving("A", leaves), Timetable.Stop.arriving("B", leaves + 10))));
            trains.add(new Timetable.Train("R" + lap, "loop", "local",
                List.of(Timetable.Stop.leaving("B", leaves + 30), Timetable.Stop.arriving("A", leaves + 80))));
        }
        int back = start + 30 + 120 * count;
        trains.add(new Timetable.Train("D2", "loop", "", List.of(Timetable.Stop.leaving("A", back), Timetable.Stop.arriving("Depot", back + 60)),
            true));
        Timetable timetable = new Timetable("Laps", List.of(new Timetable.Line("loop", "Loop", List.of("Depot", "A", "B"), true)), trains,
            List.of(new Timetable.Duty("1A", "Depot", trains.stream().map(Timetable.Train::number).toList())));
        if (!timetable.problems().isEmpty())
            throw new IllegalStateException(timetable.problems().toString());
        return timetable;
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
     * The lines set up one after another, each once the one before has a free set (or one given a duty
     * already), so that the sets have waited for different times; then the timetable loaded, and
     * {@link #watch} asked once a tick.
     */
    private abstract static class Operation {
        final GameTestHelper helper;
        private final Timetable timetable;
        private final List<TrackLine> lines;
        private final MinecraftServer server;
        private int ready;
        private boolean loaded;
        /** Whether the test has seen what it is for. */
        private boolean seen;
        /** Whether it then runs on until every line's set is back and free at the depot. */
        private boolean toTheEnd;
        /** How long it then runs on, in ticks, at the least. */
        private long runOn;
        /** The clock when it saw what it is for. */
        private long seenAt;
        /**
         * The notes there were when the timetable was loaded, the very objects: the server keeps those
         * of the tests before, and of the runs before, since the test world is saved. Not how many there
         * were, since the server keeps only the last 50; nor what they say, since every run sets the
         * same clock and so makes the same notes as the run before.
         */
        private Set<OperationData.Note> notesBefore = Set.of();

        Operation(GameTestHelper helper, Timetable timetable, TrackLine... lines) {
            this.helper = helper;
            this.timetable = timetable;
            this.lines = List.of(lines);
            this.server = helper.getLevel().getServer();
        }

        /** The run of {@code duty} going on, once it has been given a set; null until then. */
        @Nullable DutyRun runOf(OperationData data, String duty) {
            return data.trainOf(duty).map(id -> data.runs().get(id)).orElse(null);
        }

        /** True once the test has seen what it is for; it fails by throwing. It is not asked again after that. */
        abstract boolean watch(OperationData data, long now);

        /** Has the test run on, once it has seen what it is for, until every line's set is back and free at the depot. */
        Operation toTheEnd() {
            toTheEnd = true;
            return this;
        }

        /**
         * Has the test run on for {@code ticks}, once it has seen what it is for, so that the trains can
         * be watched going on on a client too: on a line the sets cannot come back to the depot along.
         */
        Operation runOn(long ticks) {
            runOn = ticks;
            return this;
        }

        /** The notes made since the timetable was loaded. */
        List<OperationData.Note> newNotes(OperationData data) {
            return data.notes().stream().filter(note -> !notesBefore.contains(note)).toList();
        }

        private static Set<OperationData.Note> identities(List<OperationData.Note> notes) {
            Set<OperationData.Note> set = Collections.newSetFromMap(new IdentityHashMap<>());
            set.addAll(notes);
            return set;
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
                notesBefore = identities(data.notes());
                data.loadTimetable(TimetableJson.write(timetable), timetable, now);
                loaded = true;
            }
            while (ready < lines.size()) {
                TrackLine line = lines.get(ready);
                // A duty that is late already takes its set as soon as the set is free, before this sees it free.
                if (!line.setUp() || !data.freeSince().containsKey(line.train().id) && !data.runs().containsKey(line.train().id))
                    return;
                ready++;
                if (ready == lines.size())
                    helper.assertTrue(now < departure(0) - ModSettings.dutyLeadSeconds() * (long) RealClock.TICKS_PER_SECOND,
                        "the sets were not all free before the first duty was to be given one, at " + TestTimes.show(now));
            }
            if (!seen) {
                seen = watch(data, now);
                seenAt = now;
            }
            if (seen && now >= seenAt + runOn && (!toTheEnd || lines.stream().allMatch(line -> data.freeSince().containsKey(line.train().id))))
                helper.succeed();
        }
    }
}
