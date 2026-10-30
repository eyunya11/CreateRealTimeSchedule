package io.github.eyunya11.createrealtimeschedule.gametest;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.simibubi.create.content.trains.entity.Train;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.clock.ClockService;
import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.diagram.DiagramService;
import io.github.eyunya11.createrealtimeschedule.diagram.DutyTimes;
import io.github.eyunya11.createrealtimeschedule.diagram.LineAxis;
import io.github.eyunya11.createrealtimeschedule.network.DiagramSnapshotPayload;
import io.github.eyunya11.createrealtimeschedule.operation.DutyRun;
import io.github.eyunya11.createrealtimeschedule.operation.OperationData;
import io.github.eyunya11.createrealtimeschedule.operation.TrackFacts;
import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.TimetableJson;
import io.github.eyunya11.createrealtimeschedule.travel.TrainSpecs;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * What the server sends a client for the diagram ({@link DiagramService}), with
 * {@code ./gradlew runGameTestServer}: the axis of a line from the track Create built, and the
 * timetable and the duties' times as they go over the network.
 *
 * <p>Each test has a batch of its own, because the operation is the server's.
 */
@GameTestHolder(Createrealtimeschedule.MODID)
@PrefixGameTestTemplate(false)
public class DiagramTests {

    private static final String EMPTY = "empty";
    private static final String TRACK_LINE = "track_line";
    private static final double DISTANCE_TOLERANCE = 0.01;

    /** Alpha, Beta and Gamma 20 blocks apart, as in {@link NetworkExportTests}: the axis has them 20 and 40 blocks down. */
    @GameTest(template = TRACK_LINE, batch = "realtimediagram_axis", timeoutTicks = 1500)
    public static void theAxisOfALineIsTheTrackBetweenItsStations(GameTestHelper helper) {
        TrackLine track = new TrackLine(helper, 50, new int[] {6, 26, 46}, new String[] {"Alpha", "Beta", "Gamma"});
        track.cleanUpWhenTheTestEnds();
        Timetable timetable = TimetableJson.parse("""
            {"format": "createrealtimeschedule.timetable", "version": 2, "name": "",
             "lines": [{"id": "l", "name": "Line", "stations": ["Alpha", "Beta", "Gamma"]}], "trains": [], "duties": []}
            """).timetable();
        helper.assertTrue(timetable != null, "the timetable does not read");
        boolean[] done = {false};
        helper.onEachTick(() -> {
            if (done[0] || !track.setUp())
                return;
            done[0] = true;
            OperationData data = new OperationData();
            LineAxis axis = DiagramService.axes(data, timetable).get("l");
            helper.assertTrue(axis != null && axis.stations().equals(List.of("Alpha", "Beta", "Gamma")), "the axis is " + axis);
            near(helper, 0, axis.position(0), "Alpha");
            near(helper, 20, axis.position(1), "Beta");
            near(helper, 40, axis.position(2), "Gamma");
            helper.assertTrue(DiagramService.axes(data, timetable).get("l") == axis, "the axis is worked out again each time");
            helper.succeed();
        });
    }

    /**
     * The same line, with Beta renamed Bravo while the timetable is run: the ways and the axis are worked
     * out again once the track has stayed so for {@link TrackFacts#SETTLE_TICKS}, and not before, and
     * again once Bravo is Beta again. A client's copy of the axes is old each time they change, and only
     * then.
     */
    @GameTest(template = TRACK_LINE, batch = "realtimediagram_track_changes", timeoutTicks = 1500)
    public static void theAxesFollowTheTrackOnceItHasStayedAsItIs(GameTestHelper helper) {
        TrackLine track = new TrackLine(helper, 50, new int[] {6, 26, 46}, new String[] {"Alpha", "Beta", "Gamma"});
        track.cleanUpWhenTheTestEnds();
        Timetable timetable = TimetableJson.parse("""
            {"format": "createrealtimeschedule.timetable", "version": 2, "name": "",
             "lines": [{"id": "l", "name": "Line", "stations": ["Alpha", "Beta", "Gamma"]}], "trains": [], "duties": []}
            """).timetable();
        helper.assertTrue(timetable != null, "the timetable does not read");
        OperationData data = new OperationData();
        MinecraftServer server = helper.getLevel().getServer();
        String[] names = {"Bravo", "Beta"};
        int[] step = {-1};
        int[] changedAt = {0};
        int[] revision = {0};
        helper.onEachTick(() -> {
            if (!track.setUp())
                return;
            int tick = server.getTickCount();
            if (step[0] < 0) {
                near(helper, 20, DiagramService.axes(data, timetable).get("l").position(1), "Beta, as the track was built");
                step[0] = 0;
                revision[0] = data.revision();
                changedAt[0] = tick;
                track.station(1).name = names[0];
                return;
            }
            TrackFacts.watch(data, timetable, tick);
            boolean bravo = names[step[0]].equals("Bravo");
            if (data.revision() == revision[0]) {
                helper.assertTrue(tick - changedAt[0] <= TrackFacts.SETTLE_TICKS + 1,
                    "the axes are not worked out again " + (tick - changedAt[0]) + " ticks after Beta was renamed " + names[step[0]]);
                return;
            }
            helper.assertTrue(tick - changedAt[0] >= TrackFacts.SETTLE_TICKS,
                "the axes are worked out again " + (tick - changedAt[0]) + " ticks after the track changed, before it settled");
            helper.assertTrue(data.legs().containsKey(List.of("Alpha", "Beta")) != bravo,
                "with the station named " + names[step[0]] + ", the ways are " + data.legs().keySet());
            LineAxis axis = data.axes().get("l");
            if (!bravo)
                near(helper, 20, axis.position(1), "Beta, named so again");
            if (step[0] == 1) {
                helper.succeed();
                return;
            }
            step[0] = 1;
            revision[0] = data.revision();
            changedAt[0] = tick;
            track.station(1).name = names[1];
        });
    }

