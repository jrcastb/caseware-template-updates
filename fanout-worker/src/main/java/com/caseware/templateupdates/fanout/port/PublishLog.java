package com.caseware.templateupdates.fanout.port;

import java.time.Instant;
import java.util.Optional;

/**
 * Durable record of how far planning for each publish has progressed. Makes planning idempotent under
 * duplicate delivery and resumable after a crash.
 *
 * <p>Two planners may race on the same publish (duplicate delivery to two instances). That is safe
 * because enqueueing is idempotent; it only costs some duplicate target resolution.
 */
public interface PublishLog {

    enum Status { IN_PROGRESS, PLANNED }

    /** @param cursor resume point for target resolution, or empty to start from the beginning */
    record Progress(String publishId, Status status, Optional<String> cursor) {}

    /** Atomically creates an IN_PROGRESS record if absent; returns the current record either way. */
    Progress begin(String publishId, Instant now);

    /** Records that every target before {@code cursor} has been enqueued. */
    void checkpoint(String publishId, String cursor);

    void markPlanned(String publishId, Instant now);
}
