package io.github.eyunya11.createrealtimeschedule.diagram;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class RequestLimitTest {

    private static final UUID ONE = new UUID(1, 1);
    private static final UUID TWO = new UUID(2, 2);

    @Test
    void aPlayerIsAnsweredOnceInTheGap() {
        RequestLimit limit = new RequestLimit(10);
        assertTrue(limit.allow(ONE, 100));
        assertFalse(limit.allow(ONE, 101));
        assertFalse(limit.allow(ONE, 109));
        assertTrue(limit.allow(ONE, 110));
        assertTrue(limit.allow(TWO, 111), "another player is counted apart");
        assertFalse(limit.allow(ONE, 119));
    }

    @Test
    void aTickBeforeTheLastAnswerOrAForgottenPlayerStartsAgain() {
        RequestLimit limit = new RequestLimit(10);
        assertTrue(limit.allow(ONE, 500));
        assertTrue(limit.allow(ONE, 3), "a new world counts its ticks from the start");
        limit.forget(ONE);
        assertTrue(limit.allow(ONE, 4));
    }
}
