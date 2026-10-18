package com.caseware.templateupdates.fanout.testsupport;

import com.caseware.templateupdates.fanout.port.CapacityLimiter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps a limiter and counts, per permit, the first release. After the worker is quiet,
 * {@code acquired == released} means no permit leaked; the delegate's own count catches double releases.
 */
public final class CountingLimiter implements CapacityLimiter {

    private final CapacityLimiter delegate;
    private final AtomicInteger acquired = new AtomicInteger();
    private final AtomicInteger released = new AtomicInteger();
    private final AtomicInteger held = new AtomicInteger();
    private final AtomicInteger maxHeld = new AtomicInteger();

    public CountingLimiter(CapacityLimiter delegate) {
        this.delegate = delegate;
    }

    @Override
    public Permit acquire() throws InterruptedException {
        Permit permit = delegate.acquire();
        acquired.incrementAndGet();
        maxHeld.accumulateAndGet(held.incrementAndGet(), Math::max);
        AtomicBoolean closed = new AtomicBoolean();
        return new Permit() {
            @Override
            public void report(Feedback feedback) {
                permit.report(feedback);
            }

            @Override
            public void close() {
                if (closed.compareAndSet(false, true)) {
                    released.incrementAndGet();
                    held.decrementAndGet();
                }
                permit.close();
            }
        };
    }

    public int acquired() { return acquired.get(); }
    public int released() { return released.get(); }
    public int held() { return held.get(); }
    public int maxHeld() { return maxHeld.get(); }
}
