package com.caseware.templateupdates.fanout.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.caseware.templateupdates.fanout.adapter.inmemory.InMemoryBindingProjection;
import com.caseware.templateupdates.fanout.adapter.inmemory.InMemoryPublishLog;
import com.caseware.templateupdates.fanout.adapter.inmemory.InMemoryTargetResolver;
import com.caseware.templateupdates.fanout.adapter.inmemory.InMemoryWorkQueue;
import com.caseware.templateupdates.fanout.adapter.inmemory.SemaphoreCapacityLimiter;
import com.caseware.templateupdates.fanout.domain.EngagementId;
import com.caseware.templateupdates.fanout.domain.TemplatePublished;
import com.caseware.templateupdates.fanout.port.PublishLog;
import com.caseware.templateupdates.fanout.port.TargetResolver;
import com.caseware.templateupdates.fanout.testsupport.FakeEngagementStateReader;
import com.caseware.templateupdates.fanout.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PublishFanOutPlannerTest {

    private static final Instant T0 = Instant.parse("2026-03-02T09:00:00Z");
    private static final List<EngagementId> TARGETS =
            IntStream.range(0, 25).mapToObj(i -> EngagementId.of("eng-%03d".formatted(i))).toList();
    private static final List<TargetResolver.Target> TARGETS_WITH_FIRMS =
            TARGETS.stream().map(id -> new TargetResolver.Target(id, "firm-1")).toList();
    private static final TemplatePublished V6 =
            new TemplatePublished("pub-6", "audit-ca", "CA-2026", "v6", T0);
    private static final TemplatePublished V7 =
            new TemplatePublished("pub-7", "audit-ca", "CA-2026", "v7", T0.plusSeconds(60));

    private MutableClock clock;
    private InMemoryWorkQueue queue;
    private InMemoryPublishLog log;
    private InMemoryTargetResolver resolver;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        queue = new InMemoryWorkQueue();
        log = new InMemoryPublishLog();
        resolver = new InMemoryTargetResolver().register("audit-ca", "CA-2026", TARGETS_WITH_FIRMS);
    }

    private PublishFanOutPlanner planner(TargetResolver targets) {
        return new PublishFanOutPlanner(log, targets, queue, clock, 10);
    }

    @Test
    void enqueuesEveryTargetExactlyOnceAcrossPages() {
        var result = planner(resolver).plan(V6);

        assertEquals(25, result.created());
        assertEquals(25, queue.size());
        assertEquals(PublishLog.Status.PLANNED, log.find("pub-6").orElseThrow().status());
    }

    @Test
    void duplicateDeliveryOfAPlannedPublishIsANoOp() {
        planner(resolver).plan(V6);
        var duplicate = planner(resolver).plan(V6);

        assertTrue(duplicate.alreadyPlanned());
        assertEquals(0, duplicate.created() + duplicate.coalesced());
        assertEquals(25, queue.size());
    }

    @Test
    void resumesFromTheCheckpointAfterACrashMidPlan() {
        List<Optional<String>> cursorsRequested = new CopyOnWriteArrayList<>();
        AtomicBoolean crashed = new AtomicBoolean();
        TargetResolver crashOnSecondPage = (event, cursor, limit) -> {
            cursorsRequested.add(cursor);
            if (cursor.isPresent() && crashed.compareAndSet(false, true)) {
                throw new IllegalStateException("planner process killed");
            }
            return resolver.page(event, cursor, limit);
        };

        assertThrows(IllegalStateException.class, () -> planner(crashOnSecondPage).plan(V6));
        assertEquals(10, queue.size());
        var progress = log.find("pub-6").orElseThrow();
        assertEquals(PublishLog.Status.IN_PROGRESS, progress.status());
        assertTrue(progress.cursor().isPresent());

        cursorsRequested.clear();
        var resumed = planner(crashOnSecondPage).plan(V6); // redelivery of the same event

        assertEquals(progress.cursor(), cursorsRequested.getFirst(), "must not restart from page one");
        assertEquals(15, resumed.created());
        assertEquals(25, queue.size());
        assertEquals(PublishLog.Status.PLANNED, log.find("pub-6").orElseThrow().status());
    }

    @Test
    void concurrentDuplicateDeliveriesCreateEachItemOnce() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<PublishFanOutPlanner.PlanResult>> results = IntStream.range(0, 8)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return planner(resolver).plan(V6);
                    }))
                    .toList();
            start.countDown();
            int created = 0;
            for (var result : results) {
                created += result.get().created();
            }
            assertEquals(25, created);
        }
        assertEquals(25, queue.size());
    }

    @Test
    void overlappingPublishesInAnyOrderCostOneDownstreamReadPerEngagement() throws Exception {
        var later = planner(resolver).plan(V7);   // v7's event overtakes v6's
        clock.advance(Duration.ofSeconds(5));
        var earlier = planner(resolver).plan(V6);

        assertEquals(25, later.created());
        assertEquals(0, earlier.created());
        assertEquals(25, earlier.coalesced());

        var reader = new FakeEngagementStateReader(clock);
        var config = new WorkerConfig("w", 4, Duration.ofMinutes(5), Duration.ofMinutes(3), Duration.ZERO,
                new RetryPolicy(3, Duration.ofSeconds(30), Duration.ofMinutes(10)));
        try (var worker = new FanOutWorker(queue, reader, new InMemoryBindingProjection(),
                new SemaphoreCapacityLimiter(4), config, clock, () -> 0.5)) {
            worker.drain();
        }
        assertEquals(25, reader.totalCalls());
        TARGETS.forEach(id -> assertEquals(1, reader.calls(id)));
        assertFalse(log.find("pub-7").isEmpty());
    }
}
