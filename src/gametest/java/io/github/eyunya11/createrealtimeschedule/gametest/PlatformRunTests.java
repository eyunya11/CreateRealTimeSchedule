package io.github.eyunya11.createrealtimeschedule.gametest;

import java.util.List;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.station.GlobalStation;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.clock.ClockService;
import io.github.eyunya11.createrealtimeschedule.clock.RealClock;
import io.github.eyunya11.createrealtimeschedule.gametest.TrackLine.Turn;
import io.github.eyunya11.createrealtimeschedule.operation.Call;
import io.github.eyunya11.createrealtimeschedule.operation.DutyRun;
import io.github.eyunya11.createrealtimeschedule.operation.TrainDriver;
import io.github.eyunya11.createrealtimeschedule.operation.TrainDriver.Outcome;
import io.github.eyunya11.createrealtimeschedule.timetable.ServiceTime;
import io.github.eyunya11.createrealtimeschedule.timetable.Timetable.Stop;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A station with two platforms of the same name, on real track, with
 * {@code ./gradlew runGameTestServer}: the operation sends a train to a station by its name, and the
 * train goes to the platform Create finds the best path to, which keeps away from one with another
 * train at it ({@code Train.Penalties.STATION_WITH_TRAIN}).
 *
 * <p>The line runs east from the depot, and at x = 60 a branch turns off it to the south, as in
 * {@link JunctionRunTests}. Both ways end at a platform named A: one straight on, 104 blocks from the
 * depot, and one on the branch, about 110. A run of {@link DutyRun} from the depot to A is driven by
 * {@link TrainDriver}, as the operation drives one.
 *
 * <p>Each test has a batch of its own (see {@link TrackLine}).
 */
@GameTestHolder(Createrealtimeschedule.MODID)
@PrefixGameTestTemplate(false)
public class PlatformRunTests {

    /** 116 blocks along x and 60 along z. */
    private static final String TURN = "track_turn";
    private static final int TIMEOUT_TICKS = 1500;
    private static final int DEPOT_X = 6;
    private static final int STRAIGHT_X = 110;
    private static final int LAST_X = 115;
    private static final Turn BRANCH = new Turn(60, 72, 14, 55);
    private static final int BRANCH_Z = 51;
    /** The indices of the stations: the depot, and the two platforms of A. */
    private static final int DEPOT = 0;
    private static final int STRAIGHT = 1;
    private static final int ON_THE_BRANCH = 2;
    private static final long START = TestTimes.clock(10, 12, 0, 0);

    /** With both platforms free, the train takes the nearer, straight on: what the next test changes is only the train at it. */
    @GameTest(template = TURN, batch = "realtimeplatform_both_free", timeoutTicks = TIMEOUT_TICKS)
    public static void withBothPlatformsFreeTheTrainTakesTheNearer(GameTestHelper helper) {
        TrackLine line = station(helper);
        new PlatformTrip(helper, line, STRAIGHT).begin();
    }

    /** A train stands at the platform straight on: the train sent to A goes onto the branch, to the free one. */
    @GameTest(template = TURN, batch = "realtimeplatform_one_taken", timeoutTicks = TIMEOUT_TICKS)
    public static void aTrainSentToAStationGoesToItsFreePlatform(GameTestHelper helper) {
        TrackLine line = station(helper).parkATrainAt(STRAIGHT);
        new PlatformTrip(helper, line, ON_THE_BRANCH).begin();
    }

    private static TrackLine station(GameTestHelper helper) {
        return new TrackLine(helper, LAST_X, BRANCH,
            new BlockPos[] {at(DEPOT_X, TrackLine.TRACK_Z), at(STRAIGHT_X, TrackLine.TRACK_Z), at(BRANCH.toX(), BRANCH_Z)},
            new String[] {"Depot", "A", "A"});
    }

    private static BlockPos at(int x, int z) {
        return new BlockPos(x, TrackLine.TRACK_Y, z);
    }

    /** The run from the depot to A, driven once a tick until the train stands at A; then where it stands is checked. */
    private static final class PlatformTrip {
        private final GameTestHelper helper;
        private final TrackLine line;
        private final int expected;
        private boolean clockSet;
        private DutyRun run;

        PlatformTrip(GameTestHelper helper, TrackLine line, int expected) {
            this.helper = helper;
            this.line = line;
            this.expected = expected;
        }

        void begin() {
            line.cleanUpWhenTheTestEnds();
            helper.onEachTick(this::tick);
        }

        private void tick() {
            if (!line.setUp())
                return;
            if (!clockSet) {
                line.clockOnNextTick(START);
                clockSet = true;
                return;
            }
            long now = ClockService.now(helper.getLevel().getServer());
            Train train = line.train();
            if (run == null) {
                List<Call> calls = List.of(new Call("Depot", Stop.NONE, ServiceTime.of("12:00:02"), "", "1"),
                    new Call("A", ServiceTime.of("12:00:40"), Stop.NONE, "1", ""));
                run = DutyRun.start("P", RealClock.day(now), calls, now);
            }
            Outcome outcome = TrainDriver.step(run, train, now, 0);
            if (outcome == Outcome.NO_WAY)
                helper.fail("no way to A (" + line.describe() + ")");
            if (outcome != Outcome.FINISHED)
                return;
            GlobalStation at = train.getCurrentStation();
            String where = at == line.station(STRAIGHT) ? "the platform straight on" : at == line.station(ON_THE_BRANCH) ? "the platform on the branch" : "neither";
            helper.assertTrue(at == line.station(expected), "the train stands at " + where + " (" + line.describe() + ")");
            if (expected == ON_THE_BRANCH)
                helper.assertTrue(line.station(STRAIGHT).getPresentTrain() == line.parked(0), "the parked train is not at the platform straight on any more");
            helper.succeed();
        }
    }
}
