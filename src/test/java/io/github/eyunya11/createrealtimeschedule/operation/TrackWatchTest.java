package io.github.eyunya11.createrealtimeschedule.operation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TrackWatchTest {

    private static final int SETTLE = 100;

    @Test
    void theTrackAsItWasNeedsNothing() {
        TrackWatch watch = new TrackWatch(SETTLE);
        watch.workedOut(7);
        assertFalse(watch.settledOnAChange(7, 0));
        assertFalse(watch.settledOnAChange(7, 10_000));
    }

    @Test
    void aChangeIsWorkedOutOnceTheTrackHasStayedAsItIsForTheSettlingTime() {
        TrackWatch watch = new TrackWatch(SETTLE);
        watch.workedOut(7);
        assertFalse(watch.settledOnAChange(8, 1_000), "just changed");
        assertFalse(watch.settledOnAChange(8, 1_000 + SETTLE - 1));
        assertTrue(watch.settledOnAChange(8, 1_000 + SETTLE));
        watch.workedOut(8);
        assertFalse(watch.settledOnAChange(8, 1_000 + SETTLE + 1), "worked out again from the track as it is now");
    }

    @Test
    void notWhileThePlayerIsStillLayingTrack() {
        TrackWatch watch = new TrackWatch(SETTLE);
        watch.workedOut(7);
        assertFalse(watch.settledOnAChange(8, 1_000));
        assertFalse(watch.settledOnAChange(9, 1_060), "changed again: it settles from now");
        assertFalse(watch.settledOnAChange(9, 1_120));
        assertTrue(watch.settledOnAChange(9, 1_160));
    }

    @Test
    void aTrackPutBackAsItWasNeedsNothing() {
        TrackWatch watch = new TrackWatch(SETTLE);
        watch.workedOut(7);
        assertFalse(watch.settledOnAChange(8, 1_000));
        assertFalse(watch.settledOnAChange(7, 1_020));
        assertFalse(watch.settledOnAChange(7, 1_020 + SETTLE * 3));
    }
}
