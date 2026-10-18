package com.caseware.templateupdates.fanout.demo;

import com.caseware.templateupdates.fanout.adapter.inmemory.AdaptiveCapacityLimiter;
import com.caseware.templateupdates.fanout.adapter.inmemory.InMemoryBindingProjection;
import com.caseware.templateupdates.fanout.adapter.inmemory.InMemoryPublishLog;
import com.caseware.templateupdates.fanout.adapter.inmemory.InMemoryTargetResolver;
import com.caseware.templateupdates.fanout.adapter.inmemory.InMemoryWorkQueue;
import com.caseware.templateupdates.fanout.app.FanOutWorker;
import com.caseware.templateupdates.fanout.app.PublishFanOutPlanner;
import com.caseware.templateupdates.fanout.app.RetryPolicy;
import com.caseware.templateupdates.fanout.app.WorkerConfig;
import com.caseware.templateupdates.fanout.demo.SimulatedEngagementService.Kind;
import com.caseware.templateupdates.fanout.domain.EngagementId;
import com.caseware.templateupdates.fanout.domain.TemplatePublished;
import com.caseware.templateupdates.fanout.port.TargetResolver.Target;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Runs the real planner and worker end to end against in-memory adapters and a simulated Engagement
 * Service. Demo wiring only: production uses DynamoDB adapters, a regional limiter and an ECS service.
 *
 * <p>Usage: {@code FanOutDemo [engagements=300] [permits=10] [callMillis=200]}. {@code callMillis}
 * stands in for the real ~60 s call; timeouts, leases, grace and backoff are scaled from it. Firms are
 * skewed like the brief: one firm owns 40% of the engagements, the rest are small firms of 5.
 */
public final class FanOutDemo {

    private static final String BIG_FIRM = "firm-big";

    private FanOutDemo() {}

