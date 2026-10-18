package com.caseware.templateupdates.fanout.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.caseware.templateupdates.fanout.adapter.inmemory.AdaptiveCapacityLimiter;
import com.caseware.templateupdates.fanout.adapter.inmemory.InMemoryBindingProjection;
import com.caseware.templateupdates.fanout.adapter.inmemory.InMemoryWorkQueue;
import com.caseware.templateupdates.fanout.adapter.inmemory.InMemoryWorkQueue.State;
import com.caseware.templateupdates.fanout.adapter.inmemory.SemaphoreCapacityLimiter;
import com.caseware.templateupdates.fanout.domain.EngagementId;
import com.caseware.templateupdates.fanout.domain.EngagementTemplateState;
import com.caseware.templateupdates.fanout.domain.Lease;
import com.caseware.templateupdates.fanout.domain.Priority;
import com.caseware.templateupdates.fanout.domain.ReadOutcome;
import com.caseware.templateupdates.fanout.domain.WorkRequest;
import com.caseware.templateupdates.fanout.port.BindingProjection;
import com.caseware.templateupdates.fanout.port.BindingProjection.ApplyResult;
import com.caseware.templateupdates.fanout.port.CapacityLimiter;
import com.caseware.templateupdates.fanout.testsupport.CountingLimiter;
import com.caseware.templateupdates.fanout.testsupport.FakeEngagementStateReader;
import com.caseware.templateupdates.fanout.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FanOutWorkerTest {

    private static final Instant T0 = Instant.parse("2026-03-02T09:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final Duration CALL_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration GRACE = Duration.ofMillis(50);
    private static final RetryPolicy RETRY = new RetryPolicy(3, Duration.ofSeconds(30), Duration.ofMinutes(10));
    private static final EngagementId E1 = EngagementId.of("eng-001");
    private static final String FIRM = "firm-1";

    private MutableClock clock;
    private InMemoryWorkQueue queue;
    private InMemoryBindingProjection projection;
    private FakeEngagementStateReader reader;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        queue = new InMemoryWorkQueue();
        projection = new InMemoryBindingProjection();
        reader = new FakeEngagementStateReader(clock);
    }

    private FanOutWorker worker(String id, CapacityLimiter limiter, int lanes) {
        return worker(id, limiter, lanes, new WorkerConfig(id, lanes, LEASE, CALL_TIMEOUT, GRACE, RETRY));
    }

    private FanOutWorker worker(String id, CapacityLimiter limiter, int lanes, WorkerConfig config) {
        return worker(limiter, config, projection);
    }

    private FanOutWorker worker(CapacityLimiter limiter, WorkerConfig config, BindingProjection projection) {
        // jitter fixed at 0.5 so backoff delays are deterministic
        return new FanOutWorker(queue, reader, projection, limiter, config, clock, () -> 0.5);
    }

    private static WorkerConfig config(int lanes, Duration callTimeout, Duration grace) {
        return new WorkerConfig("w1", lanes, LEASE, callTimeout, grace, RETRY);
    }

    private void enqueue(EngagementId id, String firm, Priority priority) {
        queue.enqueue(new WorkRequest(id, firm, priority, clock.instant(), "test"), clock.instant());
    }

    private FanOutWorker singleLaneWorker() {
        return worker("w1", new SemaphoreCapacityLimiter(1), 1);
    }

    private void enqueue(EngagementId id, Priority priority) {
        queue.enqueue(new WorkRequest(id, FIRM, priority, clock.instant(), "test"), clock.instant());
    }

    private static List<EngagementId> engagements(int count) {
        return IntStream.range(0, count).mapToObj(i -> EngagementId.of("eng-%03d".formatted(i))).toList();
    }

    private long revisionOf(EngagementId id) {
        return projection.find(id).orElseThrow().state().revision();
    }

    // ---- capacity -----------------------------------------------------------------------------------

    @Test
    void neverExceedsTheCapacityGrantEvenWithManyMoreLanes() throws Exception {
        List<EngagementId> ids = engagements(60);
        ids.forEach(id -> enqueue(id, Priority.PUBLISH));
        reader.withRealDelay(Duration.ofMillis(15));

        try (FanOutWorker worker = worker("w1", new SemaphoreCapacityLimiter(4), 16)) {
            worker.drain();
        }

        assertTrue(reader.maxInFlight() <= 4, "max in flight was " + reader.maxInFlight());
        assertTrue(reader.maxInFlight() > 1, "expected real concurrency, got " + reader.maxInFlight());
        assertEquals(60, reader.totalCalls());
        assertEquals(0, queue.size());
    }

    @Test
    void sharedLimiterBoundsSeveralWorkersAndEachEngagementIsReadExactlyOnce() throws Exception {
        List<EngagementId> ids = engagements(40);
        ids.forEach(id -> enqueue(id, Priority.BACKFILL));
        reader.withRealDelay(Duration.ofMillis(10));
        CapacityLimiter regionGrant = new SemaphoreCapacityLimiter(3);

        try (FanOutWorker a = worker("a", regionGrant, 10);
             FanOutWorker b = worker("b", regionGrant, 10);
             ExecutorService both = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> fa = both.submit(() -> { a.drain(); return null; });
            Future<?> fb = both.submit(() -> { b.drain(); return null; });
            fa.get();
            fb.get();
        }

        assertTrue(reader.maxInFlight() <= 3, "max in flight was " + reader.maxInFlight());
        assertEquals(40, reader.totalCalls());
        ids.forEach(id -> assertEquals(1, reader.calls(id), "calls for " + id));
    }

    @Test
    void higherPriorityWorkIsServedFirst() throws Exception {
        EngagementId backfill = EngagementId.of("b");
        EngagementId publish = EngagementId.of("p");
        EngagementId interactive = EngagementId.of("i");
        enqueue(backfill, Priority.BACKFILL);
        enqueue(publish, Priority.PUBLISH);
        enqueue(interactive, Priority.INTERACTIVE);

        try (FanOutWorker worker = singleLaneWorker()) {
            worker.drain();
        }

        assertEquals(List.of(interactive, publish, backfill), reader.callOrder());
    }

    // ---- failure handling -----------------------------------------------------------------------------

    @Test
    void transientFailuresAreRetriedWithBackoffAndThenSucceed() throws Exception {
        enqueue(E1, Priority.PUBLISH);
        reader.script(E1, new ReadOutcome.TransientFailure("503"), new ReadOutcome.TransientFailure("503"));

        try (FanOutWorker worker = singleLaneWorker()) {
            worker.drain();
            // attempt 1 failed: 30s capped exponential, equal jitter at 0.5 -> 22.5s
            assertEquals(T0.plusMillis(22_500), queue.view(E1).orElseThrow().notBefore());

            worker.drain();
            assertEquals(1, reader.calls(E1), "must not retry before backoff elapses");

            clock.advance(Duration.ofMillis(22_500));
            worker.drain(); // attempt 2 fails: 60s -> 45s
            assertEquals(2, reader.calls(E1));
            assertEquals(clock.instant().plusSeconds(45), queue.view(E1).orElseThrow().notBefore());

            clock.advance(Duration.ofSeconds(45));
            worker.drain(); // attempt 3 succeeds
        }

        assertEquals(3, reader.calls(E1));
        assertEquals(1, revisionOf(E1));
        assertEquals(0, queue.size());
    }

    @Test
    void exhaustedRetriesAreDeadLetteredAndNotRetriedForever() throws Exception {
        enqueue(E1, Priority.PUBLISH);
        reader.script(E1,
                new ReadOutcome.TransientFailure("503"),
                new ReadOutcome.TransientFailure("503"),
                new ReadOutcome.TransientFailure("503"));

        try (FanOutWorker worker = singleLaneWorker()) {
            for (int i = 0; i < 5; i++) {
                worker.drain();
                clock.advance(Duration.ofMinutes(15));
            }
        }

        assertEquals(3, reader.calls(E1));
        assertEquals(List.of(E1), queue.deadLettered());
        assertTrue(projection.find(E1).isEmpty());
    }

    @Test
    void permanentFailureIsDeadLetteredImmediately() throws Exception {
        enqueue(E1, Priority.PUBLISH);
        reader.script(E1, new ReadOutcome.PermanentFailure("engagement file corrupt"));

        try (FanOutWorker worker = singleLaneWorker()) {
            worker.drain();
        }

        assertEquals(1, reader.calls(E1));
        assertEquals(State.DEAD, queue.view(E1).orElseThrow().state());
    }

    @Test
    void throttlingIsHonouredAndDoesNotConsumeTheRetryBudget() throws Exception {
        enqueue(E1, Priority.PUBLISH);
        ReadOutcome throttled = new ReadOutcome.Throttled(Duration.ofSeconds(10));
        reader.script(E1, throttled, throttled, throttled, throttled, throttled); // more than maxAttempts

        try (FanOutWorker worker = singleLaneWorker()) {
            for (int i = 0; i < 6; i++) {
                FanOutWorker.DrainResult result = worker.drain();
                if (i < 5) {
                    assertEquals(1, result.throttled());
                    assertEquals(clock.instant().plusSeconds(10), queue.view(E1).orElseThrow().notBefore());
                }
                clock.advance(Duration.ofSeconds(10));
            }
        }

        assertEquals(6, reader.calls(E1));
        assertTrue(queue.deadLettered().isEmpty());
        assertEquals(1, revisionOf(E1));
    }

    @Test
    void hungDownstreamCallTimesOutAndIsRetried() throws Exception {
        enqueue(E1, Priority.PUBLISH);
        reader.scriptHang(E1);
        var config = new WorkerConfig("w1", 1, Duration.ofSeconds(1), Duration.ofMillis(100), GRACE, RETRY);

        try (FanOutWorker worker = worker("w1", new SemaphoreCapacityLimiter(1), 1, config)) {
            worker.drain();
            var view = queue.view(E1).orElseThrow();
            assertEquals(State.READY, view.state());
            assertTrue(view.lastReason().startsWith("timeout"), view.lastReason());

            clock.advance(Duration.ofMinutes(1));
            worker.drain();
        }

        assertEquals(2, reader.calls(E1));
        assertEquals(1, revisionOf(E1));
    }

    @Test
    void exceptionFromTheClientLibraryIsTreatedAsTransient() throws Exception {
        enqueue(E1, Priority.PUBLISH);
        reader.scriptThrow(E1, new IllegalStateException("connection reset"));

        try (FanOutWorker worker = singleLaneWorker()) {
            worker.drain();
            assertEquals(State.READY, queue.view(E1).orElseThrow().state());
            clock.advance(Duration.ofMinutes(1));
            worker.drain();
        }

        assertEquals(2, reader.calls(E1));
        assertEquals(0, queue.size());
    }

    @Test
    void goneEngagementIsRecordedAndNotRetried() throws Exception {
        enqueue(E1, Priority.BACKFILL);
        reader.script(E1, new ReadOutcome.Gone("archived"));

        try (FanOutWorker worker = singleLaneWorker()) {
            worker.drain();
        }

        assertTrue(projection.find(E1).orElseThrow().gone());
        assertEquals(0, queue.size());
    }

    // ---- crash recovery and fencing ----------------------------------------------------------------

    @Test
    void itemLeasedByACrashedWorkerIsReclaimedAfterTheLeaseExpires() throws Exception {
        enqueue(E1, Priority.PUBLISH);
        queue.leaseOne("crashed-worker", LEASE, clock.instant()); // leased, then the process died

        try (FanOutWorker worker = singleLaneWorker()) {
            worker.drain();
            assertEquals(0, reader.calls(E1), "lease still live: nobody else may take it");

            clock.advance(LEASE.plusSeconds(1));
            worker.drain();
        }

        assertEquals(1, reader.calls(E1));
        assertEquals(0, queue.size());
    }

    @Test
    void zombieWorkerCannotOverwriteTheOutcomeOfItsSuccessor() throws Exception {
        enqueue(E1, Priority.PUBLISH);
        Lease zombie = queue.leaseOne("zombie", LEASE, clock.instant()).orElseThrow();
        Instant zombieObservedAt = clock.instant();
        clock.advance(LEASE.plusSeconds(1)); // zombie paused (GC, network partition) past its lease
        reader.scriptLoaded(E1, 2);          // meanwhile the user applied an update: revision 2

        try (FanOutWorker worker = singleLaneWorker()) {
            worker.drain();
        }

        // The zombie wakes up with its stale observation (revision 1) and tries to record it.
        var staleWrite = projection.applyIfNewer(FakeEngagementStateReader.state(E1, 1), zombieObservedAt);
        assertEquals(ApplyResult.STALE_IGNORED, staleWrite);
        assertFalse(queue.complete(zombie, clock.instant()));
        assertFalse(queue.retryLater(zombie, clock.instant(), true, "zombie"));
        assertEquals(2, revisionOf(E1));
    }

    @Test
    void itemThatKeepsCrashingWorkersIsDeadLetteredWithoutAnotherCall() throws Exception {
        enqueue(E1, Priority.PUBLISH);
        for (int i = 0; i < RETRY.maxAttempts(); i++) { // each lease expires: the worker died mid-call
            queue.leaseOne("doomed-" + i, LEASE, clock.instant()).orElseThrow();
            clock.advance(LEASE.plusSeconds(1));
        }

        try (FanOutWorker worker = singleLaneWorker()) {
            worker.drain();
        }

        assertEquals(0, reader.totalCalls());
        assertEquals(List.of(E1), queue.deadLettered());
    }

    // ---- races and idempotency --------------------------------------------------------------------

    @Test
    void requestArrivingDuringAnInFlightReadTriggersExactlyOneMoreRead() throws Exception {
        enqueue(E1, Priority.BACKFILL);
        AtomicBoolean once = new AtomicBoolean();
        reader.duringCall(id -> {
            if (once.compareAndSet(false, true)) {
                // While the first one-minute read runs, a revision gap marks the binding suspect.
                clock.advance(Duration.ofSeconds(30));
                queue.enqueue(new WorkRequest(id, FIRM, Priority.PUBLISH, clock.instant(), "suspect"), clock.instant());
            }
        });

        try (FanOutWorker worker = singleLaneWorker()) {
            worker.drain();
        }

        assertEquals(2, reader.calls(E1));
        assertEquals(0, queue.size());
    }

    @Test
    void requestAlreadySatisfiedByTheInFlightReadDoesNotCauseAnotherRead() throws Exception {
        enqueue(E1, Priority.BACKFILL);
        reader.duringCall(id -> queue.enqueue(
                new WorkRequest(id, FIRM, Priority.PUBLISH, T0.minusSeconds(60), "older requirement"), clock.instant()));

        try (FanOutWorker worker = singleLaneWorker()) {
            worker.drain();
        }

        assertEquals(1, reader.calls(E1));
        assertEquals(0, queue.size());
    }

    @Test
    void skipsTheDownstreamCallWhenAnOrganicOpenAlreadyConfirmedTheBinding() throws Exception {
        enqueue(E1, Priority.BACKFILL);
        clock.advance(Duration.ofHours(2));
        // The user opened the engagement; the Engagement Service emitted its binding as a side effect.
        projection.applyIfNewer(FakeEngagementStateReader.state(E1, 3), clock.instant());

        try (FanOutWorker worker = singleLaneWorker()) {
            FanOutWorker.DrainResult result = worker.drain();
            assertEquals(1, result.processed());
            assertEquals(0, result.downstreamCalls());
        }

        assertEquals(0, reader.totalCalls());
        assertEquals(0, queue.size());
    }

    @Test
    void configRejectsALeaseThatCouldExpireMidCall() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorkerConfig("w", 1, Duration.ofMinutes(1), Duration.ofMinutes(1), GRACE, RETRY));
    }

    // ---- A1 adaptive limiter, through the worker ------------------------------------------------------

    @Test
    void adaptiveLimiterGrowsOnSuccessButNeverExceedsTheGrant() throws Exception {
        engagements(60).forEach(id -> enqueue(id, Priority.PUBLISH));
        reader.withRealDelay(Duration.ofMillis(10));
        var limiter = new AdaptiveCapacityLimiter(4, 2, 1, Duration.ofMinutes(1), clock);

        try (FanOutWorker worker = worker("w1", limiter, 16)) {
            worker.drain();
        }

        assertTrue(reader.maxInFlight() <= 4, "max in flight was " + reader.maxInFlight());
        assertEquals(4, limiter.limit(), "60 successes from a limit of 2 reach the grant of 4");
        assertEquals(0, limiter.inUse());
        assertEquals(0, queue.size());
    }

    @Test
    void throttlingIsReportedToTheLimiterAsOverload() throws Exception {
        enqueue(E1, Priority.PUBLISH);
        reader.script(E1, new ReadOutcome.Throttled(Duration.ofSeconds(10)));
        var limiter = new AdaptiveCapacityLimiter(8, 8, 1, Duration.ofMinutes(1), clock);

        try (FanOutWorker worker = worker("w1", limiter, 1)) {
            worker.drain();
        }

        assertEquals(4, limiter.limit());
    }

    // ---- A2 shared admission pause ----------------------------------------------------------------------

    @Test
    void oneThrottlePausesAdmissionForEveryLaneUntilRetryAfter() throws Exception {
        EngagementId throttles = EngagementId.of("eng-throttles");
        enqueue(throttles, Priority.INTERACTIVE); // leased first
        List<EngagementId> others = engagements(7);
        others.forEach(id -> enqueue(id, Priority.PUBLISH));
        reader.script(throttles, new ReadOutcome.Throttled(Duration.ofSeconds(10)));
        // The throttle arrives while the three other lanes are mid-call; those calls outlast it.
        CountDownLatch othersInFlight = new CountDownLatch(3);
        AtomicBoolean firstWave = new AtomicBoolean(true);
        reader.duringCall(id -> {
            if (id.equals(throttles)) {
                if (firstWave.get()) {
                    await(othersInFlight);
                    firstWave.set(false);
                }
            } else if (firstWave.get()) {
                othersInFlight.countDown();
                sleep(100);
            }
        });

        try (FanOutWorker worker = worker("w1", new SemaphoreCapacityLimiter(4), 4)) {
            FanOutWorker.DrainResult first = worker.drain();
            assertEquals(1, first.throttled());
            assertEquals(Optional.of(T0.plusSeconds(10)), first.pausedUntil());
            assertEquals(4, reader.totalCalls(),
                    "the three calls already in flight finish; no lane starts a new one after the throttle");
            assertEquals(0, queue.view(throttles).orElseThrow().attempts(), "the throttle is refunded");

            FanOutWorker.DrainResult stillPaused = worker.drain();
            assertEquals(0, stillPaused.downstreamCalls(), "nothing starts before Retry-After");

            clock.advance(Duration.ofSeconds(10));
            worker.drain();
        }

        assertEquals(0, queue.size());
        assertEquals(2, reader.calls(throttles));
        others.forEach(id -> assertEquals(1, reader.calls(id)));
    }

    @Test
    void aLaneThatLeasedBeforeTheThrottleDoesNotStartItsCall() throws Exception {
        EngagementId throttles = EngagementId.of("eng-throttles");
        EngagementId leasedMeanwhile = EngagementId.of("eng-meanwhile");
        enqueue(throttles, Priority.INTERACTIVE);
        enqueue(leasedMeanwhile, Priority.PUBLISH);
        CountDownLatch otherLaneLeased = new CountDownLatch(1);
        reader.script(throttles, new ReadOutcome.Throttled(Duration.ofSeconds(10)));
        reader.duringCall(id -> {
            if (id.equals(throttles)) {
                await(otherLaneLeased); // throttle only once the other lane holds its lease
            }
        });
        // The freshness check is the last step before the call: hold the other lane there until the
        // throttle has been recorded (the throttled item is back in the queue with its Retry-After).
        BindingProjection holdsBeforeTheCall = new DelegatingProjection(projection) {
            @Override
            public Optional<Instant> confirmedAt(EngagementId id) {
                if (id.equals(leasedMeanwhile)) {
                    otherLaneLeased.countDown();
                    waitUntil(() -> queue.view(throttles).orElseThrow().notBefore().isAfter(T0));
                }
                return super.confirmedAt(id);
            }
        };
        var limiter = new CountingLimiter(new SemaphoreCapacityLimiter(2));

        try (FanOutWorker worker = worker(limiter, config(2, CALL_TIMEOUT, GRACE), holdsBeforeTheCall)) {
            worker.drain();
        }

        assertEquals(0, reader.calls(leasedMeanwhile));
        var view = queue.view(leasedMeanwhile).orElseThrow();
        assertEquals(State.READY, view.state());
        assertEquals(T0.plusSeconds(10), view.notBefore());
        assertEquals(0, view.attempts(), "not the item's fault: refunded");
        assertEquals(limiter.acquired(), limiter.released());
    }

    @Test
    void theProductionLoopPausesForRetryAfterNotForTheSlowestCallInFlight() throws Exception {
        // Real clock: this is about wall-clock admission, which a drain-at-a-time loop gets wrong.
        var realClock = java.time.Clock.systemUTC();
        var realReader = new FakeEngagementStateReader(realClock);
        var realQueue = new InMemoryWorkQueue();
        EngagementId throttles = EngagementId.of("eng-a-throttles");
        EngagementId slow = EngagementId.of("eng-b-slow");
        realQueue.enqueue(new WorkRequest(throttles, FIRM, Priority.INTERACTIVE, realClock.instant(), "t"), realClock.instant());
        realQueue.enqueue(new WorkRequest(slow, FIRM, Priority.PUBLISH, realClock.instant(), "t"), realClock.instant());
        CountDownLatch slowStarted = new CountDownLatch(1);
        AtomicBoolean throttledOnce = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicLong throttledAt = new java.util.concurrent.atomic.AtomicLong();
        realReader.script(throttles, new ReadOutcome.Throttled(Duration.ofMillis(150)));
        realReader.duringCall(id -> {
            if (id.equals(slow)) {
                slowStarted.countDown();
                sleep(1_500);
            } else if (id.equals(throttles) && throttledOnce.compareAndSet(false, true)) {
                await(slowStarted);
                // Only now enqueue the work that has to wait for the pause.
                engagements(3).forEach(e -> realQueue.enqueue(
                        new WorkRequest(e, FIRM, Priority.PUBLISH, realClock.instant(), "t"), realClock.instant()));
                throttledAt.set(System.nanoTime());
            }
        });
        var config = new WorkerConfig("w1", 3, LEASE, CALL_TIMEOUT, GRACE, RETRY);

        try (var worker = new FanOutWorker(realQueue, realReader, new InMemoryBindingProjection(),
                new SemaphoreCapacityLimiter(3), config, realClock, () -> 0.5)) {
            worker.runUntil(() -> realQueue.size() == 0, Duration.ofMillis(10));
        }

        List<Long> afterThrottle = realReader.startNanos().stream().filter(t -> t > throttledAt.get()).sorted().toList();
        assertTrue(afterThrottle.size() >= 4, "expected the retry and three new calls, got " + afterThrottle.size());
        long firstMillis = TimeUnit.NANOSECONDS.toMillis(afterThrottle.getFirst() - throttledAt.get());
        assertTrue(firstMillis >= 140, "a call started " + firstMillis + " ms after the throttle, before Retry-After");
        assertTrue(firstMillis < 1_000,
                "admission resumed only after " + firstMillis + " ms: it waited for the 1.5 s call, not Retry-After");
    }

    // ---- A3 fairness across firms, through the worker ---------------------------------------------------

    @Test
    void aSlowBigFirmDoesNotCrowdOutSmallFirmsWhileTheyHaveReadyWork() throws Exception {
        queue = new InMemoryWorkQueue(2);
        List<EngagementId> big = firmItems("a-big", 40);
        List<EngagementId> small = new ArrayList<>(firmItems("s1", 10));
        small.addAll(firmItems("s2", 10));
        AtomicInteger bigInFlight = new AtomicInteger();
        AtomicInteger maxBigWhileSmallReady = new AtomicInteger();
        reader.duringCall(id -> {
            boolean isBig = id.value().startsWith("a-big");
            if (isBig) {
                int now = bigInFlight.incrementAndGet();
                boolean smallReady = small.stream().anyMatch(s -> queue.view(s)
                        .map(v -> v.state() == State.READY).orElse(false));
                if (smallReady) {
                    maxBigWhileSmallReady.accumulateAndGet(now, Math::max);
                }
            }
            sleep(isBig ? 60 : 5);
            if (isBig) {
                bigInFlight.decrementAndGet();
            }
        });

        try (FanOutWorker worker = worker("w1", new SemaphoreCapacityLimiter(4), 8)) {
            worker.drain();
        }

        assertTrue(maxBigWhileSmallReady.get() <= 2,
                "big firm held " + maxBigWhileSmallReady.get() + " calls while small firms waited (cap 2)");
        assertEquals(60, reader.totalCalls());
        assertEquals(0, queue.size());
        big.forEach(id -> assertEquals(1, reader.calls(id)));
    }

    @Test
    void aLoneBigFirmStillUsesTheWholeGrant() throws Exception {
        queue = new InMemoryWorkQueue(2);
        firmItems("a-big", 40);
        reader.withRealDelay(Duration.ofMillis(30));

        try (FanOutWorker worker = worker("w1", new SemaphoreCapacityLimiter(8), 8)) {
            worker.drain();
        }

        assertEquals(8, reader.maxInFlight(), "the per-firm cap of 2 must not idle the other 6 permits");
        assertEquals(0, queue.size());
    }

    // ---- A4 permit quarantine on timeout -------------------------------------------------------------------

    @Test
    void aTimedOutCallKeepsItsPermitForTheGracePeriod() throws Exception {
        EngagementId hangs = EngagementId.of("eng-hangs");
        EngagementId next = EngagementId.of("eng-next");
        enqueue(hangs, Priority.INTERACTIVE);
        enqueue(next, Priority.PUBLISH);
        reader.scriptHang(hangs);
        var limiter = new CountingLimiter(new SemaphoreCapacityLimiter(1));

        try (FanOutWorker worker = worker(limiter, config(2, Duration.ofMillis(100), Duration.ofMillis(400)), projection)) {
            worker.drain();
        }

        long gapMillis = TimeUnit.NANOSECONDS.toMillis(reader.firstStartNanos(next) - reader.firstStartNanos(hangs));
        assertTrue(gapMillis >= 480, "next call started " + gapMillis + " ms after the hung one; timeout 100 + grace 400");
        assertEquals(1, limiter.maxHeld(), "grant 1 never exceeded");
        assertEquals(limiter.acquired(), limiter.released());
    }

    @Test
    void aQuarantinedPermitIsReleasedAsSoonAsTheAbandonedCallReturns() throws Exception {
        EngagementId slow = EngagementId.of("eng-slow");
        EngagementId next = EngagementId.of("eng-next");
        enqueue(slow, Priority.INTERACTIVE);
        enqueue(next, Priority.PUBLISH);
        AtomicBoolean once = new AtomicBoolean();
        reader.duringCall(id -> {
            if (id.equals(slow) && once.compareAndSet(false, true)) {
                sleep(250); // finishes downstream 150 ms after our 100 ms timeout
            }
        });
        var limiter = new CountingLimiter(new SemaphoreCapacityLimiter(1));

        try (FanOutWorker worker = worker(limiter, config(2, Duration.ofMillis(100), Duration.ofSeconds(5)), projection)) {
            worker.drain();
        }

        long gapMillis = TimeUnit.NANOSECONDS.toMillis(reader.firstStartNanos(next) - reader.firstStartNanos(slow));
        assertTrue(gapMillis >= 230 && gapMillis < 2_000,
                "next call started after " + gapMillis + " ms: the abandoned call returned at ~250 ms, grace is 5 s");
        assertEquals(limiter.acquired(), limiter.released());
    }

    // ---- B1 crash loop, with real workers dying ---------------------------------------------------------

    @Test
    void anEngagementThatKillsEveryWorkerIsDeadLetteredAfterMaxAttempts() throws Exception {
        enqueue(E1, Priority.PUBLISH);
        Duration zombieLease = Duration.ofMinutes(10);
        List<FanOutWorker> dead = new ArrayList<>();
        try (ExecutorService processes = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> drains = new ArrayList<>();
            for (int attempt = 1; attempt <= RETRY.maxAttempts(); attempt++) {
                reader.scriptHang(E1); // the load wedges the worker: no outcome is ever recorded
                var doomed = new FanOutWorker(queue, reader, projection, new SemaphoreCapacityLimiter(1),
                        new WorkerConfig("doomed-" + attempt, 1, zombieLease, Duration.ofMinutes(5), Duration.ZERO, RETRY),
                        clock, () -> 0.5);
                dead.add(doomed);
                drains.add(processes.submit(() -> { doomed.drain(); return null; }));
                int expectedCalls = attempt;
                waitUntil(() -> reader.calls(E1) == expectedCalls);
                clock.advance(zombieLease.plusSeconds(1)); // the process is gone; its lease expires
            }

            try (FanOutWorker healthy = singleLaneWorker()) {
                healthy.drain();
            }
            assertEquals(List.of(E1), queue.deadLettered());
            assertEquals(RETRY.maxAttempts(), reader.calls(E1), "dead-lettered without feeding it another worker");

            // The wedged workers finally wake up; their late outcomes are fenced off.
            dead.forEach(FanOutWorker::close);
            for (Future<?> drain : drains) {
                drain.get(10, TimeUnit.SECONDS);
            }
        }
        assertEquals(State.DEAD, queue.view(E1).orElseThrow().state());
    }

    // ---- B2 overlapping requests during an in-flight read ------------------------------------------------

    @Test
    void publishAndBackfillArrivingDuringAReadCauseAtMostOneExtraRead() throws Exception {
        queue.enqueue(new WorkRequest(E1, FIRM, Priority.RECONCILE, T0, "audit"), T0);
        AtomicBoolean once = new AtomicBoolean();
        reader.duringCall(id -> {
            if (once.compareAndSet(false, true)) {
                clock.advance(Duration.ofSeconds(20));
                queue.enqueue(new WorkRequest(id, FIRM, Priority.PUBLISH, clock.instant(), "publish:p-8"), clock.instant());
                clock.advance(Duration.ofSeconds(20));
                queue.enqueue(new WorkRequest(id, FIRM, Priority.BACKFILL, clock.instant(), "backfill"), clock.instant());
            }
        });

        try (FanOutWorker worker = singleLaneWorker()) {
            worker.drain();
        }

        assertEquals(2, reader.calls(E1), "one re-run satisfies both newer requests");
        assertEquals(0, queue.size());
    }

    @Test
    void publishAndBackfillAlreadySatisfiedByTheInFlightReadCauseNoExtraRead() throws Exception {
        queue.enqueue(new WorkRequest(E1, FIRM, Priority.RECONCILE, T0, "audit"), T0);
        AtomicBoolean once = new AtomicBoolean();
        reader.duringCall(id -> {
            if (once.compareAndSet(false, true)) {
                clock.advance(Duration.ofSeconds(20));
                // Both were requested before this read started, and are delivered late.
                queue.enqueue(new WorkRequest(id, FIRM, Priority.PUBLISH, T0.minusSeconds(5), "publish:p-8"), clock.instant());
                queue.enqueue(new WorkRequest(id, FIRM, Priority.BACKFILL, T0, "backfill"), clock.instant());
            }
        });

        try (FanOutWorker worker = singleLaneWorker()) {
            worker.drain();
        }

        assertEquals(1, reader.calls(E1));
        assertEquals(0, queue.size());
    }

    // ---- B3 every outcome path releases its permit exactly once -----------------------------------------

    private record PermitScenario(String name, Runnable arrange, BindingProjection projection) {}

    @Test
    void everyOutcomePathReleasesItsPermitExactlyOnce() throws Exception {
        Duration timeout = Duration.ofMillis(60);
        Duration grace = Duration.ofMillis(60);
        BindingProjection throwing = new DelegatingProjection(projection) {
            @Override
            public ApplyResult applyIfNewer(EngagementTemplateState state, Instant observedAt) {
                throw new IllegalStateException("projection unavailable");
            }
        };
        List<PermitScenario> scenarios = List.of(
                new PermitScenario("loaded", () -> {}, null),
                new PermitScenario("gone", () -> reader.script(E1, new ReadOutcome.Gone("archived")), null),
                new PermitScenario("transient", () -> reader.script(E1, new ReadOutcome.TransientFailure("503")), null),
                new PermitScenario("permanent", () -> reader.script(E1, new ReadOutcome.PermanentFailure("corrupt")), null),
                new PermitScenario("throttled", () -> reader.script(E1, new ReadOutcome.Throttled(Duration.ofSeconds(5))), null),
                new PermitScenario("reader throws", () -> reader.scriptThrow(E1, new IllegalStateException("reset")), null),
                new PermitScenario("projection throws", () -> {}, throwing),
                new PermitScenario("already fresh", () -> {
                    clock.advance(Duration.ofSeconds(1));
                    projection.applyIfNewer(FakeEngagementStateReader.state(E1, 1), clock.instant());
                }, null),
                new PermitScenario("crash loop", () -> {
                    for (int i = 0; i < RETRY.maxAttempts(); i++) {
                        queue.leaseOne("doomed", LEASE, clock.instant());
                        clock.advance(LEASE.plusSeconds(1));
                    }
                }, null),
                new PermitScenario("timeout, call hangs past the grace", () -> reader.scriptHang(E1), null),
                new PermitScenario("timeout, call returns within the grace", () -> reader.duringCall(id -> sleep(90)), null));

        for (PermitScenario scenario : scenarios) {
            setUp();
            enqueue(E1, Priority.PUBLISH);
            scenario.arrange().run();
            var semaphore = new SemaphoreCapacityLimiter(2);
            var limiter = new CountingLimiter(semaphore);
            BindingProjection p = scenario.projection() == null ? projection : scenario.projection();
            try (FanOutWorker worker = worker(limiter, config(2, timeout, grace), p)) {
                worker.drain();
                sleep(timeout.plus(grace).plusMillis(150).toMillis()); // let any quarantine end
                assertEquals(limiter.acquired(), limiter.released(), scenario.name() + ": leaked a permit");
                assertEquals(0, semaphore.inUse(), scenario.name() + ": permit count off at the limiter");
                assertTrue(limiter.acquired() >= 1, scenario.name() + ": nothing ran");
            }
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private List<EngagementId> firmItems(String firm, int count) {
        List<EngagementId> ids = IntStream.range(0, count)
                .mapToObj(i -> EngagementId.of(firm + "-%03d".formatted(i))).toList();
        ids.forEach(id -> enqueue(id, firm, Priority.BACKFILL));
        return ids;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "latch not released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within 5 s");
            }
            sleep(1);
        }
    }

    /** Lets a test intercept one projection method while the rest behaves normally. */
    private static class DelegatingProjection implements BindingProjection {
        private final BindingProjection delegate;

        DelegatingProjection(BindingProjection delegate) {
            this.delegate = delegate;
        }

        @Override
        public ApplyResult applyIfNewer(EngagementTemplateState state, Instant observedAt) {
            return delegate.applyIfNewer(state, observedAt);
        }

        @Override
        public void markGone(EngagementId engagementId, Instant observedAt) {
            delegate.markGone(engagementId, observedAt);
        }

        @Override
        public Optional<Instant> confirmedAt(EngagementId engagementId) {
            return delegate.confirmedAt(engagementId);
        }
    }
}
