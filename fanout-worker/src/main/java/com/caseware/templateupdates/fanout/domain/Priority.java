package com.caseware.templateupdates.fanout.domain;

/**
 * Scheduling priority of a work item. When requests for the same engagement coalesce, the highest
 * priority wins. Priority orders work; it never changes how much downstream capacity is used.
 */
public enum Priority {
    /** One-off migration of existing engagements. Runs in whatever capacity is left. */
    BACKFILL,
    /** Random audit sampling and gap repair. */
    RECONCILE,
    /** A template was published and the engagement's binding cannot be derived with confidence. */
    PUBLISH,
    /** A user is looking at this engagement's firm right now. */
    INTERACTIVE;

    public Priority max(Priority other) {
        return compareTo(other) >= 0 ? this : other;
    }
}
