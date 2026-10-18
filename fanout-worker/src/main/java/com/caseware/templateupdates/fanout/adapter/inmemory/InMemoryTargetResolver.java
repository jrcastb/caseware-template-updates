package com.caseware.templateupdates.fanout.adapter.inmemory;

import com.caseware.templateupdates.fanout.domain.EngagementId;
import com.caseware.templateupdates.fanout.domain.TemplatePublished;
import com.caseware.templateupdates.fanout.port.TargetResolver;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Keyset-paginated resolver: the cursor is the last engagement id returned, so pages stay stable even
 * if engagements are added or removed while a plan is in progress (the DynamoDB adapter uses
 * {@code ExclusiveStartKey} the same way).
 */
public final class InMemoryTargetResolver implements TargetResolver {

    private final Map<String, NavigableMap<EngagementId, String>> byTemplateBranch = new ConcurrentHashMap<>();

    public InMemoryTargetResolver register(String templateId, String branch, List<Target> targets) {
        var map = byTemplateBranch.computeIfAbsent(key(templateId, branch), k -> new ConcurrentSkipListMap<>());
        targets.forEach(t -> map.put(t.engagementId(), t.firmId()));
        return this;
    }

    @Override
    public Page page(TemplatePublished event, Optional<String> cursor, int limit) {
        NavigableMap<EngagementId, String> all = byTemplateBranch.getOrDefault(
                key(event.templateId(), event.branch()), new ConcurrentSkipListMap<>());
        NavigableMap<EngagementId, String> remaining = cursor
                .map(c -> all.tailMap(EngagementId.of(c), false))
                .orElse(all);
        List<Target> targets = remaining.entrySet().stream().limit(limit)
                .map(e -> new Target(e.getKey(), e.getValue())).toList();
        boolean more = !targets.isEmpty() && remaining.size() > targets.size();
        return new Page(targets, more ? Optional.of(targets.getLast().engagementId().value()) : Optional.empty());
    }

    private static String key(String templateId, String branch) {
        return templateId + "#" + branch;
    }
}
