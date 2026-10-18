package com.caseware.templateupdates.fanout.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * What an engagement is bound to: the input to {@link VersionSemantics#derive}. Contains no engagement
 * content.
 *
 * @param decline the most recent decline, or {@code null}. Declining pins the PAIR (applied, target): a
 *                decline recorded against a different applied version is stale and is ignored.
 */
public record TemplateBinding(String branch, String applied, Decline decline) {

    public record Decline(String from, String target) {
        public Decline {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(target, "target");
        }
    }

    public TemplateBinding {
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(applied, "applied");
    }

    public static TemplateBinding on(String branch, String applied) {
        return new TemplateBinding(branch, applied, null);
    }

    public Optional<Decline> declined() {
        return Optional.ofNullable(decline);
    }

    /** The user applied {@code target}: the engagement moves, and any decline is cleared. */
    public TemplateBinding apply(String target) {
        return new TemplateBinding(branch, target, null);
    }

    /** The user declined {@code target}: the engagement stays put, and the pair (applied, target) is pinned. */
    public TemplateBinding decline(String target) {
        return new TemplateBinding(branch, applied, new Decline(applied, target));
    }
}
