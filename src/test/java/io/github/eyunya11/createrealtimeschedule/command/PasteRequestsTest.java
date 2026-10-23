package io.github.eyunya11.createrealtimeschedule.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PasteRequestsTest {

    private static final UUID ONE = new UUID(1, 1);
    private static final UUID TWO = new UUID(2, 2);

    private static PasteRequests requests() {
        return new PasteRequests(200, new Random(42));
    }

    @Test
    void aPasteWithTheTokenInTimeIsTakenOnce() {
        PasteRequests requests = requests();
        long token = requests.ask(ONE, 1000);
        assertTrue(requests.take(ONE, token, 1200));
        assertFalse(requests.take(ONE, token, 1200), "the same paste is taken twice");
    }

    @Test
    void aPasteNotAskedForOrFromAnotherPlayerIsNotTaken() {
        PasteRequests requests = requests();
        assertFalse(requests.take(ONE, 0, 1000));
        long token = requests.ask(ONE, 1000);
        assertFalse(requests.take(TWO, token, 1000), "another player's paste is taken with the first player's token");
        assertFalse(requests.take(ONE, token + 1, 1000));
        assertTrue(requests.take(ONE, token, 1000), "a wrong token threw away the request");
    }

    @Test
    void aPasteTooLateIsNotTakenAndTheRequestIsForgotten() {
        PasteRequests requests = requests();
        long token = requests.ask(ONE, 1000);
        assertFalse(requests.take(ONE, token, 1201));
        assertFalse(requests.take(ONE, token, 1100), "a request that ran out of time is still there");
    }

    @Test
    void aNewRequestTakesThePlaceOfTheOldOne() {
        PasteRequests requests = requests();
        long first = requests.ask(ONE, 1000);
        long second = requests.ask(ONE, 1010);
        assertNotEquals(first, second);
        assertFalse(requests.take(ONE, first, 1020));
        assertTrue(requests.take(ONE, second, 1020));
    }

    @Test
    void aTickBeforeTheRequestIsTooLateToo() {
        // The ticks count from the start again in a world opened since.
        PasteRequests requests = requests();
        long token = requests.ask(ONE, 1000);
        assertFalse(requests.take(ONE, token, 5));
    }
}
