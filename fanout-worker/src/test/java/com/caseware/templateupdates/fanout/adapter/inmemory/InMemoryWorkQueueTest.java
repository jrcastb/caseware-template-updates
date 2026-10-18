package com.caseware.templateupdates.fanout.adapter.inmemory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.caseware.templateupdates.fanout.adapter.inmemory.InMemoryWorkQueue.State;
import com.caseware.templateupdates.fanout.domain.EngagementId;
import com.caseware.templateupdates.fanout.domain.Lease;
import com.caseware.templateupdates.fanout.domain.Priority;
import com.caseware.templateupdates.fanout.domain.WorkRequest;
import com.caseware.templateupdates.fanout.port.WorkQueue.EnqueueResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class InMemoryWorkQueueTest {

    private static final Instant T0 = Instant.parse("2026-03-02T09:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final EngagementId E1 = EngagementId.of("eng-1");

    private final InMemoryWorkQueue queue = new InMemoryWorkQueue();

    private static WorkRequest request(Priority priority, Instant freshAfter) {
        return new WorkRequest(E1, "firm-1", priority, freshAfter, "test");
    }

    @Test
    void coalescingKeepsOneItemWithTheHighestPriorityAndLatestFreshness() {
        assertEquals(EnqueueResult.CREATED, queue.enqueue(request(Priority.PUBLISH, T0.plusSeconds(10)), T0));
        assertEquals(EnqueueResult.COALESCED, queue.enqueue(request(Priority.BACKFILL, T0.plusSeconds(20)), T0));

        var view = queue.view(E1).orElseThrow();
        assertEquals(1, queue.size());
        assertEquals(Priority.PUBLISH, view.priority());
        assertEquals(T0.plusSeconds(20), view.freshAfter());
    }

    @Test
    void anItemHasAtMostOneLiveLease() {
        queue.enqueue(request(Priority.PUBLISH, T0), T0);

        assertTrue(queue.leaseOne("a", LEASE, T0).isPresent());
        assertTrue(queue.leaseOne("b", LEASE, T0.plusSeconds(299)).isEmpty());
        assertTrue(queue.leaseOne("b", LEASE, T0.plus(LEASE)).isPresent());
    }

    @Test
    void fencingRejectsEveryMutationFromASupersededLease() {
        queue.enqueue(request(Priority.PUBLISH, T0), T0);
        Lease first = queue.leaseOne("a", LEASE, T0).orElseThrow();
        Lease second = queue.leaseOne("b", LEASE, T0.plus(LEASE)).orElseThrow();

        assertTrue(second.token() > first.token());
        assertFalse(queue.complete(first, T0.plus(LEASE)));
        assertFalse(queue.retryLater(first, T0, true, "late"));
        assertFalse(queue.deadLetter(first, "late"));
        assertEquals(State.LEASED, queue.view(E1).orElseThrow().state());
        assertTrue(queue.complete(second, T0.plus(LEASE)));
        assertEquals(0, queue.size());
    }

    @Test
    void newDemandRevivesADeadLetteredItemWithAFreshBudget() {
        queue.enqueue(request(Priority.BACKFILL, T0), T0);
        Lease lease = queue.leaseOne("a", LEASE, T0).orElseThrow();
        queue.deadLetter(lease, "permanent");

        assertEquals(EnqueueResult.CREATED, queue.enqueue(request(Priority.INTERACTIVE, T0.plusSeconds(1)), T0));

        var view = queue.view(E1).orElseThrow();
        assertEquals(State.READY, view.state());
        assertEquals(0, view.attempts());
        assertEquals(Priority.INTERACTIVE, view.priority());
    }

    // ---- fairness across firms ---------------------------------------------------------------------------

    private static void enqueueFirm(InMemoryWorkQueue q, String firm, int count, Priority priority) {
        IntStream.range(0, count).forEach(i -> q.enqueue(
                new WorkRequest(EngagementId.of(firm + "-%03d".formatted(i)), firm, priority, T0, "test"), T0));
    }

    @Test
    void firmsAreServedRoundRobinSoABigFirmDoesNotStarveSmallOnes() {
        var q = new InMemoryWorkQueue();
        enqueueFirm(q, "a-big", 100, Priority.BACKFILL); // sorts first by id: FIFO alone would serve it first
        enqueueFirm(q, "s1", 5, Priority.BACKFILL);
        enqueueFirm(q, "s2", 5, Priority.BACKFILL);

        List<String> firstFifteen = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            Lease lease = q.leaseOne("w", LEASE, T0).orElseThrow();
            firstFifteen.add(q.view(lease.engagementId()).orElseThrow().firmId());
            q.complete(lease, T0);
        }
        assertEquals(5, firstFifteen.stream().filter("s1"::equals).count(), firstFifteen.toString());
        assertEquals(5, firstFifteen.stream().filter("s2"::equals).count(), firstFifteen.toString());
    }

    @Test
    void aFirmAtItsCapIsSkippedWhileAnotherFirmHasReadyWork() {
        var q = new InMemoryWorkQueue(2);
        enqueueFirm(q, "a-big", 100, Priority.BACKFILL);
        enqueueFirm(q, "s1", 5, Priority.BACKFILL);
        Map<String, List<Lease>> live = new HashMap<>();
        for (int i = 0; i < 4; i++) { // round-robin: a-big, s1, a-big, s1
            Lease lease = q.leaseOne("w", LEASE, T0).orElseThrow();
            live.computeIfAbsent(q.view(lease.engagementId()).orElseThrow().firmId(), f -> new ArrayList<>()).add(lease);
        }
        // The small firm's loads are quick and finish; the big firm's two are still running.
        live.get("s1").forEach(lease -> q.complete(lease, T0));

        for (int i = 0; i < 2; i++) {
            Lease next = q.leaseOne("w", LEASE, T0).orElseThrow();
            assertEquals("s1", q.view(next.engagementId()).orElseThrow().firmId(),
                    "a-big already holds its cap of 2 while s1 has ready work");
        }
    }

    @Test
    void theCapIsWorkConservingForALoneFirm() {
        var q = new InMemoryWorkQueue(2);
        enqueueFirm(q, "a-big", 100, Priority.BACKFILL);
        for (int i = 0; i < 8; i++) {
            assertTrue(q.leaseOne("w", LEASE, T0).isPresent(), "lease " + (i + 1) + " refused with only one firm");
        }
    }

    @Test
    void theCapIsWorkConservingWhenEveryFirmWithReadyWorkIsAtItsCap() {
        var q = new InMemoryWorkQueue(1);
        enqueueFirm(q, "a", 3, Priority.BACKFILL);
        enqueueFirm(q, "b", 3, Priority.BACKFILL);
        Map<String, Integer> live = new HashMap<>();
        for (int i = 0; i < 4; i++) {
            Lease lease = q.leaseOne("w", LEASE, T0).orElseThrow();
            live.merge(q.view(lease.engagementId()).orElseThrow().firmId(), 1, Integer::sum);
        }
        assertEquals(Map.of("a", 2, "b", 2), live, "spare capacity is shared round-robin, never left idle");
    }

    @Test
    void expiredLeasesDoNotCountAgainstTheFirmCap() {
        var q = new InMemoryWorkQueue(1);
        enqueueFirm(q, "a", 2, Priority.BACKFILL);
        enqueueFirm(q, "b", 2, Priority.BACKFILL);
        q.leaseOne("crashed", LEASE, T0).orElseThrow();   // firm a, then the worker died
        Lease next = q.leaseOne("w", LEASE, T0.plus(LEASE)).orElseThrow();
        assertEquals("b", q.view(next.engagementId()).orElseThrow().firmId(), "round-robin moves on to b");
        Lease reclaimed = q.leaseOne("w", LEASE, T0.plus(LEASE)).orElseThrow();
        assertEquals("a", q.view(reclaimed.engagementId()).orElseThrow().firmId(),
                "a's expired lease no longer counts, so a is under its cap again");
    }

    @Test
    void priorityDominatesFairness() {
        var q = new InMemoryWorkQueue(1);
        enqueueFirm(q, "a", 3, Priority.INTERACTIVE);
        enqueueFirm(q, "b", 3, Priority.BACKFILL);
        for (int i = 0; i < 3; i++) {
            Lease lease = q.leaseOne("w", LEASE, T0).orElseThrow();
            assertEquals(Priority.INTERACTIVE, lease.priority(), "interactive work first, even above firm a's cap");
        }
    }
}
