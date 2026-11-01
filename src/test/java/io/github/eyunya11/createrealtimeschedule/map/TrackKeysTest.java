package io.github.eyunya11.createrealtimeschedule.map;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TrackKeysTest {

    @Test
    void aKeyGivesTheEndsInBlocksTheLowerFirst() {
        String key = TrackKeys.of("minecraft:overworld", new int[] {93, 4, 7}, new int[] {13, 4, 7});
        assertEquals("minecraft:overworld 6.5,2,3.5 46.5,2,3.5", key);
    }

    @Test
    void bothWaysOfATrackHaveOneKey() {
        int[] a = {0, 4, 10};
        int[] b = {0, 4, -10};
        assertEquals(TrackKeys.of("minecraft:the_nether", a, b), TrackKeys.of("minecraft:the_nether", b, a));
    }

    @Test
    void halfBlocksBelowZeroKeepTheirSign() {
        assertEquals("d -1.5,-1,0 -0.5,0,1", TrackKeys.of("d", new int[] {-1, 0, 2}, new int[] {-3, -2, 0}));
    }
}