    /**
     * The same line: a train waited for is forecast at its own speed, that of a train with fuel while it
     * has some and of one without while it has none, and whether a late duty can still start goes by
     * the faster of the two. By Create's defaults the two differ (40 and 28 blocks a second).
     */
    @GameTest(template = TRACK_LINE, batch = "realtimediagram_train_times", timeoutTicks = 1500)
    public static void aTrainIsTimedAtItsOwnSpeed(GameTestHelper helper) {
        TrackLine track = new TrackLine(helper, 50, new int[] {6, 26, 46}, new String[] {"Alpha", "Beta", "Gamma"});
        track.cleanUpWhenTheTestEnds();
        Timetable timetable = TimetableJson.parse("""
            {"format": "createrealtimeschedule.timetable", "version": 2, "name": "",
             "lines": [{"id": "l", "name": "Line", "stations": ["Alpha", "Beta", "Gamma"]}], "trains": [], "duties": []}
            """).timetable();
        helper.assertTrue(timetable != null, "the timetable does not read");
        OperationData data = new OperationData();
        boolean[] done = {false};
        helper.onEachTick(() -> {
            if (done[0] || !track.setUp())
                return;
            done[0] = true;
            Map<List<String>, Leg> legs = TrackFacts.legs(data, timetable);
            Train train = track.train();
            long powered = TrackFacts.runTimes(legs, TrainSpecs.powered(), train.throttle).ticks("Alpha", "Gamma").orElseThrow();
            long unpowered = TrackFacts.runTimes(legs, TrainSpecs.unpowered(), train.throttle).ticks("Alpha", "Gamma").orElseThrow();
            helper.assertTrue(powered < unpowered, "a train with fuel takes " + powered + " ticks from Alpha to Gamma, and one without " + unpowered);
            train.fuelTicks = 100;
            helper.assertValueEqual(OptionalLong.of(powered), TrackFacts.timesOf(train, legs).ticks("Alpha", "Gamma"), "the train with fuel");
            train.fuelTicks = 0;
            helper.assertValueEqual(OptionalLong.of(unpowered), TrackFacts.timesOf(train, legs).ticks("Alpha", "Gamma"), "the train without fuel");
            helper.assertValueEqual(OptionalLong.of(powered), TrackFacts.fastestTimes(legs).ticks("Alpha", "Gamma"), "the fastest any train takes");
            helper.succeed();
        });
    }

