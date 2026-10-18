package com.caseware.templateupdates.fanout.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.caseware.templateupdates.fanout.domain.PendingStatus.Flag;
import com.caseware.templateupdates.fanout.domain.PendingStatus.UpToDate;
import com.caseware.templateupdates.fanout.domain.PendingStatus.UpdateAvailable;
import com.caseware.templateupdates.fanout.domain.VersionGraph.Node;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Property-style checks over seeded random histories: chains, forks within a branch, cross-branch forks,
 * merges and withdrawals, with bindings that apply, decline, or carry a stale decline. Seeds are fixed so a
 * failure is reproducible; the failing seed is in the message.
 */
class VersionSemanticsPropertyTest {

    private static final int HISTORIES = 3_000;
    private static final List<String> BRANCHES = List.of("CA", "EU", "US");

    /** One random history as an ordered list of events, plus a random binding to evaluate on it. */
    private record History(List<Consumer<VersionGraph.Builder>> events, List<String> versions, TemplateBinding binding) {
        VersionGraph graph() {
            VersionGraph.Builder builder = VersionGraph.builder();
            events.forEach(e -> e.accept(builder));
            return builder.build();
        }
    }

    private static History randomHistory(long seed) {
        Random r = new Random(seed);
        List<String> branches = BRANCHES.subList(0, 1 + r.nextInt(BRANCHES.size()));
        Map<String, List<String>> onBranch = new TreeMap<>();
        List<String> versions = new ArrayList<>();
        List<Consumer<VersionGraph.Builder>> events = new ArrayList<>();
        int count = 2 + r.nextInt(12);
        for (int seq = 1; seq <= count; seq++) {
            String branch = branches.get(r.nextInt(branches.size()));
            List<String> line = onBranch.computeIfAbsent(branch, b -> new ArrayList<>());
            Set<String> parents = new LinkedHashSet<>();
            if (!line.isEmpty()) {
                // usually extend the line; sometimes fork from an older version on the same branch
                parents.add(r.nextInt(100) < 80 ? line.getLast() : line.get(r.nextInt(line.size())));
            } else if (!versions.isEmpty()) {
                parents.add(versions.get(r.nextInt(versions.size()))); // a new market branches off
            }
            if (!versions.isEmpty() && r.nextInt(100) < 10) {
                parents.add(versions.get(r.nextInt(versions.size()))); // occasional merge
            }
            String id = branch + "-v" + seq;
            long sequence = seq;
            String[] parentArray = parents.toArray(String[]::new);
            events.add(b -> b.publish(id, branch, sequence, parentArray));
            if (r.nextInt(100) < 15) {
                events.add(b -> b.withdraw(id));
            }
            line.add(id);
            versions.add(id);
        }
        String branch = branches.get(r.nextInt(branches.size()));
        String applied = versions.get(r.nextInt(versions.size()));
        TemplateBinding binding = switch (r.nextInt(3)) {
            case 0 -> TemplateBinding.on(branch, applied);
            case 1 -> TemplateBinding.on(branch, applied).decline(versions.get(r.nextInt(versions.size())));
            default -> new TemplateBinding(branch, applied, new TemplateBinding.Decline(
                    versions.get(r.nextInt(versions.size())), versions.get(r.nextInt(versions.size()))));
        };
        return new History(events, versions, binding);
    }

    @Test
    void applyingTheOfferLeavesNothingPendingAndTheOfferIsAlwaysValid() {
        int offers = 0;
        for (long seed = 1; seed <= HISTORIES; seed++) {
            History h = randomHistory(seed);
            VersionGraph g = h.graph();
            if (!(VersionSemantics.derive(g, h.binding()) instanceof UpdateAvailable offer)) {
                continue;
            }
            offers++;
            Node to = g.node(offer.to()).orElseThrow();
            String context = "seed " + seed + ": " + offer;
            assertTrue(to.published(), context + " offers a withdrawn version");
            assertEquals(h.binding().branch(), to.branch(), context + " crosses branches");
            assertTrue(g.isAncestor(h.binding().applied(), offer.to()), context + " is not a descendant of applied");

            PendingStatus after = VersionSemantics.derive(g, h.binding().apply(offer.to()));
            assertTrue(after instanceof UpToDate, context + " then apply gives " + after);
            assertFalse(((UpToDate) after).flags().contains(Flag.INCLUDES_DECLINED), context);
        }
        assertTrue(offers > HISTORIES / 10, "generator produced too few offers to be meaningful: " + offers);
    }

    @Test
    void upToDateNeverHidesPublishedContentOutsideTheAppliedOrDeclinedVersion() {
        // The safety property behind "declining never hides content newer than the declined target":
        // if we say Up to date, every published version on the branch is inside applied, or inside the
        // target the user declined from that applied version.
        int upToDate = 0;
        int pinned = 0;
        for (long seed = 1; seed <= HISTORIES; seed++) {
            History h = randomHistory(seed);
            VersionGraph g = h.graph();
            TemplateBinding b = h.binding();
            if (!(VersionSemantics.derive(g, b) instanceof UpToDate)) {
                continue;
            }
            upToDate++;
            String declined = b.declined().filter(d -> d.from().equals(b.applied()))
                    .map(TemplateBinding.Decline::target).orElse(null);
            for (Node v : g.published(b.branch())) {
                boolean inApplied = g.isAncestorOrSelf(v.id(), b.applied());
                boolean inDeclined = declined != null && g.isAncestorOrSelf(v.id(), declined);
                assertTrue(inApplied || inDeclined,
                        "seed " + seed + ": " + v.id() + " hidden from " + b + " by an 'Up to date'");
                if (!inApplied) {
                    pinned++;
                }
            }
        }
        assertTrue(upToDate > HISTORIES / 10 && pinned > 0,
                "generator did not exercise the property: upToDate=" + upToDate + " pinned=" + pinned);
    }

    @Test
    void statusDoesNotDependOnTheOrderEventsArrived() {
        for (long seed = 1; seed <= HISTORIES; seed++) {
            History h = randomHistory(seed);
            PendingStatus expected = VersionSemantics.derive(h.graph(), h.binding());
            List<Consumer<VersionGraph.Builder>> shuffled = new ArrayList<>(h.events());
            shuffled.addAll(h.events().subList(0, h.events().size() / 2)); // duplicates, as from at-least-once delivery
            Collections.shuffle(shuffled, new Random(seed * 31));
            VersionGraph.Builder builder = VersionGraph.builder();
            shuffled.forEach(e -> e.accept(builder));
            assertEquals(expected, VersionSemantics.derive(builder.build(), h.binding()), "seed " + seed);
        }
    }
}
