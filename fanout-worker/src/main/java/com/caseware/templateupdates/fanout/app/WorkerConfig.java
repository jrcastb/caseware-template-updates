package com.caseware.templateupdates.fanout.app;

import java.time.Duration;
import java.util.Objects;

/**
 * @param lanes         virtual threads that pull work. Should be at least the capacity grant so the grant
 *                      can be used; the {@code CapacityLimiter}, not this number, bounds downstream load.
 * @param leaseDuration must exceed {@code callTimeout}: a lease covers the freshness check, the call and
 *                      recording the outcome. If it could expire mid-call, a second worker would start a
 *                      duplicate one-minute load. Production values: call timeout 3 min, lease 5 min.
 * @param timeoutGrace  after a call times out the downstream may still be loading the file, so its permit
 *                      stays occupied until the abandoned call returns or this grace elapses, whichever is
 *                      first. Production value: 2 min. Zero releases the permit at the timeout.
 */
public record WorkerConfig(
        String workerId, int lanes, Duration leaseDuration, Duration callTimeout, Duration timeoutGrace,
        RetryPolicy retry) {

    public WorkerConfig {
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        Objects.requireNonNull(callTimeout, "callTimeout");
        Objects.requireNonNull(timeoutGrace, "timeoutGrace");
        Objects.requireNonNull(retry, "retry");
        if (lanes < 1) {
            throw new IllegalArgumentException("lanes must be >= 1");
        }
        if (callTimeout.isNegative() || callTimeout.isZero()) {
            throw new IllegalArgumentException("callTimeout must be positive");
        }
        if (timeoutGrace.isNegative()) {
            throw new IllegalArgumentException("timeoutGrace must not be negative");
        }
        if (leaseDuration.compareTo(callTimeout) <= 0) {
            throw new IllegalArgumentException(
                    "leaseDuration (" + leaseDuration + ") must exceed callTimeout (" + callTimeout + ")");
        }
    }
}
