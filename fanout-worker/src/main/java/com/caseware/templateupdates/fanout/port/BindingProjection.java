package com.caseware.templateupdates.fanout.port;

import com.caseware.templateupdates.fanout.domain.EngagementId;
import com.caseware.templateupdates.fanout.domain.EngagementTemplateState;
import java.time.Instant;
import java.util.Optional;

/**
 * Regional, derived read model of engagement template bindings. The Engagement Service remains the
 * source of truth; this projection can be dropped and rebuilt.
 *
 * <p>Writers are concurrent and unordered: event consumers, organic engagement opens and this worker
 * all write here. Correctness therefore comes from {@link #applyIfNewer}, never from write order.
 * Production adapter: DynamoDB conditional write on {@code revision}.
 */
public interface BindingProjection {

    enum ApplyResult {
        /** Higher revision than stored (or first sight): stored state replaced. */
        APPLIED,
        /** Same revision: state unchanged, confirmation time advanced if later. */
        REFRESHED,
        /** Lower revision than stored: ignored. Late or duplicate observation. */
        STALE_IGNORED
    }

    /**
     * Monotonic upsert. Must be atomic per engagement. Never moves an engagement to a lower revision and
     * never moves its confirmation time backwards.
     */
    ApplyResult applyIfNewer(EngagementTemplateState state, Instant observedAt);

    /** Records that the engagement no longer exists. Idempotent. */
    void markGone(EngagementId engagementId, Instant observedAt);

    /** When the stored binding was last confirmed against the source of truth, if ever. */
    Optional<Instant> confirmedAt(EngagementId engagementId);
}
