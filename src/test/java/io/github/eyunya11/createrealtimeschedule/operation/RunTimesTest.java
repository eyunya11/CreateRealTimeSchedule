package io.github.eyunya11.createrealtimeschedule.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

class RunTimesTest {

    /** Running times between stations, as a map by "from-to". */
    private static RunTimes times(Map<String, Long> ticks) {
        return (from, to) -> {
            Long found = ticks.get(from + "-" + to);
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
}
