package com.caseware.templateupdates.fanout.domain;

import java.util.Objects;

/** Opaque identifier of an engagement file. Never carries engagement content. */
public record EngagementId(String value) implements Comparable<EngagementId> {
    public EngagementId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("engagement id must not be blank");
        }
    }

    public static EngagementId of(String value) {
        return new EngagementId(value);
    }

    @Override
    public int compareTo(EngagementId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
