package com.caseware.templateupdates.fanout.app;

import com.caseware.templateupdates.fanout.domain.EngagementId;
import com.caseware.templateupdates.fanout.domain.Lease;
import com.caseware.templateupdates.fanout.domain.ReadOutcome;
import com.caseware.templateupdates.fanout.domain.ReadOutcome.Gone;
import com.caseware.templateupdates.fanout.domain.ReadOutcome.Loaded;
import com.caseware.templateupdates.fanout.domain.ReadOutcome.PermanentFailure;
import com.caseware.templateupdates.fanout.domain.ReadOutcome.Throttled;
import com.caseware.templateupdates.fanout.domain.ReadOutcome.TransientFailure;
import com.caseware.templateupdates.fanout.port.BindingProjection;
import com.caseware.templateupdates.fanout.port.CapacityLimiter;
import com.caseware.templateupdates.fanout.port.CapacityLimiter.Feedback;
import com.caseware.templateupdates.fanout.port.EngagementStateReader;
import com.caseware.templateupdates.fanout.port.WorkQueue;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

/**
 * Second half of the fan-out: drains the {@link WorkQueue} by calling the slow, capacity-limited
 * Engagement Service and writing results into the {@link BindingProjection}.
 *
 * <p>Per lane (a virtual thread): check the shared admission pause, acquire a capacity permit, lease one
 * item (the queue picks the firm, round-robin), process it, release the permit. Permit-before-lease means
 * an item's lease never ages while it waits for capacity. Virtual threads fit because each call blocks for
 * ~1 minute; the limiter, not a thread pool, bounds load.
 *
 * <p>Guarantees: at most the limiter's grant of permits held at once, and a permit stays held while a
 * timed-out call may still be running downstream (quarantine); every permit is released exactly once;
 * after a throttle no lane starts a call until Retry-After; at most one live lease per engagement; every
 * outcome is recorded under a fencing token; projection writes are revision-guarded, so a worker that lost
 * its lease can do no harm even if it writes anyway.
 */
public final class FanOutWorker implements AutoCloseable {

    /**
     * What one drain did. {@code pausedUntil} is present when admission is paused by a throttle.
     */
    public record DrainResult(int processed, int downstreamCalls, int throttled, Optional<Instant> pausedUntil) {}

    private static final System.Logger LOG = System.getLogger(FanOutWorker.class.getName());

