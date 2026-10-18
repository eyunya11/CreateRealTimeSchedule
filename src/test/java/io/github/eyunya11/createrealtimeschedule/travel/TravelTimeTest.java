package io.github.eyunya11.createrealtimeschedule.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The ticks of the first test are what real trains took in the game tests
 * ({@code TravelTimeTests}); the other expected values are this model's own output, kept so that a
 * change to it is noticed. Only straight runs have been compared with a real train.
 */
class TravelTimeTest {

    private static final TrainSpec STANDARD = TrainSpec.STANDARD;
    private static final List<SpeedZone> NO_ZONES = List.of();

    private static int ticks(double throttle, double distance, List<SpeedZone> zones) {
        return TravelTime.ticks(STANDARD, throttle, distance, zones).orElseThrow();
    }

    @Test
    void theModelGivesTheTicksRealTrainsTook() {
        // From the game tests: a train built by Create, on Create's track, from one station to the next.
        assertEquals(96, ticks(1.0, 20, NO_ZONES));
        assertEquals(133, ticks(1.0, 40, NO_ZONES));
        assertEquals(154, ticks(0.25, 40, NO_ZONES));
        assertEquals(298, ticks(0.1, 40, NO_ZONES));
        assertEquals(382, ticks(1.0, 300, NO_ZONES));
    }

    @Test
    void otherStraightRunsAtFullThrottle() {
        assertEquals(168, ticks(1, 60, NO_ZONES));
        assertEquals(218, ticks(1, 100, NO_ZONES));
        assertEquals(358, ticks(1, 262, NO_ZONES));
        assertEquals(528, ticks(1, 500, NO_ZONES));
        assertEquals(882, ticks(1, 1000, NO_ZONES));
        assertEquals(3746, ticks(1, 5000, NO_ZONES));
    }

    @Test
    void aLowerThrottleStretchesTheRunOnceTheTrainReachesItsSpeed() {
        // A quarter throttle is 7 blocks a second, reached in 2.3 s after about 8 blocks.
        assertEquals(96, ticks(0.25, 20, NO_ZONES));
        assertEquals(154, ticks(0.25, 40, NO_ZONES));
        assertEquals(211, ticks(0.25, 60, NO_ZONES));
    }

    @Test
    void aRunLongEnoughToCruiseCostsTheDistanceOverTheSpeedPlusAboutEightAndAHalfSeconds() {
        // The rule of thumb in the proposal, corrected by the measurements: distance / 28 + 8.6 s for
        // 262 blocks and more. (It was 9.3 s before the model counted the stop the way Create does.)
        for (int distance : new int[] {262, 500, 1000, 5000}) {
            double seconds = TravelTime.seconds(STANDARD, 1, distance, NO_ZONES).orElseThrow();
            assertEquals(distance / 28.0 + 8.6, seconds, 0.3, distance + " blocks");
        }
    }

    @Test
    void speedLimitZonesSlowTheHeadDownAndTheTrainBrakesInTimeForThem() {
        // 40% of 28 blocks a second is 11.2, 20% is 5.6, 60% is 16.8.
        assertEquals(882, ticks(1, 1000, NO_ZONES));
        assertEquals(1278, ticks(1, 1000, List.of(new SpeedZone(400, 700, 11.2))));
        assertEquals(1863, ticks(1, 1000, List.of(new SpeedZone(400, 700, 5.6))));
        assertEquals(1852, ticks(1, 1000, List.of(new SpeedZone(0, 1000, 11.2))));
        assertEquals(1245, ticks(1, 1000, List.of(new SpeedZone(200, 300, 16.8), new SpeedZone(600, 800, 11.2))));
    }

    @Test
    void aZoneAbovetheTrainsSpeedChangesNothing() {
        assertEquals(ticks(0.25, 100, NO_ZONES), ticks(0.25, 100, List.of(new SpeedZone(20, 60, 20))));
        assertEquals(ticks(1, 100, NO_ZONES), ticks(1, 100, List.of(new SpeedZone(20, 60, 100))));
    }

    @Test
    void aPoweredTrainIsFasterThanAnUnpoweredOne() {
        double unpowered = TravelTime.seconds(TrainSpec.STANDARD, 1, 1000, NO_ZONES).orElseThrow();
        double powered = TravelTime.seconds(TrainSpec.POWERED, 1, 1000, NO_ZONES).orElseThrow();
        assertTrue(powered < unpowered - 5, powered + " against " + unpowered);
    }

    @Test
    void aLongerRunTakesLongerExceptForTheJitterOfCreatesControlLaw() {
        // Create's braking rule flips on and off from tick to tick near the stop, and runs one block
        // apart differ by up to half a second in either direction: 130 blocks take 263 ticks, 131
        // take 258, 132 take 265 (with the stop counted at its aimed point; the pattern is the same
        // now). The largest drop below the longest run so far, over 5 to 3,000 blocks, is about a
        // dozen ticks. That is as well as any estimate can do, however good the model.
        int previous = 0;
        for (int distance = 5; distance <= 400; distance += 1) {
            int ticks = ticks(1, distance, NO_ZONES);
            assertTrue(ticks >= previous - 12, "ticks fell by more than the jitter at " + distance);
            previous = Math.max(previous, ticks);
        }
    }

    @Test
    void aRunOfNoLengthTakesNoTime() {
        assertEquals(0, ticks(1, 0, NO_ZONES));
    }

    @Test
    void nothingIsAnsweredForAStopOrForNonsense() {
        // A cap of zero is a sign that stops the train: the trip has no time.
        assertFalse(TravelTime.ticks(STANDARD, 1, 100, List.of(new SpeedZone(10, 20, 0))).isPresent());
        assertFalse(TravelTime.ticks(STANDARD, 0, 100, NO_ZONES).isPresent());
        assertFalse(TravelTime.ticks(STANDARD, 1.5, 100, NO_ZONES).isPresent());
        assertFalse(TravelTime.ticks(STANDARD, 1, -1, NO_ZONES).isPresent());
        assertFalse(TravelTime.seconds(STANDARD, 1, 100, List.of(new SpeedZone(10, 20, 0))).isPresent());
    }
}
