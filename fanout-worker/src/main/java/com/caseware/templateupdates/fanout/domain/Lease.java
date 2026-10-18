package com.caseware.templateupdates.fanout.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Exclusive, time-bounded right to process one work item.
 *
 * @param token     fencing token, strictly increasing per item. Every mutation of the item made with this
 *                  lease is rejected once the item has been leased again, so a worker that was paused or
 *                  presumed dead cannot overwrite the outcome recorded by its successor.
 * @param attempt   1-based count of times this item has been leased since it was created or last
 *                  succeeded, including leases that expired because a worker crashed.
 * @param leasedAt  instant the lease was granted; a read performed under this lease is at least this fresh.
 * @param freshAfter the item's freshness requirement at the moment it was leased.
 */
public record Lease(
        EngagementId engagementId,
        long token,
        String owner,
        Instant leasedAt,
        Instant expiresAt,
        int attempt,
        Priority priority,
        Instant freshAfter) {

    public Lease {
        Objects.requireNonNull(engagementId, "engagementId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(leasedAt, "leasedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(freshAfter, "freshAfter");
        if (!expiresAt.isAfter(leasedAt)) {
            throw new IllegalArgumentException("lease must expire after it is granted");
        }
    }
}
