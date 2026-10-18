package com.caseware.templateupdates.fanout.adapter.inmemory;

import com.caseware.templateupdates.fanout.port.CapacityLimiter;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fixed, process-local limiter: the hard cap alone, with no adaptation (what a first release would ship;
 * see DESIGN.md section 7). Fair, so lanes are served in arrival order. Share one instance between
 * workers to bound them together; production needs a regional limiter (see README).
 */
public final class SemaphoreCapacityLimiter implements CapacityLimiter {

    private final int grant;
    private final Semaphore permits;

    public SemaphoreCapacityLimiter(int permits) {
        if (permits < 1) {
            throw new IllegalArgumentException("permits must be >= 1");
        }
        this.grant = permits;
        this.permits = new Semaphore(permits, true);
    }

    @Override
    public Permit acquire() throws InterruptedException {
        permits.acquire();
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                permits.release();
            }
        };
    }

    /** Permits currently held, including quarantined ones. Never above the grant. */
    public int inUse() {
        return grant - permits.availablePermits();
    }
}
