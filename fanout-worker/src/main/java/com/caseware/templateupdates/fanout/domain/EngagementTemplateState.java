package com.caseware.templateupdates.fanout.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * The template binding of one engagement, as read from the Engagement Service (the source of truth).
 *
 * @param revision monotonically increasing per engagement; incremented by the Engagement Service on every
 *                 change to the binding (create, apply, decline). It is the only ordering signal the
 *                 projection trusts: wall-clock times from different systems are never compared.
 * @param decline  the most recent decline as the pair (applied version it was declined from, target), or
 *                 {@code null}
 */
public record EngagementTemplateState(
        EngagementId engagementId,
        String templateId,
        String branch,
        String appliedVersion,
        TemplateBinding.Decline decline,
        long revision) {

    public EngagementTemplateState {
        Objects.requireNonNull(engagementId, "engagementId");
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(appliedVersion, "appliedVersion");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0");
        }
    }

    public Optional<TemplateBinding.Decline> declined() {
        return Optional.ofNullable(decline);
    }

    /** The input to {@link VersionSemantics#derive}; no engagement content. */
    public TemplateBinding binding() {
        return new TemplateBinding(branch, appliedVersion, decline);
    }
}
