# Pending Template Updates: Design

Diagrams: [DIAGRAMS.md](DIAGRAMS.md). Part 2: [fanout-worker/](fanout-worker/README.md). Assumptions A1–A12: §7. `verification/check_numbers.py` recomputes every figure.

## 1. The central decision

A load takes a minute and a publish touches tens of thousands of engagements, so loading on publish cannot be real time. Instead, keep a small, regional, queryable copy of each engagement's **template binding** (template, branch, applied version, last decline, revision), captured by hooks whenever the Engagement Service already has the engagement loaded (create, open, decision). Pending status is a pure function of that binding and the template version graph, computed on read. A publish is one small graph write, visible in seconds; errors are fixed by recomputing, not by replaying a fan-out. The one-minute load only establishes or repairs bindings, through the capacity-limited worker of Part 2.

## 2. What the numbers say

| Given | Arithmetic | Consequence |
|---|---|---|
| 800k engagements, 40 products | ≈ 20k engagements per product | A naive publish touches ~20k engagements |
| ~1 min per load (hard) | 20k load-minutes; at 50 concurrent (A5) ≈ 6.7 h | Seconds are unreachable by loading |
| ~1 publish/week/product | 800k load-min/week ÷ 10,080 min/week | ≈ 79 loads in flight forever; 3.5M loads/month |
| Largest firm 40k | 5% of fleet; 40k ÷ 50 = 800 min = 13.3 h of the grant | Backfill needs fairness; list views need an index |
| Template content not firm-specific | Summary depends only on (template, from, to) | Scales with publishes; never sees client data |

## 3. Architecture and data ownership

**Global, template-only**: the existing Template Store; a **Version Graph** (parents, branch, per-branch sequence, published/withdrawn) in a DynamoDB global table, maintained by a Graph Sync Lambda from a publish/withdraw hook on Amazon EventBridge; a **Summary Service** (Lambda via SQS, Amazon Bedrock) writing immutable summaries to S3 with Object Lock. **Per residency region** (US, EU, Canada; A10): a **Binding Projection** with per-firm **cohort counts** (one DynamoDB table) fed by a Projector Lambda from a regional EventBridge bus; a **Status API** (API Gateway, Lambda); the Part 2 **Planner** (Lambda) and **Fan-out Worker** (ECS Fargate) with a DynamoDB work table. Only template data crosses regions: graph replication and a cross-region rule delivering template events to Planners.

| Data | Source of truth | New copy | Lives in |
|---|---|---|---|
| Versions, lineage, withdrawals | Template Store | Version Graph (rebuildable) | Global, replicated |
| Summaries, provenance | Summary Service | S3, immutable | Global |
| Applied version, decisions | The engagement | Projection + cohort counts (rebuildable) | Firm's region |
| "Has pending update" | Nobody | none: derived on read | — |
| Decision evidence | The engagement file | Decision log by summary id, in the projection table | Firm's region |

A loaded engagement always beats the projection. Decisions go to the Engagement Service as today, naming the **target version and summary id** shown; it records them, with the exact text's hash, in the engagement a regulator reviews.

## 4. Correctness

**Version semantics.** Versions form a per-template DAG; "v7 contains v5" means v5 is an ancestor of v7. One pure function, `VersionSemantics.derive`, tested in Part 2:

```
published  = versions on b.branch not withdrawn;  "newest" = highest branch sequence
head       = newest v in published with b.applied an ancestor of v
target     = head if present, else b.applied
NEEDS REVIEW  if some v in published is not ancestor-or-self of target     (diverged lineage)
UP TO DATE    if head is absent, or b.decline = (b.applied, d) and head is ancestor-or-self of d
OFFER         b.applied → head, flagged "includes changes you declined" if d is an ancestor of head
flag "applied version withdrawn" whenever b.applied is withdrawn; never change b automatically
apply(t): applied = t, decline = none;   decline(t): decline = (applied, t);   both revision + 1
```

Accumulated updates produce **one** offer (A2). **Declining pins the pair (applied, target)**: that target and everything it contains stop being offered; newer content is offered again with the declined part flagged ("declined v5, later offered v7"). A decline recorded against another applied version is ignored. A withdrawn head falls back to the newest published descendant; a withdrawn declined v7 does not re-offer the v6 it contained. If a published version on the branch is not contained in the target, lineage has diverged: the function refuses to guess, the UI shows *Checking*, and the content team declares a migration edge (A6).

**Derived, not pushed.** A publish writes nothing per engagement. Graph updates are idempotent upserts and withdrawal is terminal (A7), so event order is irrelevant (tested). Graph Sync also lists the Template Store every 5 minutes, repairing missed hooks.

