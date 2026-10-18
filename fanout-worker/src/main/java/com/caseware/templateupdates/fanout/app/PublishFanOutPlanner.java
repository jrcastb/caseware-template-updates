package com.caseware.templateupdates.fanout.app;

import com.caseware.templateupdates.fanout.domain.Priority;
import com.caseware.templateupdates.fanout.domain.TemplatePublished;
import com.caseware.templateupdates.fanout.domain.WorkRequest;
import com.caseware.templateupdates.fanout.port.PublishLog;
import com.caseware.templateupdates.fanout.port.TargetResolver;
import com.caseware.templateupdates.fanout.port.WorkQueue;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * First half of the fan-out: turns one {@link TemplatePublished} event into durable, coalesced work items.
 * It never calls the downstream; that is the {@link FanOutWorker}'s job, at the rate capacity allows.
 *
 * <p>Idempotency and recovery:
 * <ul>
 *   <li>Duplicate delivery of a planned publish is a no-op ({@link PublishLog}).</li>
 *   <li>A crash mid-plan resumes from the last checkpoint on redelivery. The checkpoint is written
 *       <em>after</em> a page is enqueued, so a crash between the two re-enqueues that page, which
 *       coalesces harmlessly.</li>
 *   <li>Arrival order across publishes is irrelevant: each item means "read this engagement", not
 *       "apply version X", so v7 arriving before v6 produces the same queue.</li>
 * </ul>
 */
public final class PublishFanOutPlanner {

    public record PlanResult(String publishId, boolean alreadyPlanned, int created, int coalesced) {}

    private final PublishLog publishLog;
    private final TargetResolver targets;
    private final WorkQueue queue;
    private final Clock clock;
    private final int pageSize;

    public PublishFanOutPlanner(
            PublishLog publishLog, TargetResolver targets, WorkQueue queue, Clock clock, int pageSize) {
        this.publishLog = Objects.requireNonNull(publishLog, "publishLog");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be >= 1");
        }
        this.pageSize = pageSize;
    }

    public PlanResult plan(TemplatePublished event) {
        Objects.requireNonNull(event, "event");
        PublishLog.Progress progress = publishLog.begin(event.publishId(), clock.instant());
        if (progress.status() == PublishLog.Status.PLANNED) {
            return new PlanResult(event.publishId(), true, 0, 0);
        }
        // Our own clock, not event.publishedAt(): never compare timestamps minted by different systems.
        Instant freshAfter = clock.instant();
        String reason = "publish:" + event.publishId();
        int created = 0;
        int coalesced = 0;
        Optional<String> cursor = progress.cursor();
        do {
            TargetResolver.Page page = targets.page(event, cursor, pageSize);
            for (TargetResolver.Target target : page.targets()) {
                var request = new WorkRequest(target.engagementId(), target.firmId(), Priority.PUBLISH, freshAfter, reason);
                var result = queue.enqueue(request, clock.instant());
                switch (result) {
                    case CREATED -> created++;
                    case COALESCED -> coalesced++;
                }
            }
            cursor = page.nextCursor();
            cursor.ifPresent(c -> publishLog.checkpoint(event.publishId(), c));
        } while (cursor.isPresent());
        publishLog.markPlanned(event.publishId(), clock.instant());
        return new PlanResult(event.publishId(), false, created, coalesced);
    }
}
