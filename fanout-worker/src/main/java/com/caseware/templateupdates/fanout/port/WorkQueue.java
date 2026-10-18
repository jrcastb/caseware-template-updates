package com.caseware.templateupdates.fanout.port;

import com.caseware.templateupdates.fanout.domain.Lease;
import com.caseware.templateupdates.fanout.domain.WorkRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Durable, regional queue of engagement reads, with at most one item per engagement.
 *
 * <p>Contract:
 * <ul>
 *   <li><b>Coalescing.</b> {@link #enqueue} for an engagement that already has an item merges into it:
 *       priority and freshness requirement become the maximum of the two. It never creates a second item.</li>
 *   <li><b>Leases.</b> {@link #leaseOne} returns an item of the highest priority that is ready
 *       ({@code notBefore <= now}) or whose previous lease has expired. Each lease carries a new fencing
 *       token and increments the item's attempt count, so a crash loop is visible as a growing attempt.</li>
 *   <li><b>Fairness across firms.</b> Within that priority, firms are served round-robin, and a firm that
 *       already holds its per-firm cap of live leases is skipped while any firm under the cap has ready
 *       work. The cap is work-conserving: when every firm with ready work is at its cap (including the
 *       case of a single firm), the item is still leased. Callers hold a capacity permit, so returning
 *       empty while work is ready would leave granted capacity idle; {@code leaseOne} returns empty only
 *       when nothing is ready.</li>
 *   <li><b>Fencing.</b> {@link #complete}, {@link #retryLater} and {@link #deadLetter} return {@code false}
 *       and change nothing if the item has since been leased to someone else (token mismatch).</li>
 *   <li><b>Re-run on raise.</b> If a request arrived during the lease whose {@code freshAfter} is later than
 *       the lease's {@code leasedAt}, {@link #complete} puts the item back to ready instead of removing it,
 *       because the read just performed may predate what the new requester needs.</li>
 *   <li><b>Revival.</b> A new request for a dead-lettered engagement revives it with a fresh retry budget:
 *       new demand is new evidence, and the dead letter has already alerted an operator.</li>
 * </ul>
 * Production adapter: DynamoDB table keyed by engagement id, conditional updates on the lease token, a
 * sparse GSI over ready items keyed by (priority, firm) and ordered by notBefore, and a per-firm live-lease
 * counter updated in the same transaction as the lease.
 */
public interface WorkQueue {

    enum EnqueueResult { CREATED, COALESCED }

    EnqueueResult enqueue(WorkRequest request, Instant now);

    Optional<Lease> leaseOne(String owner, Duration leaseFor, Instant now);

    boolean complete(Lease lease, Instant now);

    /**
     * Returns the item to the ready set no earlier than {@code notBefore}.
     *
     * @param countsAsAttempt {@code false} refunds the attempt consumed by this lease (used for throttling,
     *                        which says nothing about whether the item itself is healthy)
     */
    boolean retryLater(Lease lease, Instant notBefore, boolean countsAsAttempt, String reason);

    boolean deadLetter(Lease lease, String reason);
}
