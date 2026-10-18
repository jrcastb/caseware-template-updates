package com.caseware.templateupdates.fanout.domain;

import static com.caseware.templateupdates.fanout.domain.PendingStatus.Flag.APPLIED_WITHDRAWN;
import static com.caseware.templateupdates.fanout.domain.PendingStatus.Flag.INCLUDES_DECLINED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.caseware.templateupdates.fanout.domain.PendingStatus.Flag;
import com.caseware.templateupdates.fanout.domain.PendingStatus.NeedsReview;
import com.caseware.templateupdates.fanout.domain.PendingStatus.Reason;
import com.caseware.templateupdates.fanout.domain.PendingStatus.UpToDate;
import com.caseware.templateupdates.fanout.domain.PendingStatus.UpdateAvailable;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Concrete histories for the rule in DESIGN.md section 4. Each test is one history a content team can
 * read and confirm; the expected value is what the user should see. "CA" is the engagement's branch.
 */
class VersionSemanticsTest {

    private static VersionGraph.Builder caChain(int from, int to) {
        VersionGraph.Builder g = VersionGraph.builder().publish("v" + from, "CA", from);
        for (int v = from + 1; v <= to; v++) {
            g.publish("v" + v, "CA", v, "v" + (v - 1));
        }
        return g;
    }

    private static PendingStatus derive(VersionGraph.Builder graph, TemplateBinding binding) {
        return VersionSemantics.derive(graph.build(), binding);
    }

    private static UpdateAvailable offer(String from, String to, Flag... flags) {
        return new UpdateAvailable(from, to, Set.of(flags));
    }

    private static UpToDate upToDate(Flag... flags) {
        return new UpToDate(Set.of(flags));
    }

    @Test
    void appliedHeadIsUpToDate() {
        assertEquals(upToDate(), derive(caChain(1, 4), TemplateBinding.on("CA", "v4")));
    }

    @Test
    void accumulatedUpdatesProduceOneCumulativeOfferToTheHead() {
        // v3 applied; v4, v5, v6 published before the user decided: one offer v3 -> v6, not three.
        assertEquals(offer("v3", "v6"), derive(caChain(1, 6), TemplateBinding.on("CA", "v3")));
    }

    @Test
    void branchesAreFollowedNeverCrossed() {
        // CA: v1 -> v2 -> v3.  EU forked from v2: e1 -> e2.
        var g = caChain(1, 3).publish("e1", "EU", 10, "v2").publish("e2", "EU", 11, "e1");
        assertEquals(offer("v2", "v3"), derive(g, TemplateBinding.on("CA", "v2")));
        assertEquals(offer("e1", "e2"), derive(g, TemplateBinding.on("EU", "e1")));
        // An EU engagement still on the fork point v2 is offered EU's head, never CA's v3.
        assertEquals(offer("v2", "e2"), derive(g, TemplateBinding.on("EU", "v2")));
    }

    @Test
    void declinedV5ThenV7IsOfferedFlaggedAsIncludingTheDeclinedChanges() {
        TemplateBinding declined = TemplateBinding.on("CA", "v4").decline("v5");
        assertEquals(upToDate(), derive(caChain(4, 5), declined), "the declined target is not re-offered");
        assertEquals(offer("v4", "v7", INCLUDES_DECLINED), derive(caChain(4, 7), declined),
                "v7 contains v5: offered, with the declined content flagged");
    }

    @Test
    void decliningTheHeadAlsoSuppressesEverythingItContains() {
        // Declined v7 while on v4: v5 and v6 are inside v7, so nothing is pending until something newer.
        assertEquals(upToDate(), derive(caChain(4, 7), TemplateBinding.on("CA", "v4").decline("v7")));
    }

    @Test
    void declinedTargetLaterWithdrawnDoesNotReofferWhatItContained() {
        TemplateBinding declined = TemplateBinding.on("CA", "v4").decline("v7");
        assertEquals(upToDate(), derive(caChain(4, 7).withdraw("v7"), declined),
                "v6 is contained in the declined v7: not re-offered");
        // v8 then replaces the withdrawn v7 (v8's parent is v6): v8 does not contain v7, so no flag.
        var replaced = caChain(4, 7).withdraw("v7").publish("v8", "CA", 8, "v6");
        assertEquals(offer("v4", "v8"), derive(replaced, declined));
    }

    @Test
    void withdrawnHeadFallsBackToTheNewestPublishedDescendant() {
        assertEquals(offer("v4", "v5"), derive(caChain(4, 6).withdraw("v6"), TemplateBinding.on("CA", "v4")));
    }

