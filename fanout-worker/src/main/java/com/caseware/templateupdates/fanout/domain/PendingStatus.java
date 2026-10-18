package com.caseware.templateupdates.fanout.domain;

import java.util.Objects;
import java.util.Set;

/**
 * Result of {@link VersionSemantics#derive}. Sealed so every consumer (Status API, cohort evaluation)
 * must handle all three; the UI maps them to the design's tri-state: UpdateAvailable to "Update
 * available", UpToDate to "Up to date", NeedsReview to "Checking" plus an alert to the content team.
 * NeedsReview is never shown as "Up to date".
 */
public sealed interface PendingStatus {

    enum Flag {
        /** The offered head contains changes the user declined earlier (the brief's "declined v5, offered v7"). */
        INCLUDES_DECLINED,
        /** The engagement's applied version was withdrawn. Flagged, never changed automatically. */
        APPLIED_WITHDRAWN
    }

    enum Reason {
        /** A version in the binding is not in the graph yet (graph lag or bad data). */
        UNKNOWN_VERSION,
        /** A published version on the branch is not contained in what we would offer: we refuse to guess. */
        DIVERGED_LINEAGE
    }

    record UpToDate(Set<Flag> flags) implements PendingStatus {
        public UpToDate {
            flags = Set.copyOf(flags);
        }
    }

    record UpdateAvailable(String from, String to, Set<Flag> flags) implements PendingStatus {
        public UpdateAvailable {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            flags = Set.copyOf(flags);
        }
    }

    record NeedsReview(Reason reason, String detail) implements PendingStatus {
        public NeedsReview {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(detail, "detail");
        }
    }
}
