package com.caseware.templateupdates.fanout.domain;

import com.caseware.templateupdates.fanout.domain.PendingStatus.Flag;
import com.caseware.templateupdates.fanout.domain.PendingStatus.NeedsReview;
import com.caseware.templateupdates.fanout.domain.PendingStatus.Reason;
import com.caseware.templateupdates.fanout.domain.PendingStatus.UpToDate;
import com.caseware.templateupdates.fanout.domain.PendingStatus.UpdateAvailable;
import com.caseware.templateupdates.fanout.domain.TemplateBinding.Decline;
import com.caseware.templateupdates.fanout.domain.VersionGraph.Node;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * The rule everything depends on (DESIGN.md section 4), as one pure, I/O-free function. Pending status is
 * never stored: the Status API calls this on read, once per cohort of identical bindings.
 *
 * <pre>
 * published = versions on b.branch that are not withdrawn
 * head      = newest v in published with b.applied an ancestor of v
 * target    = head if present, else b.applied
 * REVIEW    if some v in published is not ancestor-or-self of target          (diverged lineage)
 * UP TO DATE if head is absent,
 *           or b.decline = (b.applied, d) and head is ancestor-or-self of d   (declined pin)
 * OFFER     b.applied to head, flagged INCLUDES_DECLINED if d is an ancestor of head
 * APPLIED_WITHDRAWN is flagged on any result whose b.applied is withdrawn; b itself is never changed
 * </pre>
 */
public final class VersionSemantics {

    private VersionSemantics() {}

    public static PendingStatus derive(VersionGraph graph, TemplateBinding binding) {
        Optional<Node> applied = graph.node(binding.applied());
        if (applied.isEmpty()) {
            return new NeedsReview(Reason.UNKNOWN_VERSION, "applied " + binding.applied());
        }
        // The pin is the pair (applied, target): a decline recorded against another applied version is stale.
        Optional<Decline> decline = binding.declined().filter(d -> d.from().equals(binding.applied()));
        if (decline.isPresent() && graph.node(decline.get().target()).isEmpty()) {
            return new NeedsReview(Reason.UNKNOWN_VERSION, "declined " + decline.get().target());
        }

        List<Node> published = graph.published(binding.branch());
        Optional<Node> head = published.stream()
                .filter(v -> graph.isAncestor(binding.applied(), v.id()))
                .findFirst(); // newest first
        String target = head.map(Node::id).orElse(binding.applied());
        for (Node v : published) {
            if (!graph.isAncestorOrSelf(v.id(), target)) {
                return new NeedsReview(Reason.DIVERGED_LINEAGE, v.id() + " is not contained in " + target);
            }
        }

        EnumSet<Flag> flags = EnumSet.noneOf(Flag.class);
        if (!applied.get().published()) {
            flags.add(Flag.APPLIED_WITHDRAWN);
        }
        if (head.isEmpty()) {
            return new UpToDate(flags);
        }
        String headId = head.get().id();
        if (decline.isPresent() && graph.isAncestorOrSelf(headId, decline.get().target())) {
            return new UpToDate(flags);
        }
        if (decline.isPresent() && graph.isAncestor(decline.get().target(), headId)) {
            flags.add(Flag.INCLUDES_DECLINED);
        }
        return new UpdateAvailable(binding.applied(), headId, flags);
    }
}
