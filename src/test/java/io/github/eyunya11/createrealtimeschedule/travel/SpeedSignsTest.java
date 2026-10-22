package io.github.eyunya11.createrealtimeschedule.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

class SpeedSignsTest {

    private static final SpeedSigns ROUTE = new SpeedSigns(OptionalInt.of(60),
        List.of(SpeedSign.release(700), SpeedSign.limit(400, 40), SpeedSign.limit(900, 20)));

    @Test
    void theSignsAreKeptInTheOrderOfTheRoute() {
        assertEquals(List.of(400.0, 700.0, 900.0), ROUTE.signs().stream().map(SpeedSign::position).toList());
    }

    @Test
    void theLimitAfterAPlaceIsThatOfTheLastSignPassed() {
        assertEquals(OptionalInt.of(60), ROUTE.limitAfter(0));
        assertEquals(OptionalInt.of(60), ROUTE.limitAfter(399.9));
        // A sign is passed once the front is on it.
        assertEquals(OptionalInt.of(40), ROUTE.limitAfter(400));
        assertEquals(OptionalInt.empty(), ROUTE.limitAfter(700));
        assertEquals(OptionalInt.of(20), ROUTE.limitAfter(1000));
    }

    @Test
    void aSignAtOrBehindTheStartLeavesTheLimitTheTripStartedUnder() {
        SpeedSigns signs = new SpeedSigns(OptionalInt.empty(), List.of(SpeedSign.limit(0, 40), SpeedSign.limit(-3, 20)));
        assertEquals(OptionalInt.empty(), signs.limitAfter(10));
    }
}
