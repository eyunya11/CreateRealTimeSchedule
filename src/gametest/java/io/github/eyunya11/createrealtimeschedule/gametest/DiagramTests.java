package io.github.eyunya11.createrealtimeschedule.gametest;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.clock.ClockService;
import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.diagram.DiagramService;
import io.github.eyunya11.createrealtimeschedule.diagram.DutyTimes;
import io.github.eyunya11.createrealtimeschedule.diagram.LineAxis;
import io.github.eyunya11.createrealtimeschedule.network.DiagramSnapshotPayload;
import io.github.eyunya11.createrealtimeschedule.operation.DutyRun;
import io.github.eyunya11.createrealtimeschedule.operation.OperationData;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable;
import io.github.eyunya11.createrealtimeschedule.timetable.TimetableJson;
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
            data.keep(done, "Old set", OperationData.Ending.FINISHED, now);

            DiagramSnapshotPayload first = DiagramService.snapshot(server, -1);
            helper.assertTrue(first.revision() == data.revision(), "the snapshot is not of the timetable's revision");
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

            DiagramSnapshotPayload again = DiagramService.snapshot(server, data.revision());
            helper.assertTrue(again.content() == null && again.duties().equals(first.duties()),
                "a client that has the timetable is sent it again, or not the times: " + again);
            int before = data.revision();
            data.forgetAxes();
            helper.assertTrue(data.revision() != before && DiagramService.snapshot(server, before).content() != null,
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
