# Template-publish fan-out worker (Part 2)

The component that turns a template publish into reads of engagements through the Engagement Service's
~1-minute load operation, without exceeding the capacity that team grants us. Java 21, no runtime
dependencies, JUnit 5 for tests. It also holds the pure version-semantics function the whole design
depends on (DESIGN.md §4), because that is the riskiest code in the system.

```
mvn test
```

## Running it

The worker is a library component (no production `main`, as the brief asks). `demo.FanOutDemo` runs the
real planner and worker end to end against the in-memory adapters and a simulated Engagement Service whose
~60 s call is scaled down to milliseconds. Timeouts, leases, grace and backoff are scaled from it.

```
mvn -q compile
java -cp target/classes com.caseware.templateupdates.fanout.demo.FanOutDemo                 # 300 engagements, 10 permits, 200 ms call
java -cp target/classes com.caseware.templateupdates.fanout.demo.FanOutDemo 2000 40 50      # engagements, permits, call ms
```

Firms are skewed like the brief (one firm owns 40% of the engagements, the rest are firms of 5). The demo
plans one publish, re-delivers it (a no-op), plans an overlapping publish (fully coalesced), and leaves
three leases held by a "crashed" worker. The simulated downstream injects transient errors, throttling,
loads slower than the timeout, one permanently unreadable engagement (dead-lettered) and one archived one.
It measures concurrency on the **server** side: a slow load keeps a pod busy after the client stops
waiting, which is what the grant is about.

## Where it sits in the design

The design keeps this worker **off** the real-time path. A publish makes pending status visible by
updating the version graph; status is derived from a projection of each engagement's template binding.
The worker exists for the residue that cannot be derived: engagements whose binding is unknown (backfill)
or suspect (an event gap, a failed audit sample). That is why work is keyed by *engagement*, not by
*(publish, engagement)*.

```
TemplatePublished ──► PublishFanOutPlanner ──► WorkQueue ──────► FanOutWorker ──► EngagementStateReader (~1 min)
   (at-least-once)     dedupe, page,           1 item per        pause → permit → fair lease      │
                       checkpoint              engagement,       → call → record → release         ▼
                                               fair per firm                └──────► BindingProjection (revision-guarded)
```

Layout: `domain` (records, the sealed `ReadOutcome`, and `VersionGraph` / `VersionSemantics`), `port`
(interfaces with the contracts in Javadoc), `app` (planner, worker, config, retry policy), `adapter.inmemory`
(reference implementations used by the tests), `demo` (runnable wiring). Production adapters (DynamoDB, the
Engagement Service client) are not included.

## Key implementation tradeoffs

**Correctness does not depend on exactly-once anything.** The downstream call is a read, so doing it
twice is wasteful but never wrong. The only write is to the projection, and it is a monotonic upsert on
the engagement's revision (`applyIfNewer`). Duplicates, reordering and a paused worker waking up late can
all happen; none of them can move an engagement backwards. Idempotency elsewhere saves capacity.

**One item per engagement, coalesced.** Two publishes, a backfill request and a suspect-binding repair for
the same engagement collapse into one item with the maximum priority and the latest freshness requirement.
If a newer request arrives while a read is in flight, completion re-runs the item once; if the in-flight
read already satisfies it, nothing extra happens. Both, and a publish plus a backfill arriving together
mid-read, are tested.

**Permit before lease.** Each lane acquires a capacity permit and only then leases an item, so leases never
age while waiting for capacity (which would cause duplicate one-minute loads).

**The grant is a hard ceiling; the adaptive limit sits under it.** `AdaptiveCapacityLimiter` is AIMD behind
the same `CapacityLimiter` port: +1/limit per success (about one permit per round of one-minute calls),
halve on overload (a throttle or a timeout), at most once per cooldown, because one brownout of one-minute
calls produces many failures at once. Lowering the limit never revokes held permits. `SemaphoreCapacityLimiter`
is the fixed alternative, and the one I would ship first (DESIGN.md §7): the demo below shows AIMD costing
throughput when throttling is random rather than load-driven.

