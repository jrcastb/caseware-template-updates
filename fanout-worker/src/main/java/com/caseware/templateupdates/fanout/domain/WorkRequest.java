package com.caseware.templateupdates.fanout.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A request to (re)establish an engagement's binding from the Engagement Service.
 *
 * <p>Work is keyed by engagement, not by (publish, engagement): the downstream call reads the
 * engagement's current binding, which is independent of which publish asked for it. One read therefore
 * satisfies every outstanding request for that engagement, which is what makes coalescing correct.
 *
 * @param firmId     owning firm; used only for fair scheduling across firms (design A4: listable without
 *                   a load). Never sent downstream.
 * @param freshAfter the read must start at or after this instant to satisfy the request. Set from the
 *                   requester's own clock at request time (never from another system's timestamp).
 * @param reason     free-text provenance for operators, e.g. {@code "publish:p-123"}
 */
public record WorkRequest(
        EngagementId engagementId, String firmId, Priority priority, Instant freshAfter, String reason) {
    public WorkRequest {
        Objects.requireNonNull(engagementId, "engagementId");
        Objects.requireNonNull(firmId, "firmId");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(freshAfter, "freshAfter");
        Objects.requireNonNull(reason, "reason");
    }
}
