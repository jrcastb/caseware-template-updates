package com.caseware.templateupdates.fanout.adapter.inmemory;

import com.caseware.templateupdates.fanout.port.CapacityLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * AIMD limiter under a hard ceiling (the capacity grant).
 *
 * <ul>
 *   <li><b>Additive increase:</b> each {@code SUCCESS} adds {@code 1/limit}, so the limit grows by about one
 *       permit per limit's worth of successful calls: one per "round" of one-minute calls, never a jump.</li>
 *   <li><b>Multiplicative decrease:</b> an {@code OVERLOAD} halves the limit, at most once per
 *       {@code cooldown}. One brownout of one-minute calls produces many overload reports at nearly the same
 *       time; without the cooldown they would collapse the limit to the floor.</li>
 *   <li><b>Ceiling:</b> the limit never exceeds the grant, and a permit is admitted only while
 *       {@code inUse < floor(limit)}, so in-use permits never exceed the grant either. Lowering the limit
 *       does not revoke permits already held; it only delays new admissions.</li>
 * </ul>
 * Process-local, like {@link SemaphoreCapacityLimiter}.
 */
public final class AdaptiveCapacityLimiter implements CapacityLimiter {

    private final int ceiling;
    private final int floor;
    private final Duration cooldown;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition admissible = lock.newCondition();

    private double limit;
    private int inUse;
    private int maxInUse;
    private Instant lastDecrease;

    /**
     * @param ceiling  the capacity grant; never exceeded
     * @param initial  starting limit, at most the ceiling (start below it to probe a downstream gently)
     * @param floor    the limit never drops below this, so progress continues during a brownout
     * @param cooldown minimum time between two decreases
     */
    public AdaptiveCapacityLimiter(int ceiling, int initial, int floor, Duration cooldown, Clock clock) {
        if (floor < 1 || floor > ceiling) {
            throw new IllegalArgumentException("need 1 <= floor <= ceiling");
        }
        if (initial < floor || initial > ceiling) {
            throw new IllegalArgumentException("need floor <= initial <= ceiling");
        }
        this.ceiling = ceiling;
        this.floor = floor;
        this.cooldown = Objects.requireNonNull(cooldown, "cooldown");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.limit = initial;
    }

    @Override
    public Permit acquire() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (inUse >= admitted()) {
                admissible.await();
            }
            inUse++;
            maxInUse = Math.max(maxInUse, inUse);
        } finally {
            lock.unlock();
        }
        return new AdaptivePermit();
    }

    /** Current admission limit (whole permits), between the floor and the ceiling. */
    public int limit() {
        lock.lock();
        try {
            return admitted();
        } finally {
            lock.unlock();
        }
    }

    public int inUse() {
        lock.lock();
        try {
            return inUse;
        } finally {
            lock.unlock();
        }
    }

    public int maxInUse() {
        lock.lock();
        try {
            return maxInUse;
        } finally {
            lock.unlock();
        }
    }

    private int admitted() {
        return Math.min(ceiling, (int) Math.floor(limit));
    }

    private void onFeedback(Feedback feedback) {
        lock.lock();
        try {
            switch (feedback) {
                case SUCCESS -> {
                    int before = admitted();
                    limit = Math.min(ceiling, limit + 1.0 / limit);
                    if (admitted() > before) {
                        admissible.signalAll();
                    }
                }
                case OVERLOAD -> {
                    Instant now = clock.instant();
                    if (lastDecrease == null || !now.isBefore(lastDecrease.plus(cooldown))) {
                        limit = Math.max(floor, limit / 2);
                        lastDecrease = now;
                    }
                }
                case NEUTRAL -> { }
            }
        } finally {
            lock.unlock();
        }
    }

    private void release() {
        lock.lock();
        try {
            inUse--;
            admissible.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private final class AdaptivePermit implements Permit {
        private final AtomicBoolean released = new AtomicBoolean();

        @Override
        public void report(Feedback feedback) {
            onFeedback(Objects.requireNonNull(feedback, "feedback"));
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                release();
            }
        }
    }
}
