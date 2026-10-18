package com.caseware.templateupdates.fanout.port;

/**
 * Bounds concurrent calls to the Engagement Service to the capacity it has granted us.
 *
 * <p>The worker acquires a permit BEFORE leasing an item, so leases never age while waiting for capacity.
 * In production the permit count must hold across every worker instance in a region (see README); the
 * in-memory adapters bound a single process, which is what the tests exercise with shared instances.
 *
 * <p>The grant is a hard ceiling for every implementation. An adaptive implementation may admit fewer
 * calls than the grant, using the {@link Feedback} reported on each permit, but never more.
 */
public interface CapacityLimiter {

    /** How the downstream call made under a permit ended, from the capacity point of view. */
    enum Feedback {
        /** The downstream answered in time (loaded, gone, or a permanent per-item error). */
        SUCCESS,
        /** The downstream pushed back or browned out: throttled, or the call timed out. */
        OVERLOAD,
        /** Says nothing about load (e.g. a client-side exception). */
        NEUTRAL
    }

    interface Permit extends AutoCloseable {
        /** Reports the outcome of the call made under this permit. Ignored by non-adaptive limiters. */
        default void report(Feedback feedback) {}

        /** Releases the permit. Idempotent: only the first call has an effect. */
        @Override
        void close();
    }

    /** Blocks until a permit is available. */
    Permit acquire() throws InterruptedException;
}
