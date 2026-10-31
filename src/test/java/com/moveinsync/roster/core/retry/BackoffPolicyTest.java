package com.moveinsync.roster.core.retry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffPolicyTest {

    private final BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(60));

    @Test
    void delayDoublesWithEachAttempt() {
        assertEquals(Duration.ofSeconds(1), policy.delayFor(1));
        assertEquals(Duration.ofSeconds(2), policy.delayFor(2));
        assertEquals(Duration.ofSeconds(4), policy.delayFor(3));
        assertEquals(Duration.ofSeconds(8), policy.delayFor(4));
        assertEquals(Duration.ofSeconds(32), policy.delayFor(6));
    }

    @Test
    void capHoldsOnceReached() {
        assertEquals(Duration.ofSeconds(60), policy.delayFor(7));
        assertEquals(Duration.ofSeconds(60), policy.delayFor(8));
    }

    @Test
    void capHoldsWhereTheExponentWouldOverflow() {
        assertEquals(Duration.ofSeconds(60), policy.delayFor(63));
        assertEquals(Duration.ofSeconds(60), policy.delayFor(64));
        assertEquals(Duration.ofSeconds(60), policy.delayFor(65));
        assertEquals(Duration.ofSeconds(60), policy.delayFor(Integer.MAX_VALUE));
    }

    @Test
    void delayNeverExceedsACapThatIsNotAPowerOfTwoOfTheBase() {
        BackoffPolicy uneven = new BackoffPolicy(Duration.ofSeconds(3), Duration.ofSeconds(7));

        assertEquals(Duration.ofSeconds(3), uneven.delayFor(1));
        assertEquals(Duration.ofSeconds(6), uneven.delayFor(2));
        assertEquals(Duration.ofSeconds(7), uneven.delayFor(3));
    }

    @Test
    void isDeterministic() {
        assertEquals(policy.delayFor(5), policy.delayFor(5));
    }

    @Test
    void rejectsAttemptsBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> policy.delayFor(0));
    }

    @Test
    void rejectsAnInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new BackoffPolicy(Duration.ZERO, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new BackoffPolicy(Duration.ofSeconds(2), Duration.ofSeconds(1)));
    }
}
