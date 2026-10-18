package com.caseware.templateupdates.fanout.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(5, Duration.ofSeconds(30), Duration.ofMinutes(4));

    @Test
    void growsExponentiallyUpToTheCap() {
        assertEquals(Duration.ofSeconds(30), policy.backoffAfter(1, 1.0));
        assertEquals(Duration.ofSeconds(60), policy.backoffAfter(2, 1.0));
        assertEquals(Duration.ofSeconds(120), policy.backoffAfter(3, 1.0));
        assertEquals(Duration.ofMinutes(4), policy.backoffAfter(4, 1.0));
        assertEquals(Duration.ofMinutes(4), policy.backoffAfter(60, 1.0));
    }

    @Test
    void jitterStaysWithinTheUpperHalfOfTheWindow() {
        assertEquals(Duration.ofSeconds(15), policy.backoffAfter(1, 0.0));
        assertEquals(Duration.ofMillis(22_500), policy.backoffAfter(1, 0.5));
        assertThrows(IllegalArgumentException.class, () -> policy.backoffAfter(1, 1.5));
    }

    @Test
    void exhaustionIsAfterTheLastAllowedAttempt() {
        assertFalse(policy.isExhaustedAfter(4));
        assertTrue(policy.isExhaustedAfter(5));
    }
}
