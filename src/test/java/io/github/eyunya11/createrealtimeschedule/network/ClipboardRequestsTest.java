package io.github.eyunya11.createrealtimeschedule.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

class ClipboardRequestsTest {

    private static ClipboardRequests requests() {
        return new ClipboardRequests(200, new Random(42));
    }

    @Test
    void anAnswerWithTheTokenInTimeIsTakenOnce() {
        ClipboardRequests requests = requests();
        long token = requests.ask(1000);
        assertTrue(requests.take(token, 1200));
        assertFalse(requests.take(token, 1200), "the same answer is taken twice");
    }

    @Test
    void anAnswerNotAskedForOrWithAnotherTokenIsNotTaken() {
        ClipboardRequests requests = requests();
        assertFalse(requests.take(0, 1000), "an answer to nothing asked is taken");
        long token = requests.ask(1000);
        assertFalse(requests.take(token + 1, 1000));
        assertTrue(requests.take(token, 1000), "a wrong token threw away the request");
    }

    @Test
    void anAnswerTooLateIsNotTakenAndTheRequestIsForgotten() {
        ClipboardRequests requests = requests();
        long token = requests.ask(1000);
        assertFalse(requests.take(token, 1201));
        assertFalse(requests.take(token, 1100), "a request that ran out of time is still there");
    }

    @Test
    void aNewRequestTakesThePlaceOfTheOldOne() {
        ClipboardRequests requests = requests();
        long first = requests.ask(1000);
        long second = requests.ask(1010);
        assertNotEquals(first, second);
        assertFalse(requests.take(first, 1020));
        assertTrue(requests.take(second, 1020));
    }

    @Test
    void aTimeBeforeTheRequestIsTooLateToo() {
        // A clock that has gone back, as one that counts from the start of a world opened since.
        ClipboardRequests requests = requests();
        long token = requests.ask(1000);
        assertFalse(requests.take(token, 5));
    }
}
