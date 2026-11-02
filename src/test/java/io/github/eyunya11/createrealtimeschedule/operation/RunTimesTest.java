package io.github.eyunya11.createrealtimeschedule.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

class RunTimesTest {

    /** Running times between stations, as a map by "from-to", or "from-to-tag" for a train held to a track tag. */
    private static RunTimes times(Map<String, Long> ticks) {
        return (from, to, tag) -> {
            Long found = ticks.get(from + "-" + to + (tag.isEmpty() ? "" : "-" + tag));
            return found == null ? OptionalLong.empty() : OptionalLong.of(found);
        };
    }

    @Test
    void theFastestIsTheLeastThatAnyKnows() {
        RunTimes powered = times(Map.of("A-B", 100L, "B-C", 300L));
        RunTimes unpowered = times(Map.of("A-B", 140L, "B-C", 250L, "C-D", 80L));
        RunTimes fastest = RunTimes.fastest(List.of(powered, unpowered));
        assertEquals(OptionalLong.of(100), fastest.ticks("A", "B"));
        assertEquals(OptionalLong.of(250), fastest.ticks("B", "C"), "whichever is the faster there");
        assertEquals(OptionalLong.of(80), fastest.ticks("C", "D"), "what only one knows");
        assertEquals(OptionalLong.empty(), fastest.ticks("D", "A"), "what none knows");
        assertEquals(OptionalLong.empty(), RunTimes.fastest(List.of()).ticks("A", "B"));
    }

    /** A train held to a track tag has the times of its tag, which may be none where a train of no tag has one. */
    @Test
    void theTimesOfATagAreTheirOwn() {
        RunTimes times = times(Map.of("A-B", 100L, "A-B-local", 120L));
        assertEquals(OptionalLong.of(100), times.ticks("A", "B"));
        assertEquals(OptionalLong.of(120), times.ticks("A", "B", "local"));
        assertEquals(OptionalLong.empty(), times.ticks("A", "B", "express"));
        RunTimes remembered = RunTimes.remembered(times);
        assertEquals(OptionalLong.of(100), remembered.ticks("A", "B"));
        assertEquals(OptionalLong.of(120), remembered.ticks("A", "B", "local"), "remembered by the tag too");
        RunTimes fastest = RunTimes.fastest(List.of(times, times(Map.of("A-B-local", 110L))));
        assertEquals(OptionalLong.of(110), fastest.ticks("A", "B", "local"));
        assertEquals(OptionalLong.of(100), fastest.ticks("A", "B"));
    }

    @Test
    void rememberedTimesAreWorkedOutOnceForEachTwoStations() {
        int[] asked = {0};
        RunTimes remembered = RunTimes.remembered((from, to, tag) -> {
            asked[0]++;
            return from.equals("A") ? OptionalLong.of(100) : OptionalLong.empty();
        });
        for (int k = 0; k < 3; k++) {
            assertEquals(OptionalLong.of(100), remembered.ticks("A", "B"));
            assertEquals(OptionalLong.empty(), remembered.ticks("B", "A"), "what is not known is remembered too");
        }
        assertEquals(2, asked[0]);
    }
}
