package com.caseware.templateupdates.fanout.adapter.inmemory;

import com.caseware.templateupdates.fanout.port.PublishLog;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryPublishLog implements PublishLog {

    private final ConcurrentHashMap<String, Progress> records = new ConcurrentHashMap<>();

    @Override
    public Progress begin(String publishId, Instant now) {
        return records.computeIfAbsent(publishId, id -> new Progress(id, Status.IN_PROGRESS, Optional.empty()));
    }

    @Override
    public void checkpoint(String publishId, String cursor) {
        records.computeIfPresent(publishId, (id, current) -> current.status() == Status.PLANNED
                ? current
                : new Progress(id, Status.IN_PROGRESS, Optional.of(cursor)));
    }

    @Override
    public void markPlanned(String publishId, Instant now) {
        records.put(publishId, new Progress(publishId, Status.PLANNED, Optional.empty()));
    }

    public Optional<Progress> find(String publishId) {
        return Optional.ofNullable(records.get(publishId));
    }
}
