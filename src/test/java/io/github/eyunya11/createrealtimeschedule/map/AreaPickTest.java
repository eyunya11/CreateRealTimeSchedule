package io.github.eyunya11.createrealtimeschedule.map;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AreaPickTest {

    private static AreaPick pick() {
        return new AreaPick(new MapProjection(100, 50, 16, 3, 2));
    }

    @Test
    void aDragSnapsTheMiddleToWholeBlocksButKeepsWhatIsLessThanABlock() {
        AreaPick pick = pick();
        pick.press();
        for (int move = 0; move < 10; move++)
            pick.drag(0.01, -0.005, false);
        // A tenth of a block of the screen east and a twentieth north: 1.6 and 0.8 blocks of the world.
        assertEquals(new MapProjection(102, 49, 16, 3, 2), pick.shown());
        pick.drag(0.01, 0, true);
        assertEquals(101.76, pick.shown().centreX(), 1e-9, "held fine, the middle is not snapped");
        assertEquals(49.2, pick.shown().centreZ(), 1e-9);
    }

    @Test
    void theWheelStepsTheScaleEveryThreeNotchesOrALittleHeldFine() {
        AreaPick pick = pick();
        pick.wheel(1, 0, 0, false);
        pick.wheel(1, 0, 0, false);
        assertEquals(16, pick.shown().scale(), "two notches are not yet a step");
        pick.wheel(1, 0, 0, false);
        assertEquals(12, pick.shown().scale());
        pick.wheel(-1, 0, 0, true);
        assertEquals(12 * MapProjection.WHEEL, pick.shown().scale(), 1e-9);
    }

    @Test
    void aDragGoesOnFromWhatTheWheelZoomedToInIt() {
        AreaPick pick = pick();
        pick.press();
        pick.drag(0.5, 0, false);
        pick.wheel(3, 0, 0, false);
        assertEquals(12, pick.shown().scale());
        pick.drag(0.5, 0, false);
        assertEquals(12, pick.shown().scale(), "the drag keeps the scale zoomed to while it went on");
        assertEquals(108 + 6, pick.shown().centreX(), 1e-9, "half a block more, at the new scale");
        pick.wheel(-1, 0, 0, true);
        double fine = pick.shown().scale();
        pick.drag(0.25, 0, true);
        assertEquals(fine, pick.shown().scale(), 1e-12);
    }
}