**Unreliable engagement events.** `BindingObserved` is emitted via a transactional outbox in the customer database (A3), at-least-once, with a per-engagement **revision**. Projection writes are conditional on `revision >` stored (equal refreshes the confirmation time), so duplicates and reordering are harmless. A revision gap marks the binding SUSPECT; on a publish the Planner queues reads only for SUSPECT bindings on that template. A lost *last* event leaves no gap; a daily outbox comparison and audit loads catch it, and every open refreshes the binding.

**Races.** Decisions name their target, so a publish mid-decision is harmless; a since-withdrawn target is rejected as "offer changed" (A11). Worker races are tested in Part 2.

**The user sees a tri-state**: *Update available*, *Up to date*, or *Checking*. Nothing claims "up to date" without evidence.

## 5. Production evolution, scale, cost, operations

**Migration, no maintenance window**, every step additive and flag-reversible: (1) ship hooks dark, seed the graph from Template Store history; (2) backfill through the worker at BACKFILL priority, raised to INTERACTIVE for a firm viewing its list; (3) shadow mode against audit loads; (4) enable per region at ≥ 99% confirmed bindings, small firms first. Unrecorded past declines (A4) re-show once: errors go towards showing an update. **Backfill**: 800k loads at 50 concurrent is 16,000 min, ≈ 11 days 24/7 or ≈ 22 days off-peak, shortened by organic opens (A12); compute $0.8k–1.6k once.

**List view for a 40,000-engagement firm.** Deriving status per engagement reads 12 MB per view (≈ 1,500 RRU). Identical bindings share a status, so the table keeps a **cohort count** per (firm, template, branch, applied, decline): ≤ 1,200 cohorts for the largest firm (40 templates × 10 applied versions × 3 decline states, A8). The Projector writes the binding and moves one count between cohorts in a single DynamoDB transaction conditioned on the revision, so duplicates change nothing. A view reads the firm's cohorts (240 KB, ≈ 30 RRU, one query), derives each cohort's status, and has **exact counts** without a scan. **Filter and paginate** through a GSI keyed by firm and cohort, sorted by name: a page of 50 merges the pending cohorts' partitions under an opaque multi-cursor; other sorts are client-side below 1,000 rows. Cost: 6-WRU transactions for ≤ 3.5M decisions/month (in the table below) and one GSI; bulk "apply all" heats one counter at ≈ 11 writes/s. A per-engagement "pending" flag would cost 20k writes and a drift source per publish.

**Monthly cost** (planning figures, A9):

| Item | Arithmetic | ≈ $/month |
|---|---|---|
| Downstream loads | Audit 1,000/day = 30k loads × $0.001–0.002 | 30–60 |
| Events, projection, cohorts | 8M/month events ($1/M) and writes ($0.625/M) + 3.5M × 6 WRU | ≈ 25 |
| Status reads | 6M list views × ~25 RRU × $0.125/M | ≈ 20 |
| Compute, observability | Lambdas, 2 Fargate tasks × 3 regions, CloudWatch | 250–350 |
| Summaries | ≈ 1,740 pairs × ~$0.23 (§6) | ≈ $400, ≤ 800 with retries |
| **Total** | | **≈ $0.7k–1.3k** |

Loading on publish would cost $3.5k–7k/month in compute alone, plus ~79 slots. This fits any $X above ~$1.5k; below it, use the §6 levers.

**SLOs.** Freshness: 99% of confirmed engagements reflect a publish within 10 s (hidden-branch canary every 15 minutes); an open list picks it up on its next refresh. List view p99 < 1 s at 40k engagements. Correctness: < 0.1% projection/engagement disagreement in the audit sample, zero cohort drift when recounted. Coverage: confirmed bindings per firm. Summaries: change list at publish, narrative within 15 minutes for 95%.

**Observability.** Permits held (including quarantined after timeouts) versus grant and the adaptive limit; admission-pause time; queue age by priority and **oldest ready item per firm** (a fairness regression shows as one firm aging); dead letters; Bedrock spend. Telemetry carries ids, never engagement content.

**Failure recovery.** Projection lost: rebuild from outbox replay and loads; users see *Checking*. Graph lost: rebuild from the Template Store. Bedrock down: the change list still works. Engagement Service overloaded: the worker halves its limit and honours Retry-After. Region down: the feature is down with it.

## 6. Human-readable summaries