    private final WorkQueue queue;
    private final EngagementStateReader reader;
    private final BindingProjection projection;
    private final CapacityLimiter limiter;
    private final WorkerConfig config;
    private final Clock clock;
    private final DoubleSupplier jitter;
    private final AdmissionPause pause = new AdmissionPause();
    private final ExecutorService callExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public FanOutWorker(
            WorkQueue queue,
            EngagementStateReader reader,
            BindingProjection projection,
            CapacityLimiter limiter,
            WorkerConfig config,
            Clock clock,
            DoubleSupplier jitter) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.projection = Objects.requireNonNull(projection, "projection");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
    }

    public FanOutWorker(
            WorkQueue queue,
            EngagementStateReader reader,
            BindingProjection projection,
            CapacityLimiter limiter,
            WorkerConfig config,
            Clock clock) {
        this(queue, reader, projection, limiter, config, clock, () -> ThreadLocalRandom.current().nextDouble());
    }

    /**
     * Production loop. Each lane runs independently until {@code stopRequested}: a lane that finds no ready
     * work sleeps {@code idlePause}; a lane that finds admission paused sleeps exactly until Retry-After.
     * Lanes do not wait for each other, so a throttle stops admission for Retry-After, not for as long as the
     * slowest call still in flight (which is what a drain-at-a-time loop would do).
     */
    public void runUntil(BooleanSupplier stopRequested, Duration idlePause) throws InterruptedException {
        var stats = new Stats();
        try (ExecutorService lanes = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> running = new ArrayList<>();
            for (int i = 0; i < config.lanes(); i++) {
                running.add(lanes.submit(() -> {
                    while (!stopRequested.getAsBoolean()) {
                        switch (runLane(stats)) {
                            case IDLE -> Thread.sleep(idlePause);
                            case PAUSED -> sleepUntilAdmissionResumes();
                        }
                    }
                    return null;
                }));
            }
            awaitLanes(running);
        }
    }

    /**
     * Processes ready items until none is ready or admission is paused. Items in backoff are not waited
     * for. Returns when every lane has stopped. Safe to call from several workers concurrently against the
     * same queue.
     */
    public DrainResult drain() throws InterruptedException {
        var stats = new Stats();
        try (ExecutorService lanes = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> running = new ArrayList<>();
            for (int i = 0; i < config.lanes(); i++) {
                running.add(lanes.submit(() -> runLane(stats)));
            }
            awaitLanes(running);
        }
        return stats.snapshot(pause.activeAt(clock.instant()));
    }

    private static void awaitLanes(List<Future<?>> running) throws InterruptedException {
        for (Future<?> lane : running) {
            try {
                lane.get();
            } catch (ExecutionException e) {
                // process() contains every expected failure; reaching here is a bug, so surface it loudly.
                throw new IllegalStateException("fan-out lane failed", e.getCause());
            }
        }
    }

    private void sleepUntilAdmissionResumes() throws InterruptedException {
        Instant now = clock.instant();
        Duration wait = pause.activeAt(now).map(until -> Duration.between(now, until)).orElse(Duration.ZERO);
        Thread.sleep(wait.compareTo(Duration.ofMillis(1)) > 0 ? wait : Duration.ofMillis(1));
    }

    /** Why a lane stopped: nothing ready, or admission paused by a throttle. */
    private enum LaneExit { IDLE, PAUSED }

    private LaneExit runLane(Stats stats) throws InterruptedException {
        while (pause.activeAt(clock.instant()).isEmpty()) {
            HeldPermit permit = new HeldPermit(limiter.acquire());
            try {
                if (pause.activeAt(clock.instant()).isPresent()) {
                    return LaneExit.PAUSED; // throttled while we waited for capacity
                }
                Optional<Lease> lease = queue.leaseOne(config.workerId(), config.leaseDuration(), clock.instant());
                if (lease.isEmpty()) {
                    return LaneExit.IDLE;
                }
                stats.processed();
                process(lease.get(), permit, stats);
            } finally {
                permit.releaseUnlessQuarantined();
            }
        }
        return LaneExit.PAUSED;
    }

    private void process(Lease lease, HeldPermit permit, Stats stats) {
        try {
            if (lease.attempt() > config.retry().maxAttempts()) {
                // Earlier leases expired without an outcome: the item may be crashing workers. Stop feeding it.
                record(lease, queue.deadLetter(lease, "retry budget consumed by expired leases (possible crash loop)"));
                return;
            }
            if (alreadyFresh(lease)) {
                // e.g. the user opened the engagement while this item waited; the open emitted a fresh binding.
                record(lease, queue.complete(lease, clock.instant()));
                return;
            }
            Optional<Instant> paused = pause.activeAt(clock.instant());
            if (paused.isPresent()) {
                // Another lane was throttled after we leased: do not start a call. Not the item's fault.
                record(lease, queue.retryLater(lease, paused.get(), false, "admission paused after throttle"));
                return;
            }
            stats.called();
            handle(lease, callWithTimeout(lease.engagementId(), permit), permit, stats);
        } catch (RuntimeException unexpected) {
            LOG.log(Level.ERROR, "unexpected failure processing " + lease.engagementId(), unexpected);
            retryOrDeadLetter(lease, "unexpected: " + unexpected);
        }
    }

    private void handle(Lease lease, ReadOutcome outcome, HeldPermit permit, Stats stats) {
        Instant now = clock.instant();
        switch (outcome) {
            case Loaded loaded -> {
                permit.report(Feedback.SUCCESS);
                // Written even if our lease was lost: applyIfNewer makes a late write harmless.
                projection.applyIfNewer(loaded.state(), loaded.observedAt());
                record(lease, queue.complete(lease, now));
            }
            case Gone gone -> {
                permit.report(Feedback.SUCCESS);
                projection.markGone(lease.engagementId(), now);
                record(lease, queue.complete(lease, now));
            }
            case Throttled throttled -> {
                stats.throttled();
                permit.report(Feedback.OVERLOAD);
                // Pause every lane BEFORE returning the item, so no lane can observe the item back without the pause.
                pause.pauseUntil(now.plus(throttled.retryAfter()));
                record(lease, queue.retryLater(lease, now.plus(throttled.retryAfter()), false, "throttled"));
            }
            case TransientFailure failure -> {
                permit.report(permit.quarantined() ? Feedback.OVERLOAD : Feedback.NEUTRAL);
                retryOrDeadLetter(lease, failure.reason());
            }
            case PermanentFailure failure -> {
                permit.report(Feedback.SUCCESS);
                record(lease, queue.deadLetter(lease, failure.reason()));
            }
        }
    }

    private boolean alreadyFresh(Lease lease) {
        return projection.confirmedAt(lease.engagementId())
                .map(confirmed -> !confirmed.isBefore(lease.freshAfter()))
                .orElse(false);
    }

    private ReadOutcome callWithTimeout(EngagementId id, HeldPermit permit) {
        Future<ReadOutcome> call = callExecutor.submit(() -> reader.load(id));
        try {
            return Objects.requireNonNull(
                    call.get(config.callTimeout().toMillis(), TimeUnit.MILLISECONDS), "reader returned null");
        } catch (TimeoutException e) {
            quarantine(permit, call);
            return new TransientFailure("timeout after " + config.callTimeout());
        } catch (ExecutionException e) {
            return new TransientFailure("reader threw " + e.getCause());
        } catch (InterruptedException e) {
            quarantine(permit, call);
            Thread.currentThread().interrupt();
            return new TransientFailure("worker interrupted");
        }
    }

    /**
     * We stopped waiting, but the downstream may still be loading the file. Keep the permit occupied until
     * the abandoned call returns (it finished downstream) or the grace elapses (then interrupt it), so true
     * downstream concurrency stays within the grant. The permit is released exactly once, by the watcher.
     */
    private void quarantine(HeldPermit permit, Future<ReadOutcome> call) {
        CapacityLimiter.Permit held = permit.quarantine();
        try {
            callExecutor.submit(() -> {
                try {
                    call.get(config.timeoutGrace().toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException | ExecutionException | InterruptedException ignored) {
                    // finished, failed or gave up: in every case the call is over for capacity purposes
                } finally {
                    call.cancel(true);
                    held.close();
                }
                return null;
            });
        } catch (RejectedExecutionException shuttingDown) {
            call.cancel(true);
            held.close();
        }
    }

    private void retryOrDeadLetter(Lease lease, String reason) {
        RetryPolicy retry = config.retry();
        if (retry.isExhaustedAfter(lease.attempt())) {
            record(lease, queue.deadLetter(lease, "retries exhausted: " + reason));
        } else {
            Duration delay = retry.backoffAfter(lease.attempt(), jitter.getAsDouble());
            record(lease, queue.retryLater(lease, clock.instant().plus(delay), true, reason));
        }
    }

    private void record(Lease lease, boolean stillHeld) {
        if (!stillHeld) {
            LOG.log(Level.WARNING, "lease lost for {0} (token {1}); outcome superseded by a newer lease",
                    lease.engagementId(), lease.token());
        }
    }

    @Override
    public void close() {
        callExecutor.shutdownNow();
    }

    /** A lane's permit: released by the lane, unless ownership moved to a quarantine watcher. */
    private static final class HeldPermit {
        private final CapacityLimiter.Permit permit;
        private boolean quarantined;

        HeldPermit(CapacityLimiter.Permit permit) {
            this.permit = permit;
        }

        void report(Feedback feedback) {
            permit.report(feedback);
        }

        boolean quarantined() {
            return quarantined;
        }

        CapacityLimiter.Permit quarantine() {
            quarantined = true;
            return permit;
        }

        void releaseUnlessQuarantined() {
            if (!quarantined) {
                permit.close();
            }
        }
    }

    private static final class Stats {
        private int processed;
        private int calls;
        private int throttled;

        synchronized void processed() { processed++; }
        synchronized void called() { calls++; }
        synchronized void throttled() { throttled++; }

        synchronized DrainResult snapshot(Optional<Instant> pausedUntil) {
            return new DrainResult(processed, calls, throttled, pausedUntil);
        }
    }
}
