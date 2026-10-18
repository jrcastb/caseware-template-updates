package com.caseware.templateupdates.fanout.port;

import com.caseware.templateupdates.fanout.domain.EngagementId;
import com.caseware.templateupdates.fanout.domain.TemplatePublished;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves, in this region, the engagements bound to the published template and branch whose binding
 * cannot be trusted without a read (state SUSPECT in the design: a revision gap or a failed audit sample).
 * Engagements with a confirmed binding are deliberately NOT returned: their pending status is derived
 * from the projection and the version graph with no downstream call.
 *
 * <p>Contract: pages are stable for a given cursor; an engagement may appear in more than one page if the
 * underlying set changes during iteration (harmless: enqueue coalesces).
 */
@FunctionalInterface
public interface TargetResolver {

    /** An engagement to read and the firm that owns it (the firm is for fair scheduling only). */
    record Target(EngagementId engagementId, String firmId) {
        public Target {
            Objects.requireNonNull(engagementId, "engagementId");
            Objects.requireNonNull(firmId, "firmId");
        }
    }

    record Page(List<Target> targets, Optional<String> nextCursor) {
        public Page {
            targets = List.copyOf(targets);
            Objects.requireNonNull(nextCursor, "nextCursor");
        }
    }

    Page page(TemplatePublished event, Optional<String> cursor, int limit);
}