**Pipeline.** The JSON diff becomes change items with stable ids (path, operation, content hash). A **deterministic rendering** ("Procedure 'Revenue cut-off testing' added to section 4.2") is always shown as the authoritative list. A pinned Bedrock model writes a plain-language narrative in the market's language, every sentence citing change ids; template text is data, not instructions. Gates before anyone sees it: cited ids exist; every change is cited or listed under "other changes"; numbers, standard references and section names appear in a cited item; no recommendations; an LLM judge scores faithfulness. Any failure shows the list alone and alerts the content team. A golden set gates every model or prompt change; 5% of summaries get human review.

**Cost and quality.** Each publish creates offers from every applied version in use, ≤ 10 per branch (A8). *One summary per (from, to) pair*: ≈ 174 publishes/month × 10 ≈ 1,740 pairs/month × ~$0.23 (30k tokens in, 1.5k out, plus an evaluation pass, at $3/$15 per M) ≈ $400. *Composed per-step summaries* (v4→v5, v5→v6, …): ≈ $40, but users read changes later steps reverted, and gates can no longer check the narrative against the net change decided. I choose per-pair because the decision, and its November defence, concern the net change. The cheaper fallback lever is composition behind the deterministic net list, which stays exact; lazy generation on first view also limits spend to pairs someone opens.

**Defending a March summary in November.** Summaries are immutable and content-addressed (diff hash, model version, prompt version, parameters), stored with inputs, output and gate results under S3 Object Lock in compliance mode (A10). The decision record holds the summary id and the hash of the text shown. We can produce what the user saw, the list, the diff, model, prompt and gate evidence, without re-running a model. If a summary proves wrong, the decision log lists every decision made on it.

## 7. Tradeoffs, assumptions, challenge, risk, omissions

**Tradeoffs.** *Derivation vs. pending flags*: no fan-out or drift, for a cohort lookup per view. *Capture on load*: free and fresh, but needs another team's hook. *DynamoDB*: conditional writes are the monotonic upsert; ad hoc SQL goes to Athena. *Per-pair summaries*: private, but blind to customisations. *One cumulative offer*: v5 cannot be taken without v6.

| # | Assumption | If false |
|---|---|---|
| A1 | Lineage comes from the Template Store or is declared at publish | Declare it; never infer from version names |
| A2 | Applying is version-granular | Per-change declines; redesign offers |
| A3 | Engagement Service can emit revisioned events via an outbox | Publish-after-commit; bigger audit sample |
| A4 | Engagements, firm and region listable without loading; old declines may be unrecorded | Backfill relies on organic opens |
| A5 | Grant ≈ 50 concurrent loads per region off-peak, ~10 on-peak | Backfill time scales inversely |
| A6 | Engagements follow their own market branch | Explicit migration edges |
| A7 | Withdrawal is terminal | Graph status needs a revision |
| A8 | ≤ 10 applied versions in use per branch; diffs ≈ 30k tokens | Chunk diffs; more cohorts |
| A9 | AWS prices are planning figures | Re-run the script |
| A10 | Regions US, EU, CA; template content may leave them; summaries retained ≥ engagements | Bedrock per region |
| A11 | Decisions serialised per engagement | Revision precondition on decisions |
| A12 | ~50% of engagements opened within a month | Backfill takes 11–22 days |

**The requirement I challenge: "real time, within seconds".** For the indicator it is cheap, so I meet it; as a target for everything else it is wrong. Updates arrive weekly and decisions take days. A narrative in seconds skips the gates that make it defensible, and un-backfilled engagements cannot meet it. I propose indicator and change list in seconds, narrative within 15 minutes, *Checking* until backfill completes, and no push channel: I dropped my first draft's WebSocket notifier because no decision depends on a sub-minute push for a weekly event.

**Riskiest part: the version semantics.** An error is silent and plausible: "up to date" shown confidently, a methodology update missed, no dashboard noticing. Mitigation: one pure function; table tests from concrete histories, to be reviewed with content teams; property tests on random histories (applying an offer leaves nothing pending; "up to date" never hides published content outside the applied or declined version; event order is irrelevant); *Needs review* instead of guessing; shadow mode. Second risk: another team must ship the hook and grant capacity.

**What I would cut for a first release.** Keep the pure version function, revision-guarded writes, the hard concurrency cap, coalescing and fencing. Cut first the adaptive limiter and per-firm fairness: a fixed conservative limit with the shared Retry-After pause is safe, only slower, and fairness matters only once backfill starts. Next, the LLM narrative: the deterministic list alone is correct.

**Deliberately left out.** Applying updates. Per-engagement impact analysis, which needs a load per engagement per publish (it could run lazily when an offer is opened). Cross-branch migrations. Notifications. Cross-region failover of firm data. A cross-process regional limiter (README). Bilingual Canadian summaries, which would double pairs there.
