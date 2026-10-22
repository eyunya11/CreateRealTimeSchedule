package io.github.eyunya11.createrealtimeschedule.gametest;

import java.util.List;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;

import io.github.eyunya11.createrealtimeschedule.railway.CreateNetwork;
import io.github.eyunya11.createrealtimeschedule.railway.Leg;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * A train on a {@link TrackLine} given a schedule to a station of the line that it has no way to: the
 * export must have no leg from the first station to it, and the train must not set off, as Create
 * finds no path for it. Stepped once a tick by a game test.
 */
final class NoWayTrip {

    /** How long a train that has no way is watched, in ticks. */
    private static final int WATCH_TICKS = 100;

    private final GameTestHelper helper;
    private final TrackLine line;
    private final int destination;
    private Train train;
    private int watched;

    /** @param destination the index of the station the train is sent to */
    NoWayTrip(GameTestHelper helper, TrackLine line, int destination) {
        this.helper = helper;
        this.line = line;
        this.destination = destination;
    }

    void begin() {
        line.cleanUpWhenTheTestEnds();
        helper.onEachTick(this::tick);
    }

    private void tick() {
        if (train == null) {
            if (!line.setUp())
                return;
            train = line.train();
            String leg = line.station(0).name + " to " + line.station(destination).name;
            List<Leg> network = CreateNetwork.read(Create.RAILWAYS.trackNetworks.values()).legs();
            List<String> legs = network.stream().map(l -> l.from() + " to " + l.to()).toList();
            helper.assertTrue(!legs.contains(leg), "the export has a leg from " + leg + ", where Create finds no way: " + legs);
            train.runtime.setSchedule(ScheduleTests.schedule(ScheduleTests.stop(line.station(destination).name)), false);
            return;
        }
        helper.assertTrue(train.speed == 0 && train.navigation.destination == null,
            "the train set off for " + line.station(destination).name + ", which the export has no way to (" + line.describe() + ")");
        if (++watched >= WATCH_TICKS)
            helper.succeed();
    }
}
