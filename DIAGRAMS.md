# Diagrams

Diagram 1 is hand-laid-out SVG (an automatic Mermaid layout of it was unreadable); its generator `diagrams/arch_svg.py --check` verifies with font metrics that text fits its boxes and that no arrow crosses a box or a label. Diagrams 2 to 5 are Mermaid (source below; SVGs in `diagrams/` are rendered from this exact source with mermaid-cli). Every component is marked **new** or **existing** (green or grey in diagram 1).

## 1. Architecture and data boundaries

![Architecture](diagrams/1-architecture.svg)

Template data (global, not firm-specific) flows outward to regions; firm data (bindings, cohort counts, decisions, work items) never leaves its residency region. The Status API reads summaries from the global archive, which holds template-derived text only. The Fan-out Worker is the only caller of the one-minute load, and only for backfill and suspect bindings.

## 2. Main flow: publish to visible indicator

No engagement is loaded on this path. The graph write is visible in each region within seconds; the deterministic change list is available at once and the narrative follows within minutes. An open list picks the change up on its next refresh (no push channel; DESIGN.md §7).

```mermaid
sequenceDiagram
  autonumber
  participant CT as Content team (existing)
  participant TS as Template Store (existing)
  participant EB as EventBridge (new)
  participant GS as Graph Sync λ (new)
  participant VG as Version Graph (new)
  participant UI as Web app (existing)
  participant API as Status API (new, per region)
  participant BP as Projection + cohort counts (new, per region)
  participant SS as Summary Service (new)

  CT->>TS: publish v8 on branch CA-2026
  TS-)EB: TemplatePublished (hook, at-least-once)
  EB-)GS: deliver
  GS->>VG: upsert node v8 (idempotent, order-independent)
  Note over VG: replicated to each region (global table)
  EB-)SS: one job per distinct (from, to) pair
  UI->>API: GET firm's list (filter: pending)
  API->>BP: read the firm's cohorts (≤ 1,200 rows, one query)
  API->>API: status per cohort = derive(binding, graph)
  API->>BP: page of 50 from the pending cohorts' GSI partitions
  API-->>UI: exact counts + page + deterministic change list
  SS->>SS: diff, narrative, gates, archive (minutes)
  UI->>API: open offer
  API-->>UI: narrative summary, or the deterministic list if gates failed
```

## 3. Part 2: fan-out worker flow

```mermaid
sequenceDiagram
  autonumber
  participant EB as Regional bus (new)
  participant PL as Planner λ (new)
  participant WQ as Work table (new)
  participant W as Worker lane, virtual thread (new)
  participant AP as Admission pause, shared by lanes (new)
  participant CL as AIMD limiter, ceiling = grant (new)
  participant ES as Engagement Service (existing)
  participant PR as Binding Projection (new)

  EB-)PL: TemplatePublished (possibly duplicate)
  PL->>WQ: enqueue SUSPECT targets with firm, coalesced per engagement, checkpoint per page
  W->>AP: paused? stop this drain until Retry-After
  W->>CL: acquire permit (blocks at the adaptive limit, never above the grant)
  W->>WQ: leaseOne: top priority, round-robin across firms, per-firm cap (work-conserving)
  alt binding confirmed after the request
    W->>WQ: complete, no downstream call
  else admission paused after this lease
    W->>WQ: retryLater at Retry-After, attempt refunded
  else needs a read
    W->>ES: load(engagement), about 1 min, timeout 3 min
    alt Loaded / Gone / Permanent
      ES-->>W: outcome
      W->>CL: report SUCCESS (additive increase)
      W->>PR: applyIfNewer(revision)
      W->>WQ: complete or deadLetter (rejected if token is stale)
    else Throttled(Retry-After)
      ES-->>W: 429
      W->>CL: report OVERLOAD (halve, at most once per cooldown)
      W->>AP: pause every lane until Retry-After
      W->>WQ: retryLater, attempt refunded
    else timeout
      W->>CL: report OVERLOAD
      Note over W,CL: permit quarantined: held until the abandoned call returns or the grace (2 min) ends
      W->>WQ: retryLater with backoff
    end
  end
  W->>CL: release permit (exactly once, by the quarantine watcher after a timeout)
```

## 4. Work item lifecycle

```mermaid
stateDiagram-v2
  direction LR
  [*] --> READY: enqueue (with firm)
  READY --> LEASED: permit, then fair lease
  LEASED --> [*]: complete
  LEASED --> READY: re-run, refund, or backoff
  LEASED --> DEAD: permanent, exhausted, crash loop
  DEAD --> READY: new request revives
```

Transitions from LEASED back to READY: *re-run* when a newer request arrived during the lease; *refund* when throttled or when admission was paused before the call (the attempt does not count); *backoff* after a transient failure or timeout (counts as an attempt). *Complete* covers loaded, gone and already fresh. Two non-transitions are not drawn: enqueueing an item that already exists coalesces into it (maximum priority and freshness), and a LEASED item whose lease expired (a crashed worker) is leased again with a new fencing token and an incremented attempt.

## 5. Version semantics example

The engagement is on v4 and declined v5 (the pin is the pair v4, v5). v7 was published and then withdrawn; v8 replaced it. The offer is v4 → v8, flagged "includes changes you declined in v5". The EU branch is never offered to a CA-2026 engagement. This exact history is the test `VersionSemanticsTest.theDiagramFiveExample`. If CA-2026 also had a published version not contained in v8, the result would be *Needs review* instead of an offer.

```mermaid
flowchart LR
  classDef applied fill:#cfe3ff,stroke:#2b5fb4,color:#111
  classDef declined fill:#ffe1c2,stroke:#b4632b,color:#111
  classDef withdrawn fill:#f2f2f2,stroke:#999,stroke-dasharray:4 3,color:#777
  classDef head fill:#d4f0dc,stroke:#2e7d4f,color:#111
  classDef other fill:#ffffff,stroke:#999,color:#555

  v4["v4: applied"]:::applied --> v5["v5: declined from v4"]:::declined
  v5 --> v6["v6"]
  v6 --> v7["v7: withdrawn"]:::withdrawn
  v6 --> v8["v8: head, offered as v4 to v8"]:::head
  v5 --> eu["EU-2026 v5-eu1: other branch, never offered"]:::other
```
