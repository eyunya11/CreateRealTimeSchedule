package io.github.eyunya11.createrealtimeschedule.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The ticks of the first test are what real trains took in the game tests
 * ({@code TravelTimeRunTests}). The values for zones and for the other runs are this model's own
 * output, and they agree with {@code tools/travel_time_replica.py}, a second replica written apart
 * from it that follows Create's code more closely; neither is a real train, so only straight runs have
 * been compared with one.
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
    void aZoneAboveTheTrainsSpeedChangesNothing() {
        assertEquals(ticks(0.25, 100, NO_ZONES), ticks(0.25, 100, List.of(new SpeedZone(20, 60, 20))));
        assertEquals(ticks(1, 100, NO_ZONES), ticks(1, 100, List.of(new SpeedZone(20, 60, 100))));
    }

    @Test
    void aCurveHoldsTheTrainToTheSpecsTurnSpeed() {
        SpeedZone curve = SpeedZone.curve(400, 700, STANDARD);
        assertEquals(STANDARD.turnSpeed(), curve.cap());
        // At full throttle the 14 blocks a second of a curve is half the top speed, so a 300-block curve costs time.
        assertTrue(ticks(1, 1000, List.of(curve)) > ticks(1, 1000, NO_ZONES) + 100);
        assertEquals(ticks(1, 1000, List.of(new SpeedZone(400, 700, 14))), ticks(1, 1000, List.of(curve)));
        // A train held to 7 blocks a second by its throttle goes round at 7, as in the game.
        assertEquals(ticks(0.25, 1000, NO_ZONES), ticks(0.25, 1000, List.of(curve)));
        // A spec with another turn speed gives another curve.
        TrainSpec slowTurner = new TrainSpec(28, 7, 3);
        assertTrue(TravelTime.ticks(slowTurner, 1, 1000, List.of(SpeedZone.curve(400, 700, slowTurner))).orElseThrow()
            > ticks(1, 1000, List.of(curve)));
    }

    @Test
    void aPoweredTrainIsFasterThanAnUnpoweredOne() {
        double unpowered = TravelTime.seconds(TrainSpec.STANDARD, 1, 1000, NO_ZONES).orElseThrow();
        double powered = TravelTime.seconds(TrainSpec.POWERED, 1, 1000, NO_ZONES).orElseThrow();
        assertTrue(powered < unpowered - 5, powered + " against " + unpowered);
    }

    @Test
    void aLongerRunTakesLongerExceptForTheJitterOfCreatesControlLaw() {
        // Create's braking rule flips on and off from tick to tick near the stop, so runs a block apart
        // differ in either direction: 130, 131 and 132 blocks take 252, 248 and 254 ticks. Over 5 to
        // 3,000 blocks at full throttle the largest fall from one block to the next is 9 ticks (at 215
        // blocks), the largest rise is 9 (at 263), and the largest drop below the longest run so far is
        // 10 (at 265). That is as well as any estimate can do, however good the model.
        int previous = 0;
        int previousTicks = -1;
        for (int distance = 5; distance <= 3000; distance += 1) {
            int ticks = ticks(1, distance, NO_ZONES);
            assertTrue(ticks >= previous - 10, "ticks fell by more than the jitter at " + distance);
            if (previousTicks >= 0)
                assertTrue(Math.abs(ticks - previousTicks) <= 9, "a block more or less changed the ticks by more than 9 at " + distance);
            previous = Math.max(previous, ticks);
            previousTicks = ticks;
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

    @Test
    void aCapTooLowToBeASpeedIsAStop() {
        // These are refused before the loop runs (it would run a whole day of ticks to say the same).
        assertFalse(TravelTime.ticks(STANDARD, 1, 1000, List.of(new SpeedZone(0, 1000, 1e-9))).isPresent());
        assertFalse(TravelTime.ticks(STANDARD, 1, 1000, List.of(new SpeedZone(0, 1000, TravelTime.MIN_CAP / 2))).isPresent());
        // The lowest cap that counts is a speed, however slow, as long as the stretch can be crossed.
        assertTrue(TravelTime.ticks(STANDARD, 1, 100, List.of(new SpeedZone(0, 10, TravelTime.MIN_CAP))).isPresent());
        // A stretch that cannot be crossed within a day is refused even above the floor.
        assertFalse(TravelTime.ticks(STANDARD, 1, 5000, List.of(new SpeedZone(0, 5000, TravelTime.MIN_CAP))).isPresent());
    }
}
