package com.caseware.templateupdates.fanout.port;

import com.caseware.templateupdates.fanout.domain.EngagementId;
import com.caseware.templateupdates.fanout.domain.ReadOutcome;

/**
 * Port to the Engagement Service operation that loads (rehydrates) an engagement and returns its
 * template binding. Owned by another team; capacity is limited and granted to us.
 *
 * <p>Contract:
 * <ul>
 *   <li>Blocking and slow: ~60 s per call. Callers must hold a {@link CapacityLimiter} permit for the whole
 *       call; this port does not limit itself.</li>
 *   <li>Side-effect free from the caller's point of view. Calling it twice for the same engagement is safe
 *       but wastes scarce capacity, so de-duplication is a capacity concern, not a correctness one.</li>
 *   <li>Expected failures are returned as {@link ReadOutcome} values, not thrown. Implementations should
 *       honour thread interruption so the caller can enforce a timeout.</li>
 * </ul>
 */
@FunctionalInterface
public interface EngagementStateReader {
    ReadOutcome load(EngagementId engagementId) throws InterruptedException;
}
