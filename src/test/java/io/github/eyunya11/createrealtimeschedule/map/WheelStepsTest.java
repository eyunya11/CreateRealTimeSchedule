package io.github.eyunya11.createrealtimeschedule.map;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WheelStepsTest {

    @Test
    void threeNotchesMakeAStepEitherWay() {
        WheelSteps wheel = new WheelSteps();
        assertEquals(0, wheel.turn(1));
        assertEquals(0, wheel.turn(1));
        assertEquals(1, wheel.turn(1), "the third notch away steps in");
        assertEquals(0, wheel.turn(1));
        assertEquals(0, wheel.turn(1));
        assertEquals(1, wheel.turn(1));
        assertEquals(2, wheel.turn(6), "a turn of six notches at once is two steps");
    }

    @Test
    void turningBackStartsTheCountAgain() {
        WheelSteps wheel = new WheelSteps();
        wheel.turn(2);
        assertEquals(0, wheel.turn(-1), "the two notches away are not taken off the way back");
        assertEquals(0, wheel.turn(-1));
        assertEquals(-1, wheel.turn(-1), "three notches back step out");
        assertEquals(0, wheel.turn(0), "no turn keeps the count");
        assertEquals(0, wheel.turn(-2));
        assertEquals(-1, wheel.turn(-1));
    }

    @Test
    void partsOfANotchAddUp() {
        WheelSteps wheel = new WheelSteps();
        int steps = 0;
        for (int part = 0; part < 12; part++)
            steps += wheel.turn(0.25);
        assertEquals(1, steps, "a touchpad's twelve quarter notches are three notches");
    }
}
