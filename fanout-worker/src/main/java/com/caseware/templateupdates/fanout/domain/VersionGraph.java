package com.caseware.templateupdates.fanout.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable lineage of one product template's versions: a DAG, never a line. "v7 contains v5" means v5 is
 * an ancestor of v7. Ancestor sets are precomputed at build time, so every query is a set lookup; the
 * graph is tens of nodes per template (design A8), so the space is negligible.
 *
 * <p>Built from publish and withdraw events in any order and with duplicates (the Graph Sync contract):
 * publishing is an idempotent upsert, and withdrawal is terminal (design A7), so a withdraw that arrives
 * before its publish still wins. "Newest" always means the highest per-branch publish sequence, never
 * anything parsed from a version name.
 */
public final class VersionGraph {

    public enum Status { PUBLISHED, WITHDRAWN }

    public record Node(String id, String branch, long sequence, Set<String> parents, Status status) {
        public Node {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(branch, "branch");
            Objects.requireNonNull(status, "status");
            parents = Set.copyOf(parents);
        }

        public boolean published() {
            return status == Status.PUBLISHED;
        }
    }

    private final Map<String, Node> nodes;
    private final Map<String, Set<String>> ancestors;
    private final Map<String, List<Node>> publishedNewestFirst;

    private VersionGraph(Map<String, Node> nodes, Map<String, Set<String>> ancestors) {
        this.nodes = Map.copyOf(nodes);
        this.ancestors = Map.copyOf(ancestors);
        Map<String, List<Node>> byBranch = new HashMap<>();
        nodes.values().stream()
                .filter(Node::published)
                .sorted(Comparator.comparingLong(Node::sequence).reversed())
                .forEach(n -> byBranch.computeIfAbsent(n.branch(), b -> new ArrayList<>()).add(n));
        byBranch.replaceAll((b, list) -> List.copyOf(list));
        this.publishedNewestFirst = Map.copyOf(byBranch);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<Node> node(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    /** Strict: a version is not its own ancestor. */
    public boolean isAncestor(String ancestor, String of) {
        Set<String> set = ancestors.get(of);
        return set != null && set.contains(ancestor);
    }

    public boolean isAncestorOrSelf(String ancestor, String of) {
        return ancestor.equals(of) || isAncestor(ancestor, of);
    }

    /** Published (not withdrawn) versions on {@code branch}, newest first. */
    public List<Node> published(String branch) {
        return publishedNewestFirst.getOrDefault(branch, List.of());
    }

    /** Accumulates events; {@link #build} validates them and precomputes ancestor sets. Not thread-safe. */
    public static final class Builder {
        private final Map<String, Node> published = new LinkedHashMap<>();
        private final Set<String> withdrawn = new HashSet<>();

        private Builder() {}

        /** Idempotent: the same publish twice is a no-op; a conflicting one is a data error. */
        public Builder publish(String id, String branch, long sequence, String... parents) {
            Node node = new Node(id, branch, sequence, Set.of(parents), Status.PUBLISHED);
            Node previous = published.putIfAbsent(id, node);
            if (previous != null && !previous.equals(node)) {
                throw new IllegalArgumentException("conflicting publish for " + id + ": " + previous + " vs " + node);
            }
            return this;
        }

        /** Terminal, and order-independent: may arrive before the publish it withdraws. */
        public Builder withdraw(String id) {
            withdrawn.add(id);
            return this;
        }

        public VersionGraph build() {
            Map<String, Node> nodes = new HashMap<>();
            Map<String, String> branchSequence = new HashMap<>();
            for (Node n : published.values()) {
                Status status = withdrawn.contains(n.id()) ? Status.WITHDRAWN : Status.PUBLISHED;
                nodes.put(n.id(), new Node(n.id(), n.branch(), n.sequence(), n.parents(), status));
                String clash = branchSequence.put(n.branch() + "#" + n.sequence(), n.id());
                if (clash != null) {
                    throw new IllegalArgumentException("sequence " + n.sequence() + " used twice on " + n.branch());
                }
            }
            for (Node n : nodes.values()) {
                for (String p : n.parents()) {
                    Node parent = nodes.get(p);
                    if (parent == null) {
                        throw new IllegalArgumentException(n.id() + " has unknown parent " + p);
                    }
                    if (parent.branch().equals(n.branch()) && parent.sequence() >= n.sequence()) {
                        throw new IllegalArgumentException(n.id() + " is sequenced before its parent " + p);
                    }
                }
            }
            return new VersionGraph(nodes, ancestorSets(nodes));
        }

        /** Kahn's algorithm: ancestors(v) = union over parents p of {p} + ancestors(p). Rejects cycles. */
        private static Map<String, Set<String>> ancestorSets(Map<String, Node> nodes) {
            Map<String, Integer> pendingParents = new HashMap<>();
            Map<String, List<String>> children = new HashMap<>();
            for (Node n : nodes.values()) {
                pendingParents.put(n.id(), n.parents().size());
                for (String p : n.parents()) {
                    children.computeIfAbsent(p, k -> new ArrayList<>()).add(n.id());
                }
            }
            Deque<String> ready = new ArrayDeque<>();
            pendingParents.forEach((id, count) -> { if (count == 0) ready.add(id); });
            Map<String, Set<String>> ancestors = new HashMap<>();
            while (!ready.isEmpty()) {
                String id = ready.poll();
                Set<String> set = new HashSet<>();
                for (String p : nodes.get(id).parents()) {
                    set.add(p);
                    set.addAll(ancestors.get(p));
                }
                ancestors.put(id, Set.copyOf(set));
                for (String child : children.getOrDefault(id, List.of())) {
                    if (pendingParents.merge(child, -1, Integer::sum) == 0) {
                        ready.add(child);
                    }
                }
            }
            if (ancestors.size() != nodes.size()) {
                throw new IllegalArgumentException("version lineage contains a cycle");
            }
            return ancestors;
        }
    }
}
