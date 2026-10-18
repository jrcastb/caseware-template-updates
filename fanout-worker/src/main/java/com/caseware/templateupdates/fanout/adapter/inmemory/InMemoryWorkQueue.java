package com.caseware.templateupdates.fanout.adapter.inmemory;

import com.caseware.templateupdates.fanout.domain.EngagementId;
import com.caseware.templateupdates.fanout.domain.Lease;
import com.caseware.templateupdates.fanout.domain.Priority;
import com.caseware.templateupdates.fanout.domain.WorkRequest;
import com.caseware.templateupdates.fanout.port.WorkQueue;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reference implementation of the {@link WorkQueue} contract. Every operation is a single critical
 * section, which models the per-item conditional writes of the DynamoDB adapter.
 */
public final class InMemoryWorkQueue implements WorkQueue {

    public enum State { READY, LEASED, DEAD }

    /** Read-only view for tests and operator tooling. */
    public record ItemView(
            EngagementId engagementId,
            String firmId,
            State state,
            Priority priority,
            Instant freshAfter,
            Instant notBefore,
            int attempts,
            String lastReason) {}

    private static final class Item {
        final EngagementId id;
        final String firmId;
        State state = State.READY;
        Priority priority;
        Instant freshAfter;
        Instant notBefore;
        int attempts;
        long token;
        Instant leasedAt;
        Instant leaseExpiresAt;
        String lastReason;

        Item(WorkRequest request, Instant now) {
            this.id = request.engagementId();
            this.firmId = request.firmId();
            this.priority = request.priority();
            this.freshAfter = request.freshAfter();
            this.notBefore = now;
            this.lastReason = request.reason();
        }

        boolean leasableAt(Instant now) {
            return switch (state) {
                case READY -> !notBefore.isAfter(now);
                case LEASED -> !leaseExpiresAt.isAfter(now);
                case DEAD -> false;
            };
        }

        boolean liveLeaseAt(Instant now) {
            return state == State.LEASED && leaseExpiresAt.isAfter(now);
        }

        void toReady(Instant notBefore, String reason) {
            this.state = State.READY;
            this.notBefore = notBefore;
            this.lastReason = reason;
            this.leasedAt = null;
            this.leaseExpiresAt = null;
        }
    }

    /** Order of items within one firm: oldest ready first, then by id for determinism. */
    private static final Comparator<Item> WITHIN_FIRM = Comparator
            .comparing((Item item) -> item.notBefore)
            .thenComparing(item -> item.id);

    private final Map<EngagementId, Item> items = new HashMap<>();
    private final int perFirmCap;
    private long tokenSequence;
    private String lastServedFirm = "";

    /** No per-firm cap; firms are still served round-robin within a priority. */
    public InMemoryWorkQueue() {
        this(Integer.MAX_VALUE);
    }

    /** @param perFirmCap live leases one firm may hold while another firm under the cap has ready work */
    public InMemoryWorkQueue(int perFirmCap) {
        if (perFirmCap < 1) {
            throw new IllegalArgumentException("perFirmCap must be >= 1");
        }
        this.perFirmCap = perFirmCap;
    }

    @Override
    public synchronized EnqueueResult enqueue(WorkRequest request, Instant now) {
        Item item = items.get(request.engagementId());
        if (item == null) {
            items.put(request.engagementId(), new Item(request, now));
            return EnqueueResult.CREATED;
        }
        if (item.state == State.DEAD) {
            Item revived = new Item(request, now);
            revived.freshAfter = later(item.freshAfter, request.freshAfter());
            items.put(request.engagementId(), revived);
            return EnqueueResult.CREATED;
        }
        item.priority = item.priority.max(request.priority());
        item.freshAfter = later(item.freshAfter, request.freshAfter());
        return EnqueueResult.COALESCED;
    }

    @Override
    public synchronized Optional<Lease> leaseOne(String owner, Duration leaseFor, Instant now) {
        // 1. Highest priority with ready work. Priority always dominates fairness.
        Optional<Priority> top = items.values().stream()
                .filter(item -> item.leasableAt(now)).map(item -> item.priority).max(Comparator.naturalOrder());
        if (top.isEmpty()) {
            return Optional.empty();
        }
        // 2. Each firm's next item at that priority.
        TreeMap<String, Item> nextByFirm = new TreeMap<>();
        Map<String, Integer> liveLeases = new HashMap<>();
        for (Item item : items.values()) {
            if (item.liveLeaseAt(now)) {
                liveLeases.merge(item.firmId, 1, Integer::sum);
            }
            if (item.priority == top.get() && item.leasableAt(now)) {
                nextByFirm.merge(item.firmId, item, (a, b) -> WITHIN_FIRM.compare(a, b) <= 0 ? a : b);
            }
        }
        // 3. Work-conserving cap: skip firms at the cap only while some firm below it has ready work.
        NavigableSet<String> underCap = new TreeSet<>();
        for (String firm : nextByFirm.keySet()) {
            if (liveLeases.getOrDefault(firm, 0) < perFirmCap) {
                underCap.add(firm);
            }
        }
        NavigableSet<String> eligible = underCap.isEmpty() ? new TreeSet<>(nextByFirm.keySet()) : underCap;
        // 4. Round-robin: the first eligible firm after the one served last.
        String firm = Optional.ofNullable(eligible.higher(lastServedFirm)).orElse(eligible.first());
        lastServedFirm = firm;

        Item item = nextByFirm.get(firm);
        item.state = State.LEASED;
        item.attempts++;
        item.token = ++tokenSequence;
        item.leasedAt = now;
        item.leaseExpiresAt = now.plus(leaseFor);
        return Optional.of(new Lease(item.id, item.token, owner, now, item.leaseExpiresAt,
                item.attempts, item.priority, item.freshAfter));
    }

    @Override
    public synchronized boolean complete(Lease lease, Instant now) {
        Item item = heldBy(lease);
        if (item == null) {
            return false;
        }
        boolean raisedDuringLease = item.freshAfter.isAfter(lease.freshAfter())
                && item.freshAfter.isAfter(lease.leasedAt());
        if (raisedDuringLease) {
            item.attempts = 0;
            item.toReady(now, "re-run: a request newer than this read arrived during the lease");
        } else {
            items.remove(item.id);
        }
        return true;
    }

    @Override
    public synchronized boolean retryLater(Lease lease, Instant notBefore, boolean countsAsAttempt, String reason) {
        Item item = heldBy(lease);
        if (item == null) {
            return false;
        }
        if (!countsAsAttempt) {
            item.attempts = Math.max(0, item.attempts - 1);
        }
        item.toReady(notBefore, reason);
        return true;
    }

    @Override
    public synchronized boolean deadLetter(Lease lease, String reason) {
        Item item = heldBy(lease);
        if (item == null) {
            return false;
        }
        item.state = State.DEAD;
        item.lastReason = reason;
        return true;
    }

    public synchronized Optional<ItemView> view(EngagementId id) {
        return Optional.ofNullable(items.get(id)).map(InMemoryWorkQueue::toView);
    }

    public synchronized int size() {
        return items.size();
    }

    public synchronized List<EngagementId> deadLettered() {
        return items.values().stream().filter(item -> item.state == State.DEAD).map(item -> item.id).sorted().toList();
    }

    private Item heldBy(Lease lease) {
        Item item = items.get(lease.engagementId());
        boolean held = item != null && item.state == State.LEASED && item.token == lease.token();
        return held ? item : null;
    }

    private static ItemView toView(Item item) {
        return new ItemView(item.id, item.firmId, item.state, item.priority, item.freshAfter, item.notBefore,
                item.attempts, item.lastReason);
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
