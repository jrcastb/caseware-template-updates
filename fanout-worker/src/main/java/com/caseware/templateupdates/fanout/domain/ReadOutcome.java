package com.caseware.templateupdates.fanout.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Result of one call to the Engagement Service load operation. Sealed so that the worker's handling is
 * checked for exhaustiveness by the compiler: adding an outcome forces a decision about it.
 */
public sealed interface ReadOutcome {

    /** The engagement was loaded; {@code observedAt} is no earlier than the start of the call. */
    record Loaded(EngagementTemplateState state, Instant observedAt) implements ReadOutcome {
        public Loaded {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }

    /** The engagement no longer exists or is archived. Terminal and expected: not an error. */
    record Gone(String reason) implements ReadOutcome {}

    /** The downstream asked us to back off. Does not count against the item's retry budget. */
    record Throttled(Duration retryAfter) implements ReadOutcome {
        public Throttled {
            Objects.requireNonNull(retryAfter, "retryAfter");
            if (retryAfter.isNegative()) {
                throw new IllegalArgumentException("retryAfter must not be negative");
            }
        }
    }

    /** Timeouts, 5xx, connection errors, pod evictions: worth retrying with backoff. */
    record TransientFailure(String reason) implements ReadOutcome {}

    /** The request can never succeed as issued (e.g. corrupt engagement). Dead-lettered for a human. */
    record PermanentFailure(String reason) implements ReadOutcome {}
}