    @Test
    void appliedVersionWithdrawnIsFlaggedNotChanged() {
        // On v6, v6 withdrawn: never offered a "downgrade" to v5; the engagement is flagged.
        var g = caChain(4, 6).withdraw("v6");
        assertEquals(upToDate(APPLIED_WITHDRAWN), derive(g, TemplateBinding.on("CA", "v6")));
        // A later v7 built on v6 is offered normally, still flagged.
        assertEquals(offer("v6", "v7", APPLIED_WITHDRAWN),
                derive(g.publish("v7", "CA", 7, "v6"), TemplateBinding.on("CA", "v6")));
    }

    @Test
    void divergedLineageNeedsReviewInsteadOfAGuess() {
        // Two lines on one branch: v5 and v6 are both children of v4.
        var g = caChain(1, 4).publish("v5", "CA", 5, "v4").publish("v6", "CA", 6, "v4");
        assertEquals(Reason.DIVERGED_LINEAGE,
                ((NeedsReview) derive(g, TemplateBinding.on("CA", "v5"))).reason(),
                "on v5, the branch's newest v6 does not contain v5: never silently 'up to date'");
        assertEquals(Reason.DIVERGED_LINEAGE,
                ((NeedsReview) derive(g, TemplateBinding.on("CA", "v4"))).reason(),
                "on v4, offering v6 would silently skip v5's changes");
    }

    @Test
    void theDiagramFiveExample() {
        // DIAGRAMS.md 5: v4 applied, v5 declined, v7 withdrawn, v8 replaced it, EU fork from v5.
        var g = caChain(4, 6)
                .publish("v7", "CA", 7, "v6").withdraw("v7")
                .publish("v8", "CA", 8, "v6")
                .publish("v5-eu1", "EU-2026", 1, "v5");
        assertEquals(offer("v4", "v8", INCLUDES_DECLINED), derive(g, TemplateBinding.on("CA", "v4").decline("v5")));
    }

    @Test
    void aDeclineRecordedAgainstAnotherAppliedVersionIsIgnored() {
        // Declining pins the pair (applied, target). A stale (v3, v7) decline must not hide v7 from v4.
        var stale = new TemplateBinding("CA", "v4", new TemplateBinding.Decline("v3", "v7"));
        assertEquals(offer("v4", "v7"), derive(caChain(3, 7), stale));
    }

    @Test
    void applyingClearsTheDeclineAndDecliningRecordsThePair() {
        TemplateBinding b = TemplateBinding.on("CA", "v4").decline("v5");
        assertEquals(new TemplateBinding.Decline("v4", "v5"), b.decline());
        TemplateBinding applied = b.apply("v6");
        assertEquals(TemplateBinding.on("CA", "v6"), applied);
        assertEquals(offer("v6", "v7"), derive(caChain(4, 7), applied), "no stale 'includes declined' flag");
    }

    @Test
    void unknownVersionsNeedReview() {
        assertEquals(Reason.UNKNOWN_VERSION,
                ((NeedsReview) derive(caChain(1, 3), TemplateBinding.on("CA", "v9"))).reason());
        assertEquals(Reason.UNKNOWN_VERSION,
                ((NeedsReview) derive(caChain(1, 3), TemplateBinding.on("CA", "v1").decline("v9"))).reason());
    }

    @Test
    void graphIsBuiltFromEventsInAnyOrderAndWithdrawalIsTerminal() {
        var inOrder = VersionGraph.builder().publish("v1", "CA", 1).publish("v2", "CA", 2, "v1").withdraw("v2");
        var reordered = VersionGraph.builder().withdraw("v2").publish("v2", "CA", 2, "v1")
                .publish("v1", "CA", 1).publish("v2", "CA", 2, "v1"); // duplicate publish after withdrawal
        TemplateBinding b = TemplateBinding.on("CA", "v1");
        assertEquals(upToDate(), derive(inOrder, b));
        assertEquals(derive(inOrder, b), derive(reordered, b));
    }

    @Test
    void graphRejectsLineageItCannotTrust() {
        assertThrows(IllegalArgumentException.class,
                () -> VersionGraph.builder().publish("v2", "CA", 2, "v1").build(), "unknown parent");
        assertThrows(IllegalArgumentException.class,
                () -> VersionGraph.builder().publish("a", "X", 1, "b").publish("b", "Y", 1, "a").build(), "cycle");
        assertThrows(IllegalArgumentException.class,
                () -> VersionGraph.builder().publish("v1", "CA", 1).publish("v2", "CA", 1, "v1").build(),
                "duplicate sequence on a branch");
        assertThrows(IllegalArgumentException.class,
                () -> VersionGraph.builder().publish("v1", "CA", 1).publish("v1", "EU", 1), "conflicting publish");
    }
}
