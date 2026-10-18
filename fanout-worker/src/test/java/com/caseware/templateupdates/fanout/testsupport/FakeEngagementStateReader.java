package com.caseware.templateupdates.fanout.testsupport;

import com.caseware.templateupdates.fanout.domain.EngagementId;
import com.caseware.templateupdates.fanout.domain.EngagementTemplateState;
import com.caseware.templateupdates.fanout.domain.ReadOutcome;
import com.caseware.templateupdates.fanout.port.EngagementStateReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Stand-in for the Engagement Service. Records concurrency and call order, and can be scripted per
 * engagement. Unscripted calls succeed with revision 1.
 */
public final class FakeEngagementStateReader implements EngagementStateReader {

    private final Clock clock;
    private final Map<EngagementId, Deque<BiFunction<EngagementId, Instant, ReadOutcome>>> scripts =
            new ConcurrentHashMap<>();
    private final Map<EngagementId, AtomicInteger> callsById = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<EngagementId> callOrder = new ConcurrentLinkedQueue<>();
    private final Map<EngagementId, Long> firstStartNanos = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Long> startNanos = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private volatile Duration realDelay = Duration.ZERO;
    private volatile Consumer<EngagementId> duringCall = id -> {};

    public FakeEngagementStateReader(Clock clock) {
        this.clock = clock;
    }

    public static EngagementTemplateState state(EngagementId id, long revision) {
        return new EngagementTemplateState(id, "audit-ca", "CA-2026", "v4", null, revision);
    }

    public FakeEngagementStateReader withRealDelay(Duration delay) {
        this.realDelay = delay;
        return this;
    }

    public FakeEngagementStateReader duringCall(Consumer<EngagementId> hook) {
        this.duringCall = hook;
        return this;
    }

    /** Queues outcomes returned by successive calls for {@code id}; then falls back to success. */
    public FakeEngagementStateReader script(EngagementId id, ReadOutcome... outcomes) {
        var queue = scripts.computeIfAbsent(id, k -> new ArrayDeque<>());
        synchronized (queue) {
            for (ReadOutcome outcome : outcomes) {
                queue.add((ignored, start) -> outcome);
            }
        }
        return this;
    }

    public FakeEngagementStateReader scriptLoaded(EngagementId id, long revision) {
        var queue = scripts.computeIfAbsent(id, k -> new ArrayDeque<>());
        synchronized (queue) {
            queue.add((eid, start) -> new ReadOutcome.Loaded(state(eid, revision), start));
        }
        return this;
    }

    /** The next call for {@code id} blocks until interrupted (a hung downstream). */
    public FakeEngagementStateReader scriptHang(EngagementId id) {
        var queue = scripts.computeIfAbsent(id, k -> new ArrayDeque<>());
        synchronized (queue) {
            queue.add((eid, start) -> {
                try {
                    Thread.sleep(Duration.ofHours(1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new ReadOutcome.TransientFailure("interrupted");
            });
        }
        return this;
    }

    /** The next call for {@code id} throws, like a buggy client library. */
    public FakeEngagementStateReader scriptThrow(EngagementId id, RuntimeException exception) {
        var queue = scripts.computeIfAbsent(id, k -> new ArrayDeque<>());
        synchronized (queue) {
            queue.add((eid, start) -> { throw exception; });
        }
        return this;
    }

    @Override
    public ReadOutcome load(EngagementId id) throws InterruptedException {
        Instant start = clock.instant();
        int current = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(current, Math::max);
        try {
            callsById.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
            callOrder.add(id);
            long started = System.nanoTime();
            firstStartNanos.putIfAbsent(id, started);
            startNanos.add(started);
            duringCall.accept(id);
            if (!realDelay.isZero()) {
                Thread.sleep(realDelay);
            }
            BiFunction<EngagementId, Instant, ReadOutcome> next = null;
            var queue = scripts.get(id);
            if (queue != null) {
                synchronized (queue) {
                    next = queue.poll();
                }
            }
            return next != null ? next.apply(id, start) : new ReadOutcome.Loaded(state(id, 1), start);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /** Real time ({@link System#nanoTime}) at which the first call for {@code id} started. */
    public long firstStartNanos(EngagementId id) {
        Long nanos = firstStartNanos.get(id);
        if (nanos == null) {
            throw new IllegalStateException("never called: " + id);
        }
        return nanos;
    }

    /** Real start time of every call, in call order. */
    public List<Long> startNanos() {
        return List.copyOf(startNanos);
    }

    public int calls(EngagementId id) {
        AtomicInteger count = callsById.get(id);
        return count == null ? 0 : count.get();
    }

    public int totalCalls() {
        return callOrder.size();
    }

    public int maxInFlight() {
        return maxInFlight.get();
    }

    public int inFlight() {
        return inFlight.get();
    }

    public List<EngagementId> callOrder() {
        return List.copyOf(callOrder);
    }

    public Map<EngagementId, Integer> callCounts() {
        Map<EngagementId, Integer> counts = new ConcurrentHashMap<>();
        callsById.forEach((id, n) -> counts.put(id, n.get()));
        return counts;
    }
}
