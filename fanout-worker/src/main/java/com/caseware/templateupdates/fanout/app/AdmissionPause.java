package com.caseware.templateupdates.fanout.app;

import java.time.Instant;
import java.util.Optional;

/**
 * Shared by every lane of a worker: once the downstream throttles any call, no lane starts a new call
 * until the Retry-After instant. Extending is monotonic, so a shorter Retry-After never cuts a longer
 * pause short.
 */
final class AdmissionPause {

    private Instant until = Instant.MIN;

    synchronized void pauseUntil(Instant instant) {
        if (instant.isAfter(until)) {
            until = instant;
        }
    }

    /** The end of the pause, if admission is paused at {@code now}. */
    synchronized Optional<Instant> activeAt(Instant now) {
        return now.isBefore(until) ? Optional.of(until) : Optional.empty();
    }
}
