package com.caseware.templateupdates.fanout.app;

import java.time.Duration;
import java.util.Objects;

/**
 * Exponential backoff with a cap and "equal jitter" (half fixed, half random). Jitter matters here: a
 * single publish can fail hundreds of reads at once when the downstream degrades, and unjittered retries
 * would come back as a synchronised wave against a team whose capacity is already the bottleneck.
 *
 * @param maxAttempts total leases an item may consume (including ones lost to worker crashes) before it
 *                    is dead-lettered
 */
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {

    public RetryPolicy {
        Objects.requireNonNull(baseDelay, "baseDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (baseDelay.isNegative() || baseDelay.isZero()) {
            throw new IllegalArgumentException("baseDelay must be positive");
        }
        if (maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must be >= baseDelay");
        }
    }

    /** True when the attempt that just failed was the last one allowed. */
    public boolean isExhaustedAfter(int failedAttempt) {
        return failedAttempt >= maxAttempts;
    }

    /**
     * Delay before the next attempt after {@code failedAttempt} (1-based) failed.
     *
     * @param jitter01 a uniformly distributed value in [0, 1]; injected so tests are deterministic
     */
    public Duration backoffAfter(int failedAttempt, double jitter01) {
        if (failedAttempt < 1) {
            throw new IllegalArgumentException("failedAttempt is 1-based");
        }
        if (jitter01 < 0.0 || jitter01 > 1.0) {
            throw new IllegalArgumentException("jitter must be in [0, 1]");
        }
        long capMillis = maxDelay.toMillis();
        int doublings = Math.min(failedAttempt - 1, 30);
        long exponential = baseDelay.toMillis() << doublings;
        long capped = (exponential <= 0 || exponential > capMillis) ? capMillis : exponential;
        long fixedHalf = capped / 2;
        return Duration.ofMillis(fixedHalf + Math.round((capped - fixedHalf) * jitter01));
    }
}
