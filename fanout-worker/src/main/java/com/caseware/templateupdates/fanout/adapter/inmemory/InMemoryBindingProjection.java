package com.caseware.templateupdates.fanout.adapter.inmemory;

import com.caseware.templateupdates.fanout.domain.EngagementId;
import com.caseware.templateupdates.fanout.domain.EngagementTemplateState;
import com.caseware.templateupdates.fanout.port.BindingProjection;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** {@link BindingProjection} backed by {@link ConcurrentHashMap#compute}, which is atomic per key. */
public final class InMemoryBindingProjection implements BindingProjection {

    /** @param state null only if the engagement was reported gone before it was ever read */
    public record Entry(EngagementTemplateState state, Instant confirmedAt, boolean gone) {}

    private final ConcurrentHashMap<EngagementId, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public ApplyResult applyIfNewer(EngagementTemplateState state, Instant observedAt) {
        var result = new AtomicReference<ApplyResult>();
        entries.compute(state.engagementId(), (id, current) -> {
            if (current == null || current.state() == null || state.revision() > current.state().revision()) {
                result.set(ApplyResult.APPLIED);
                return new Entry(state, observedAt, false);
            }
            if (state.revision() == current.state().revision()) {
                result.set(ApplyResult.REFRESHED);
                return new Entry(current.state(), later(current.confirmedAt(), observedAt), current.gone());
            }
            result.set(ApplyResult.STALE_IGNORED);
            return current;
        });
        return result.get();
    }

    @Override
    public void markGone(EngagementId engagementId, Instant observedAt) {
        entries.compute(engagementId, (id, current) -> current == null
                ? new Entry(null, observedAt, true)
                : new Entry(current.state(), later(current.confirmedAt(), observedAt), true));
    }

    @Override
    public Optional<Instant> confirmedAt(EngagementId engagementId) {
        return find(engagementId).map(Entry::confirmedAt);
    }

    public Optional<Entry> find(EngagementId engagementId) {
        return Optional.ofNullable(entries.get(engagementId));
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