**A throttle pauses every lane until Retry-After.** One lane being told "back off" means the downstream is
overloaded for all of them. The pause is shared, monotonic, checked before acquiring a permit, after
acquiring it, and again between lease and call; an item caught by the pause is returned with its attempt
refunded. In the production loop each lane sleeps exactly until Retry-After, independently, so a pause is
not stretched by the slowest call still in flight (an earlier drain-at-a-time loop did that: measured
≈ 390 ms of stop per 100 ms Retry-After in the demo).

**Fair across firms, without idling capacity.** Within a priority, `leaseOne` serves firms round-robin
and skips a firm that already holds its per-firm cap of live leases while another firm under the cap has
ready work. The cap is work-conserving: when every firm with ready work is at its cap (including a lone
firm), the item is leased anyway, because the caller already holds a permit and an idle permit is wasted
grant. This is slightly stronger than "only when one firm remains" and includes it. Priority always
dominates fairness.

**Permit quarantine on timeout.** After a timeout we stop waiting, but the downstream may still be loading
the file. The permit stays occupied until the abandoned call returns (known finished) or the grace elapses,
then the call is interrupted and the permit released, by one watcher, exactly once. A test covers every
outcome path for permit balance.

**Leases with fencing tokens for crash recovery.** A crashed worker's item becomes leasable when the lease
expires; each lease gets a new token and stale outcomes are rejected. Expired leases count as attempts, so
an engagement that kills every worker is dead-lettered after `maxAttempts` (tested with real worker
instances that wedge, not only simulated expiries). `WorkerConfig` refuses a lease shorter than the timeout.

**Skip when already fresh**, **sealed `ReadOutcome`** handled by an exhaustive `switch`, **jittered
exponential backoff**, **virtual threads bounded by the limiter**, and **checkpoint after enqueue** in the
planner are unchanged from the first version.

**Version semantics as a pure function.** `VersionGraph` precomputes ancestor sets (Kahn's algorithm,
rejecting cycles, unknown parents and duplicate branch sequences) and is built from publish and withdraw
events in any order. `VersionSemantics.derive` implements DESIGN.md §4 line for line: one cumulative offer to
the newest published descendant on the branch; declining pins the pair (applied, target), and a decline
recorded against another applied version is ignored; withdrawn heads fall back; a withdrawn applied
version is flagged, never changed; a diverged lineage returns `NeedsReview` instead of a guess.

## What I did not do

**The limiter and the pause are process-local.** The grant is per region and shared by every worker task.
Simplest correct option: a static split (permits per task = grant ÷ task count, fixed in ECS), each task
running AIMD inside its share; a DynamoDB counting semaphore with leased permits if tasks must scale
dynamically. A throttle seen by one task does not pause the others; their own throttles will.

**AIMD is not shown to help.** The simulator throttles 3% of calls at random, independent of load, which
gives AIMD nothing to learn from; it only costs throughput there (numbers below). Its value depends on the
downstream's throttling tracking its real load, which needs the other team's data to confirm.

**Quarantine bounds, but does not guarantee, true concurrency.** If a load is still running downstream after
timeout + grace (3 + 2 minutes in production), it is interrupted on our side but may continue on theirs.
Closing that needs a cancellation or admission token from the Engagement Service team. Also, a timed-out
item retried before its abandoned call finishes can be loaded twice concurrently; both hold permits, so the
grant still holds, and the result of the abandoned call is discarded.

No lease heartbeat (the lease is sized above the timeout), no metrics or tracing (the design names the
signals: permits held vs grant, adaptive limit, pause time, oldest ready item per firm), no AWS adapters.
The in-memory queue scans all items per lease, fine for a reference adapter, not for production.

## How this was verified