    /**
     * A client with no timetable is sent it, with the times of the duty running and of the one that has
     * finished; one that has the timetable is sent the times only. What is sent reads back the same.
     */
    @GameTest(template = EMPTY, batch = "realtimediagram_snapshot")
    public static void theSnapshotHasTheTimesAndLeavesOutWhatTheClientHas(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        OperationData data = OperationData.get(server);
        try {
            long now = ClockService.now(server);
            String json = TimetableCommandTests.TIMETABLE;
            data.loadTimetable(json, TimetableJson.parse(json).timetable(), now);
            DutyRun running = DutyRun.start("1A", RealClock.day(now), data.calls("1A"), now);
            running.left(running.departure());
            data.runs().put(new UUID(7, 7), running);
            DutyRun done = DutyRun.start("1A", RealClock.day(now) - 1, data.calls("1A"), now);
            data.keep(done, "Old set", DutyTimes.Status.FINISHED, now);

            DiagramSnapshotPayload first = DiagramService.snapshot(server, -1, -1);
            helper.assertTrue(first.revision() == data.revision() && first.timesRevision() == data.timesRevision(),
                "the snapshot is not of the timetable's and the times' revisions");
            helper.assertTrue(first.duties() != null, "the times are not sent to a client that has none");
            helper.assertTrue(first.content() != null && first.content().json().equals(json) && !first.content().tooLarge(),
                "the timetable is not sent to a client that has none: " + first.content());
            helper.assertTrue(first.content().axes().size() == 1 && first.content().axes().getFirst().line().equals("loop"),
                "the axes sent are " + first.content().axes());
            Set<DutyTimes.Status> statuses = first.duties().stream().map(DutyTimes::status).collect(Collectors.toSet());
            helper.assertTrue(statuses.equals(Set.of(DutyTimes.Status.RUNNING, DutyTimes.Status.FINISHED)),
                "the duties sent are " + first.duties());
            helper.assertTrue(first.duties().stream().anyMatch(times -> times.train().equals("Old set")
                    && times.departures().equals(done.departures())), "the finished duty is not sent as it was kept: " + first.duties());

            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            DiagramSnapshotPayload.STREAM_CODEC.encode(buf, first);
            DiagramSnapshotPayload read = DiagramSnapshotPayload.STREAM_CODEC.decode(buf);
            helper.assertTrue(read.equals(first) && buf.readableBytes() == 0, "the snapshot does not read back the same: " + read);

            DiagramSnapshotPayload again = DiagramService.snapshot(server, data.revision(), -1);
            helper.assertTrue(again.content() == null && first.duties().equals(again.duties()),
                "a client that has the timetable is sent it again, or not the times: " + again);
            DiagramSnapshotPayload same = DiagramService.snapshot(server, data.revision(), data.timesRevision());
            helper.assertTrue(same.content() == null && same.duties() == null, "a client that has everything is sent some again: " + same);
            int timesBefore = data.timesRevision();
            data.keep(DutyRun.start("1A", RealClock.day(now), data.calls("1A"), now), "", DutyTimes.Status.GIVEN_UP, now);
            DiagramSnapshotPayload changed = DiagramService.snapshot(server, data.revision(), timesBefore);
            helper.assertTrue(changed.duties() != null && changed.duties().size() == 3,
                "after a duty is given up, a client is not sent the times again: " + changed.duties());
            FriendlyByteBuf small = new FriendlyByteBuf(Unpooled.buffer());
            DiagramSnapshotPayload.STREAM_CODEC.encode(small, same);
            helper.assertTrue(DiagramSnapshotPayload.STREAM_CODEC.decode(small).equals(same), "a snapshot with nothing new does not read back the same");
            // A duty whose set was held at A from its first call there to its second, leaving out the call at B,
            // and was seen to run through B on its way to A.
            DutyTimes held = new DutyTimes("1A", RealClock.day(now), "Held set", DutyTimes.Status.RUNNING,
                List.of(DutyRun.NOT_YET, now - 200, DutyRun.NOT_YET, DutyRun.NOT_YET), List.of(now - 400, DutyRun.NOT_YET, DutyRun.NOT_YET, DutyRun.NOT_YET),
                List.of(2), List.of(new DutyRun.Pass(0, "B", now - 300)));
            DiagramSnapshotPayload withHeld = new DiagramSnapshotPayload(same.revision(), null, same.timesRevision(), List.of(held), now);
            FriendlyByteBuf heldBuf = new FriendlyByteBuf(Unpooled.buffer());
            DiagramSnapshotPayload.STREAM_CODEC.encode(heldBuf, withHeld);
            helper.assertTrue(DiagramSnapshotPayload.STREAM_CODEC.decode(heldBuf).equals(withHeld) && heldBuf.readableBytes() == 0,
                "the calls a held duty leaves out, or the stations it ran through, do not read back the same");
            int before = data.revision();
            data.forgetAxes();
            helper.assertTrue(data.revision() != before && DiagramService.snapshot(server, before, -1).content() != null,
                "after the axes are worked out again, a client is not sent them");
            helper.succeed();
        } finally {
            data.unloadTimetable(ClockService.now(server));
        }
    }

    private static void near(GameTestHelper helper, double expected, double actual, String what) {
        helper.assertTrue(Math.abs(expected - actual) < DISTANCE_TOLERANCE, what + " is " + actual + " blocks down, not " + expected);
    }
}
