package com.caseware.templateupdates.fanout.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Event emitted by the Template Store publish hook.
 *
 * <p>Delivery contract (assumed, see design A3): at-least-once, possibly duplicated, possibly out of
 * order relative to other publishes. {@code publishId} is unique per publish and is the idempotency key.
 * Consumers must not infer anything from arrival order; the version graph is the source of truth for
 * which version is the head of a branch.
 */
public record TemplatePublished(
        String publishId, String templateId, String branch, String version, Instant publishedAt) {

    public TemplatePublished {
        Objects.requireNonNull(publishId, "publishId");
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(publishedAt, "publishedAt");
    }
}
