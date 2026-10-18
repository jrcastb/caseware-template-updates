package com.caseware.templateupdates.fanout.adapter.inmemory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.caseware.templateupdates.fanout.port.CapacityLimiter.Feedback;
import com.caseware.templateupdates.fanout.port.CapacityLimiter.Permit;
import com.caseware.templateupdates.fanout.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AdaptiveCapacityLimiterTest {

    private static final Instant T0 = Instant.parse("2026-03-02T09:00:00Z");
    private static final Duration COOLDOWN = Duration.ofMinutes(2);

    private final MutableClock clock = new MutableClock(T0);

    private void report(AdaptiveCapacityLimiter limiter, Feedback feedback, int times) throws InterruptedException {
        for (int i = 0; i < times; i++) {
            try (Permit p = limiter.acquire()) {
                p.report(feedback);
            }
        }
    }

    @Test
    void aBurstOfOverloadsHalvesTheLimitOncePerCooldown() throws Exception {
        var limiter = new AdaptiveCapacityLimiter(16, 16, 1, COOLDOWN, clock);
        List<Permit> held = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            held.add(limiter.acquire());
        }
        // A brownout: all 16 one-minute calls fail at nearly the same moment.
        held.forEach(p -> p.report(Feedback.OVERLOAD));
        held.forEach(Permit::close);
        assertEquals(8, limiter.limit(), "16 simultaneous overloads are one congestion event");

        clock.advance(COOLDOWN.minusSeconds(1));
        report(limiter, Feedback.OVERLOAD, 1);
        assertEquals(8, limiter.limit(), "still inside the cooldown");

        clock.advance(Duration.ofSeconds(1));
        report(limiter, Feedback.OVERLOAD, 1);
        assertEquals(4, limiter.limit(), "a new congestion event after the cooldown");
    }

    @Test
    void neverDropsBelowTheFloor() throws Exception {
        var limiter = new AdaptiveCapacityLimiter(16, 16, 2, COOLDOWN, clock);
        for (int i = 0; i < 10; i++) {
            report(limiter, Feedback.OVERLOAD, 1);
            clock.advance(COOLDOWN);
        }
        assertEquals(2, limiter.limit());
    }

    @Test
    void growthIsAdditiveAndStopsAtTheCeiling() throws Exception {
        var limiter = new AdaptiveCapacityLimiter(10, 2, 1, COOLDOWN, clock);
        report(limiter, Feedback.SUCCESS, 1);
        assertEquals(2, limiter.limit(), "one success at limit 2 is half a permit");
        int successes = 1;
        int previous = limiter.limit();
        while (limiter.limit() < 10) {
            report(limiter, Feedback.SUCCESS, 1);
            successes++;
            assertTrue(limiter.limit() - previous <= 1, "jumped from " + previous + " to " + limiter.limit());
            previous = limiter.limit();
        }
        // +1/limit per success: going from L to L+1 takes about L successes, so 2 -> 10 takes ~(10^2 - 2^2)/2.
        assertTrue(successes >= 40 && successes <= 50, "reached the ceiling after " + successes + " successes");
        report(limiter, Feedback.SUCCESS, 10_000);
        assertEquals(10, limiter.limit(), "capped at the grant");
        report(limiter, Feedback.NEUTRAL, 100);
        assertEquals(10, limiter.limit());
    }

    @Test
    void permitsInUseNeverExceedTheGrantUnderConcurrentChurn() throws Exception {
        var limiter = new AdaptiveCapacityLimiter(6, 3, 1, Duration.ofMillis(5), java.time.Clock.systemUTC());
        AtomicInteger inUse = new AtomicInteger();
        AtomicInteger maxInUse = new AtomicInteger();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> workers = new ArrayList<>();
            for (int t = 0; t < 40; t++) {
                long seed = t;
                workers.add(pool.submit(() -> {
                    Random random = new Random(seed);
                    for (int i = 0; i < 150; i++) {
                        try (Permit p = limiter.acquire()) {
                            maxInUse.accumulateAndGet(inUse.incrementAndGet(), Math::max);
                            Thread.sleep(0, 200_000);
                            p.report(random.nextInt(10) == 0 ? Feedback.OVERLOAD : Feedback.SUCCESS);
                            inUse.decrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : workers) {
                f.get(30, TimeUnit.SECONDS);
            }
        }
        assertTrue(maxInUse.get() <= 6, "observed " + maxInUse.get() + " permits in use, grant 6");
        assertTrue(limiter.maxInUse() <= 6, "limiter recorded " + limiter.maxInUse());
        assertEquals(0, limiter.inUse(), "every permit released");
    }

    @Test
    void closeIsIdempotent() throws Exception {
        var limiter = new AdaptiveCapacityLimiter(2, 2, 1, COOLDOWN, clock);
        Permit p = limiter.acquire();
        p.close();
        p.close();
        assertEquals(0, limiter.inUse());
    }

    @Test
    void rejectsAnInitialLimitAboveTheGrant() {
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveCapacityLimiter(4, 5, 1, COOLDOWN, clock));
    }
}
