package com.caseware.templateupdates.fanout.demo;

import com.caseware.templateupdates.fanout.domain.EngagementId;
import com.caseware.templateupdates.fanout.domain.EngagementTemplateState;
import com.caseware.templateupdates.fanout.domain.ReadOutcome;
import com.caseware.templateupdates.fanout.port.EngagementStateReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Demo stand-in for the Engagement Service load operation. Each call takes {@code callTime} (the real call
 * is ~1 minute) and fails in the ways the real one can: transient errors, throttling, loads slower than the
 * worker's timeout, one engagement that can never be read, and one that was archived.
 *
 * <p>It measures concurrency on the SERVER side: a slow load keeps a pod busy until it finishes, even if
 * the client stopped waiting, because cancelling our side does not stop theirs. That is the number the
 * capacity grant is about, and what the worker's permit quarantine protects.
 */
public final class SimulatedEngagementService implements EngagementStateReader {

    public enum Kind { LOADED, TRANSIENT, THROTTLED, SLOW, PERMANENT, GONE }

    private final Clock clock;
    private final Duration callTime;
    private final Duration slowTime;
    private final EngagementId broken;
    private final EngagementId archived;
    private final Random random;
    private final Map<Kind, LongAdder> counts = new EnumMap<>(Kind.class);
    private final AtomicInteger serverInFlight = new AtomicInteger();
    private final AtomicInteger maxServerInFlight = new AtomicInteger();

    /** @param slowTime how long a SLOW load occupies the server; set it above the worker's call timeout */
    public SimulatedEngagementService(Clock clock, Duration callTime, Duration slowTime, long seed,
                                      EngagementId broken, EngagementId archived) {
        this.clock = clock;
        this.callTime = callTime;
        this.slowTime = slowTime;
        this.broken = broken;
        this.archived = archived;
        this.random = new Random(seed);
        for (Kind kind : Kind.values()) {
            counts.put(kind, new LongAdder());
        }
    }

    @Override
    public ReadOutcome load(EngagementId id) throws InterruptedException {
        Instant start = clock.instant();
        Kind kind = decide(id);
        counts.get(kind).increment();
        Duration serverTime = kind == Kind.SLOW ? slowTime : callTime;
        long serverEndsAt = System.nanoTime() + serverTime.toNanos();
        maxServerInFlight.accumulateAndGet(serverInFlight.incrementAndGet(), Math::max);
        try {
            Thread.sleep(serverTime);
        } catch (InterruptedException e) {
            // The client gave up; the pod does not know and finishes the load anyway.
            long remaining = serverEndsAt - System.nanoTime();
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(Duration.ofNanos(Math.max(0, remaining)));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    serverInFlight.decrementAndGet();
                }
            });
            throw e;
        }
        serverInFlight.decrementAndGet();
        return switch (kind) {
            case LOADED, SLOW -> new ReadOutcome.Loaded(
                    new EngagementTemplateState(id, "audit-ca", "CA-2026", "v4", null, 1), start);
            case TRANSIENT -> new ReadOutcome.TransientFailure("503 from engagement pods");
            case THROTTLED -> new ReadOutcome.Throttled(callTime.multipliedBy(2));
            case PERMANENT -> new ReadOutcome.PermanentFailure("engagement file cannot be rehydrated");
            case GONE -> new ReadOutcome.Gone("archived");
        };
    }

    private Kind decide(EngagementId id) {
        if (id.equals(broken)) {
            return Kind.PERMANENT;
        }
        if (id.equals(archived)) {
            return Kind.GONE;
        }
        int roll;
        synchronized (random) {
            roll = random.nextInt(100);
        }
        if (roll < 4) {
            return Kind.TRANSIENT;
        }
        if (roll < 7) {
            return Kind.THROTTLED;
        }
        if (roll < 8) {
            return Kind.SLOW;
        }
        return Kind.LOADED;
    }

    public long count(Kind kind) {
        return counts.get(kind).sum();
    }

    public long totalCalls() {
        return counts.values().stream().mapToLong(LongAdder::sum).sum();
    }

    public int serverInFlight() {
        return serverInFlight.get();
    }

    public int maxServerInFlight() {
        return maxServerInFlight.get();
    }
}