    public static void main(String[] args) throws InterruptedException {
        int engagements = intArg(args, 0, 300);
        int permits = intArg(args, 1, 10);
        int callMillis = intArg(args, 2, 200);
        Duration call = Duration.ofMillis(callMillis);
        Clock clock = Clock.systemUTC();

        int bigCount = engagements * 2 / 5;
        List<Target> suspect = IntStream.range(0, engagements)
                .mapToObj(i -> new Target(EngagementId.of("eng-%05d".formatted(i)),
                        i < bigCount ? BIG_FIRM : "firm-%04d".formatted((i - bigCount) / 5)))
                .toList();
        int perFirmCap = Math.max(1, permits / 4);
        var queue = new InMemoryWorkQueue(perFirmCap);
        var projection = new InMemoryBindingProjection();
        var planner = new PublishFanOutPlanner(new InMemoryPublishLog(),
                new InMemoryTargetResolver().register("audit-ca", "CA-2026", suspect), queue, clock, 100);
        // Timeout 5 calls, grace 3 calls; a SLOW load occupies the server for 6 calls, i.e. past the timeout.
        var config = new WorkerConfig("demo-worker", permits * 2,
                call.multipliedBy(8), call.multipliedBy(5), call.multipliedBy(3),
                new RetryPolicy(4, call.multipliedBy(2), Duration.ofSeconds(5)));
        var downstream = new SimulatedEngagementService(clock, call, call.multipliedBy(6), 42L,
                suspect.get(7).engagementId(), suspect.get(11).engagementId());
        var limiter = new AdaptiveCapacityLimiter(permits, Math.max(1, permits / 2), 1, call.multipliedBy(5), clock);

        System.out.printf("Fan-out demo: %d suspect engagements (%d in %s, rest in firms of 5), capacity grant %d, "
                + "per-firm cap %d, simulated call %d ms%n%n", engagements, bigCount, BIG_FIRM, permits, perFirmCap,
                callMillis);

        var v7 = new TemplatePublished("pub-v7", "audit-ca", "CA-2026", "v7", clock.instant());
        var v8 = new TemplatePublished("pub-v8", "audit-ca", "CA-2026", "v8", clock.instant());
        System.out.println("plan v7              -> " + planner.plan(v7));
        System.out.println("plan v7 (duplicate)  -> " + planner.plan(v7));
        System.out.println("plan v8 (overlaps)   -> " + planner.plan(v8));

        for (int i = 0; i < 3; i++) { // a previous worker leased these and died
            queue.leaseOne("crashed-worker", config.leaseDuration(), clock.instant());
        }
        System.out.printf("crashed worker holds 3 leases; they become reclaimable after %d ms%n%n",
                config.leaseDuration().toMillis());

        Instant start = clock.instant();
        AtomicBoolean finished = new AtomicBoolean();
        AtomicInteger minLimit = new AtomicInteger(Integer.MAX_VALUE);
        AtomicInteger maxLimit = new AtomicInteger();
        Thread progress = Thread.ofVirtual().start(
                () -> report(queue, downstream, limiter, engagements, start, finished, minLimit, maxLimit));
        try (var worker = new FanOutWorker(queue, downstream, projection, limiter, config, clock)) {
            worker.runUntil(() -> queue.size() == queue.deadLettered().size(), Duration.ofMillis(50));
        }
        finished.set(true);
        progress.join();
        Thread.sleep(config.timeoutGrace()); // let quarantined permits drain before reading the limiter

        Duration elapsed = Duration.between(start, clock.instant());
        System.out.printf("%nDone in %.1f s%n", elapsed.toMillis() / 1000.0);
        System.out.printf("  downstream calls    %d for %d engagements (%d loaded, %d transient, %d throttled, "
                        + "%d slower than the timeout, %d permanent, %d gone)%n",
                downstream.totalCalls(), engagements, downstream.count(Kind.LOADED), downstream.count(Kind.TRANSIENT),
                downstream.count(Kind.THROTTLED), downstream.count(Kind.SLOW), downstream.count(Kind.PERMANENT),
                downstream.count(Kind.GONE));
        System.out.printf("  max in flight       %d server-side, %d permits held (grant %d)%n",
                downstream.maxServerInFlight(), limiter.maxInUse(), permits);
        System.out.printf("  adaptive limit      ranged %d..%d (ceiling %d); permits held at end %d%n",
                minLimit.get(), maxLimit.get(), permits, limiter.inUse());
        long confirmed = suspect.stream()
                .filter(t -> projection.find(t.engagementId()).map(e -> !e.gone()).orElse(false)).count();
        long gone = suspect.stream()
                .filter(t -> projection.find(t.engagementId()).map(e -> e.gone()).orElse(false)).count();
        System.out.printf("  projection          %d confirmed, %d gone, of %d%n", confirmed, gone, engagements);
        System.out.printf("  fairness            small firms all confirmed by t=%.1f s; %s by t=%.1f s%n",
                lastConfirmed(projection, suspect, false, start), BIG_FIRM,
                lastConfirmed(projection, suspect, true, start));
        System.out.printf("  dead letters        %d%n", queue.deadLettered().size());
        for (EngagementId id : queue.deadLettered()) {
            System.out.printf("    %s: %s%n", id, queue.view(id).orElseThrow().lastReason());
        }
        double realHours = elapsed.toMillis() * (60_000.0 / callMillis) / 3_600_000.0;
        System.out.printf("  with a real ~60 s call this run would take about %.1f h at this grant%n", realHours);
    }

    private static double lastConfirmed(InMemoryBindingProjection projection, List<Target> targets, boolean big,
                                        Instant start) {
        return targets.stream()
                .filter(t -> t.firmId().equals(BIG_FIRM) == big)
                .flatMap(t -> projection.find(t.engagementId()).stream())
                .mapToLong(e -> Duration.between(start, e.confirmedAt()).toMillis())
                .max().orElse(0) / 1000.0;
    }

    private static void report(InMemoryWorkQueue queue, SimulatedEngagementService downstream,
                               AdaptiveCapacityLimiter limiter, int total, Instant start, AtomicBoolean finished,
                               AtomicInteger minLimit, AtomicInteger maxLimit) {
        long tick = 0;
        while (!finished.get()) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                return;
            }
            minLimit.accumulateAndGet(limiter.limit(), Math::min);
            maxLimit.accumulateAndGet(limiter.limit(), Math::max);
            if (++tick % 20 == 0) {
                System.out.printf("t=%4.1fs  done=%d/%d  limit=%d  permits=%d  server-in-flight=%d  dead=%d%n",
                        (System.currentTimeMillis() - start.toEpochMilli()) / 1000.0, total - queue.size(), total,
                        limiter.limit(), limiter.inUse(), downstream.serverInFlight(), queue.deadLettered().size());
            }
        }
    }

    private static int intArg(String[] args, int index, int fallback) {
        if (args.length <= index) {
            return fallback;
        }
        int value = Integer.parseInt(args[index]);
        if (value < 1) {
            throw new IllegalArgumentException("arguments must be positive: " + args[index]);
        }
        return value;
    }
}