Environment: JDK 21 runtime with the `jdk.compiler` module but no `javac` binary, no Maven, no network.
```
  oct 17, 2026 1:01:15 P. M. com.caseware.templateupdates.fanout.app.FanOutWorker record
  ADVERTENCIA: lease lost for eng-001 (token 3); outcome superseded by a newer lease
  oct 17, 2026 1:01:15 P. M. com.caseware.templateupdates.fanout.app.FanOutWorker record
  ADVERTENCIA: lease lost for eng-001 (token 2); outcome superseded by a newer lease
  oct 17, 2026 1:01:15 P. M. com.caseware.templateupdates.fanout.app.FanOutWorker record
  ADVERTENCIA: lease lost for eng-001 (token 1); outcome superseded by a newer lease
  oct 17, 2026 1:01:17 P. M. com.caseware.templateupdates.fanout.app.FanOutWorker process
  GRAVE: unexpected failure processing eng-001
  java.lang.IllegalStateException: projection unavailable
          at com.caseware.templateupdates.fanout.app.FanOutWorkerTest$2.applyIfNewer(FanOutWorkerTest.java:745)
          at com.caseware.templateupdates.fanout.app.FanOutWorker.handle(FanOutWorker.java:210)
          at com.caseware.templateupdates.fanout.app.FanOutWorker.process(FanOutWorker.java:197)
          at com.caseware.templateupdates.fanout.app.FanOutWorker.runLane(FanOutWorker.java:170)
          at com.caseware.templateupdates.fanout.app.FanOutWorker.lambda$drain$0(FanOutWorker.java:131)
          at java.base/java.util.concurrent.FutureTask.run(FutureTask.java:328)
          at java.base/java.util.concurrent.ThreadPerTaskExecutor$ThreadBoundFuture.run(ThreadPerTaskExecutor.java:323)
          at java.base/java.lang.VirtualThread.run(VirtualThread.java:460)

  [INFO] Tests run: 30, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 7.007 s -- in com.caseware.templateupdates.fanout.app.FanOutWorkerTest
  [INFO] Running com.caseware.templateupdates.fanout.app.RetryPolicyTest
  [INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.001 s -- in com.caseware.templateupdates.fanout.app.RetryPolicyTest
  [INFO] 
  [INFO] Results:
  [INFO] 
  [INFO] Tests run: 75, Failures: 0, Errors: 0, Skipped: 0
  [INFO] 
  [INFO] ------------------------------------------------------------------------
  [INFO] BUILD SUCCESS
  [INFO] ------------------------------------------------------------------------
  [INFO] Total time:  8.787 s
  [INFO] Finished at: 2026-10-17T13:01:22-05:00
  [INFO] ------------------------------------------------------------------------
```
- Main and test sources compiled with real javac 21, `-Xlint:all -Werror` (`java -m jdk.compiler/...`;
  `--release` is unavailable on this JRE, so source and target are the JDK default, 21).
- Tests run against a minimal stand-in for the JUnit 5 API (`../verification/junit-standin`, fewer
  overloads than real JUnit, so anything that compiles there resolves in JUnit 5.10):
  `../verification/run-tests.sh "" 30` → **75 tests, 75 passed, in each of 30 consecutive runs**.
  An earlier version of one throttle test failed 1 run in 10 because it assumed calls overlapped; it now
  orders them explicitly.
- Mutation check, `../verification/mutate.py`: **28 injected bugs, 28 caught** (8 from the first version,
  20 for new code); results in `../verification/mutation-results.txt`.
- Demo, same seed (42), final code:

| Run | Elapsed | Max in flight, server-side / grant | Throttles | Dead letters | Small firms done / big firm done |
|---|---|---|---|---|---|
| default (300, 10, 200 ms) | 14.1 s | 10 / 10 | 12 | 1 (the unreadable one) | 8.0 s / 13.2 s |
| `2000 40 50` | 18.3 s | 21 / 40 | 66 | 1 | 11.8 s / 18.1 s |

  The adaptive limit stayed at or below about 20 of 40 in the larger run because of the random throttling.
  For comparison only (a throwaway patch, not shipped): the fixed limiter on the same seed took 7.3 s.
  Before the loop change, the same run with the fixed limiter took 30.0 s with throttling and 4.2 s without.
