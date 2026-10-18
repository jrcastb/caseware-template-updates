package com.caseware.templateupdates.fanout.adapter.inmemory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.caseware.templateupdates.fanout.domain.EngagementId;
import com.caseware.templateupdates.fanout.domain.EngagementTemplateState;
import com.caseware.templateupdates.fanout.port.BindingProjection.ApplyResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class InMemoryBindingProjectionTest {

    private static final Instant T0 = Instant.parse("2026-03-02T09:00:00Z");
    private static final EngagementId E1 = EngagementId.of("eng-1");

    private final InMemoryBindingProjection projection = new InMemoryBindingProjection();

    private static EngagementTemplateState revision(long revision) {
        return new EngagementTemplateState(E1, "audit-ca", "CA-2026", "v" + revision, null, revision);
    }

    @Test
    void outOfOrderObservationNeverRegressesTheBinding() {
        assertEquals(ApplyResult.APPLIED, projection.applyIfNewer(revision(5), T0.plusSeconds(10)));
        assertEquals(ApplyResult.STALE_IGNORED, projection.applyIfNewer(revision(3), T0.plusSeconds(20)));

        var entry = projection.find(E1).orElseThrow();
        assertEquals(5, entry.state().revision());
        assertEquals(T0.plusSeconds(10), entry.confirmedAt());
    }

    @Test
    void duplicateObservationOnlyMovesConfirmationForward() {
        projection.applyIfNewer(revision(5), T0.plusSeconds(10));

        assertEquals(ApplyResult.REFRESHED, projection.applyIfNewer(revision(5), T0));
        assertEquals(T0.plusSeconds(10), projection.confirmedAt(E1).orElseThrow());

        assertEquals(ApplyResult.REFRESHED, projection.applyIfNewer(revision(5), T0.plusSeconds(30)));
        assertEquals(T0.plusSeconds(30), projection.confirmedAt(E1).orElseThrow());
    }

    @Test
    void concurrentWritersConvergeOnTheHighestRevision() throws Exception {
        List<Long> revisions = new ArrayList<>(LongStream.rangeClosed(1, 200).boxed().toList());
        Collections.shuffle(revisions);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            revisions.forEach(r -> pool.submit(() -> projection.applyIfNewer(revision(r), T0)));
        }
        assertEquals(200, projection.find(E1).orElseThrow().state().revision());
    }
}
