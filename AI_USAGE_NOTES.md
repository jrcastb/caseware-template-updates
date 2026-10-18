# AI usage summary: take-home architecture exercise

>  This summarizes what the AI assistants (Claude) did across three working chats. **Chat 1** (the orchestrator chat) produced the original solution and the follow-up refinements, then compared the other solutions against it and wrote the prompts that started chats 2 and 3. **Chat 2** started from the prompt written in chat 1 (step F1) and produced an independent solution, then helped run, assess and explain it. **Chat 3** (the merge) started from chat 2's solution and the merge prompt written in chat 1 (step F3). It produced the final submission, verified it, and prepared the author for the live review.

### Chats and models

| Chat | Role | Model | Main output |
|---|---|---|---|
| 1 | Orchestrator: first solution, refinements, comparison of the other solutions, prompts for chats 2 and 3 | Claude Sonnet 5.5 | Original solution (29 tests), design and diagram updates, explanations, the F1 and F3 prompts, the review of chat 2's solution |
| 2 | Independent solution from the brief only | Claude Opus 5.5 (as stated by the author) | Independent solution (32 tests), demo package, run and assessment follow-ups |
| 3 | Merge of both solutions | *Author to confirm* | Final submission (75 tests), verification, review walkthrough |

## 1. What was asked

The author shared the take-home brief and asked the assistant to act as a Staff Java developer and produce every deliverable: design document, Part 2 Java implementation (template-publish fan-out worker) with README, diagrams, and AI-usage notes. Follow-up requests refined the work (AWS stack, diagrams, explanations, comparison with another model's solution).

In chat 2, a second model received only the brief and the prompt from F1, and produced an independent solution. The follow-ups there covered how to run it, whether the demo results met the requirements, an explanation of the problem from scratch, and documenting AI usage.

In chat 3, the assistant received the brief, chat 2's solution (zip) and the merge prompt from F3. The prompt kept chat 2's central design and listed required changes:

- **A1–A4, worker:** adaptive limiter, shared Retry-After pause, per-firm fairness, permit quarantine on timeout.
- **B1–B3, correctness tests:** crash loop, overlapping requests, permit leaks.
- **C1–C2:** version semantics as a pure, tested function.
- **D1–D5, design:** list view at 40,000 engagements, summary cost, SLOs, a 4-page limit, a first-release cut.
- **E1–E2:** diagrams and a consistency pass.

It also set a mandatory verification protocol: `-Xlint:all -Werror`, 10+ test runs, mutation checks, recomputed numbers and demo runs. The follow-ups covered finishing the work (two "Continue" turns after tool limits), a plain explanation, a review walkthrough, a personal review guide kept out of the submission, and AI-usage documentation.

## 2. Step-by-step process

The steps are grouped by purpose (understand, build, check, communicate, operate, cross-check, document), not by time. The tag after each step ID says which chat did the work. Timing notes: chat 2 began after step F1. Its solution already existed when it was uploaded to chat 1 for review (F2), and that zip already contained the demo package and the author's `mvn test` result, so E4 happened before F2. The timing of chat 2's other follow-ups (C2, G1, G2) relative to F2 and F3 is not recorded. Chat 3 began after step F3.

### Phase A. Understand the problem

| Step | What the author asked | What the assistant produced |
|---|---|---|
| A1 · chat 1 | What is this exercise really testing? | A reading of the brief: the 1-minute load is the trap, every stated number is a constraint, and each sentence maps to something the reviewers evaluate |
| A2 · chat 1 | Explain the problem in simple terms | A textbook-publisher analogy; why the obvious solution fails; the core idea of keeping a ledger instead of opening every copy |
| A3 · chat 2 | Explain the context, the points to pay special attention to, and how to approach the exercise from scratch | A plain-language explanation (phone app-update analogy) and the arithmetic that rules out the naive fan-out. Also what each detail in the brief is hinting at, a step-by-step 4-hour plan, five things to be able to explain in two minutes each, and likely review questions |
| A4 · chat 3 | Explain in an easy way what was done to the project, and what the added Python files are for | A plain-language summary of the four worker protections, the version rules as code, the tests (32 → 75) and the design changes. The Python files were explained as verification tools only, not product code: `check_numbers.py` (recomputes every design figure), `mutate.py` (injects bugs to prove tests catch them), `arch_svg.py` (draws and checks diagram 1). Also what the author still had to do before submitting |

### Phase B. Build the deliverables

| Step | What the author asked | What the assistant produced |
|---|---|---|
| B1 · chat 1 | Act as a Staff Java developer and complete every deliverable, applying SOLID, patterns and modern Java where relevant | `DESIGN.md`; the Java fan-out worker with 29 tests and a README; Mermaid diagrams; AI notes; a zip of everything |
| B2 · chat 2 | The F1 prompt, given to a second model with only the brief: analyse the numbers first, produce every deliverable, compile and test, report only observed results | An independent solution. `DESIGN.md` (4 pages) uses a regional binding projection, pending status derived on read, one cumulative offer, "declined" pinning the (applied, target) pair, summaries per (from, to) pair, and assumptions A1–A12. Also the fan-out worker with 32 JUnit 5 tests and a README, a hand-laid-out architecture SVG plus 4 Mermaid diagrams rendered to SVG, an AI-notes draft, and a zip |
| B3 · chat 3 | The F3 merge prompt on chat 2's solution, part A (worker): protect the downstream better | `AdaptiveCapacityLimiter`: AIMD under the hard grant, halving at most once per cooldown, with permits reporting SUCCESS / OVERLOAD / NEUTRAL feedback. A shared `AdmissionPause`: one throttle pauses every lane until Retry-After, with the attempt refunded. Round-robin per-firm fairness in `leaseOne` with a work-conserving cap; the firm id was added to requests and targets. Permit quarantine: after a timeout the permit is held until the abandoned call returns or the grace ends (`WorkerConfig.timeoutGrace`) |
| B4 · chat 3 | Same prompt, parts B and C: close correctness gaps and prove the riskiest part in code | New tests: a crash loop with real wedged worker instances, a publish plus a backfill arriving during an in-flight read, and permit balance across 11 outcome paths. Version semantics as a pure function (`VersionGraph`, `TemplateBinding`, `PendingStatus`, `VersionSemantics.derive`): one cumulative offer, decline pinned as the pair (applied, target), withdrawn-head fallback, withdrawn applied version flagged, "needs review" on diverged lineage. 15 table tests from concrete histories and 3 property tests over 3,000 seeded random histories. Tests: 32 → 75 |
| B5 · chat 3 | Same prompt, part D: design document | `DESIGN.md` rewritten from 2,642 to 2,299 words (counted with `wc -w`, including table pipes) and confirmed to fit 4 pages. Added the list view for a 40,000-engagement firm (per-firm cohort counts: ≤ 1,200 cohorts, ≈ 30 read units per view instead of ≈ 1,500), per-pair vs composed summary cost (≈ $400 vs ≈ $40 a month) with a choice and a fallback, SLO and observability additions, and "what I would cut for a first release". Removed the WebSocket notifier and summary replication, which no requirement needed. The design's pseudo-code was aligned with the tested function |

### Phase C. Check the work against the brief

| Step | What the author asked | What the assistant produced |
|---|---|---|
| C1 · chat 1 | Did the solution consider the brief's "Assumptions & Constraints" section? | An honest gap analysis. AWS was covered but scattered, assumptions were covered, and "clarity over completeness" was the weakest point because the code is larger than the brief implies. The design was then updated with an AWS stack table, Bedrock for inference, and a "what I would cut for a first release" paragraph |
| C2 · chat 2 | Shared the output of two demo runs (defaults; 1,000 engagements, 40 permits, 500 ms call): do we meet the requirements, and why? | A requirement-by-evidence mapping. Max in flight equalled the grant. Every extra downstream call was a retry (1093 − 1000 = 93 = 42 transient + 40 throttled + 11 timeouts), with one terminal outcome per engagement. The slow tail is backoff, not idle capacity. Also what the demo does not prove (process-local limiter, simulated DynamoDB and downstream, timed-out calls may still run remotely, no per-firm fairness) and what the author still had to do |
| C3 · chat 3 | The merge prompt's mandatory verification protocol | **Baseline:** the original 32 tests passed. **Final suite:** 75/75 in 30 of 30 consecutive runs. **Mutation testing:** 28 injected bugs (the 8 from chat 2 plus 20 new), all caught; a harness reports non-applying or non-compiling mutants as errors, never as survivors. **Numbers:** a script found 33/33 design figures present. **Demo:** both configurations run and reported (max in flight, throttles, dead letters, elapsed time) |
| C4 · chat 3 | (Found while running the demos for C3) | Measuring the demo exposed a design flaw that every test passed: a 100 ms Retry-After stopped admission for about 390 ms, because a pause ended the whole drain and waited for the slowest call in flight. The production loop was changed so each lane pauses independently, with a new real-clock test and a mutant (M28) that restores the old loop and is caught. Large demo: 41.0 s → 18.3 s. A throwaway comparison also showed the adaptive limiter was slower than the fixed one under random throttling (18.3 s vs 7.3 s). That was reported, not hidden, and it supports cutting the adaptive limiter first |
| C5 · chat 3 | The merge prompt's E2: no contradictions between README, design, diagrams and code | A consistency sweep that fixed a README describing fairness and AIMD as "not done", a stale "mvn test BUILD SUCCESS" claim for changed code, a decision log with no AWS home, the Status API named differently in the document and the diagram, and a stale summary-pair figure (1,730 → ≈ 1,740). `CHANGES.md` lists each change A1–E2 with the test that proves it |

### Phase D. Make the design easy to communicate

| Step | What the author asked | What the assistant produced |
|---|---|---|
| D1 · chat 1 | If the design changes, do the diagrams stay the same? Update them | Identified which diagrams were out of sync (components and sequence) and relabelled them with the AWS services |
| D2 · chat 1 | Make the diagrams more understandable, with icons and better flows | Two SVG diagrams with service icons, NEW/EXISTING tags, a numbered publish flow and latency bands. Layout was checked by script (text fit, overlaps, arrows crossing boxes); they were not rendered in the sandbox |
| D3 · chat 1 | What do diagrams 1 and 2 explain? | A walkthrough of both diagrams and what each one is meant to show a reviewer |
| D4 · chat 3 | The merge prompt's E1: diagrams that match the changed worker and the list-view mechanism | Architecture diagram regenerated: notifier and summary replica removed; cohort counts and decision log added; the worker shows AIMD, the shared pause and quarantine. Its generator now checks with real font metrics that text fits its box and that no arrow crosses a box or a label; the checker was shown to fail on deliberately broken layouts. Mermaid diagrams 2–5 re-rendered (mermaid-cli, headless Chrome) and inspected. Diagram 4 was simplified because its self-loop labels overlapped; diagram 5 is exactly one of the version tests. Every component is labelled new or existing |
| D5 · chat 3 | A step-by-step file to run and explain everything to the reviewer, every package and flow, mapped to what Caseware evaluates | `REVIEW_WALKTHROUGH.md`: a conversational script (what to say, what to run, why each step exists, likely follow-up questions), a map from evaluation criteria to steps, package-by-package and flow-by-flow explanations, a 24-case "what happens if" table with tests to run, and 10 hard questions. Every test reference was checked against the code and the quoted demo output against a real run. The diagram checker was made portable to macOS and Windows fonts so it would not crash live |
| D6 · chat 3 | Recreate that walkthrough as a personal document, not inside the submission zip, documented well enough to know exactly what to show and say | A personal review guide as a separate Claude Doc, removed from the zip. Explanations are in Spanish and the speaking script in English. For each step it gives the goal, exactly what to show (file, section, method and line numbers), the command and expected output, what to say, which part of the brief it answers, likely questions with answers, and what to do if something fails. Also preparation steps, a cases appendix, 15 hard questions, and a glossary of every concept in plain Spanish |

### Phase E. Understand and operate the code

| Step | What the author asked | What the assistant produced |
|---|---|---|
| E1 · chat 1 | How do I run it with Java 25 and Maven? | Commands for tests and the demo, plus fixes for plugin-version problems |
| E2 · chat 1 | Explain the many files and the flow | A folder-by-folder map and the two-phase flow (enumerate, then drain) |
| E3 · chat 1 | Where do I change parameters for different tests? | Where each tunable lives (worker config, limiter, retry policy, demo data), what it does, and experiments to show in the review |
| E4 · chat 2 | How do I run the fan-out? (shared own `mvn test` output: 32 tests, 0 failures, BUILD SUCCESS) | Explained that the worker is a library component with no production `main`. Added a `demo` package: `FanOutDemo` plus a simulated Engagement Service with a scaled call time and injected transient errors, throttles, hangs, a permanent failure and an archived engagement. The demo shows duplicate-delivery no-ops, merged overlapping publishes and recovery of a crashed worker's leases. Added run instructions and the author's `mvn test` result to the README |
| E5 · chat 3 | (Part of the merge) Make the demo show the new behaviour | Demo rewritten with skewed firms (one firm owns 40%), the adaptive limiter, and a simulated downstream that keeps working after the client stops waiting, so concurrency is measured on the server side. Final results: default run 14.1 s, max 10/10 in flight, 12 throttles, 1 dead letter, small firms done at 8.0 s vs the big firm at 13.2 s. Larger run (2000, 40, 50 ms): 18.3 s, max 21/40, 66 throttles, 1 dead letter. A `verification/` folder was added so the checks can be re-run |

### Phase F. Cross-check with an independent solution

| Step | What the author asked | What the assistant produced |
|---|---|---|
| F1 · chat 1 | A prompt for another model that starts only from the brief | A role-based prompt with a working method, deliverables and a quality bar, with no hints from this solution. It was used to start chat 2 (B2) |
| F2 · chat 1 | What do you think of the other model's solution? | Chat 2's solution (B2) was extracted to a scratch directory, compiled with `-Xlint:all -Werror`, and its 32 tests were run on a JUnit stand-in (3 runs, all passing); its demo was run (max in flight equalled the grant). Chat 1 then wrote a side-by-side comparison: its strengths, my gaps, my strengths and its gaps. Chat 1 did not re-run chat 2's mutation testing or render its pages |
| F3 · chat 1 | A prompt to merge the best of both, and how to enable code execution | A change-by-change prompt with acceptance tests and a verification protocol; setup guidance; advice on a new chat versus continuing this one. It was used to start chat 3 (B3–B5) |
| F4 · chat 3 | Continue (twice), after the assistant stopped at its tool limit | Each stop ended with an exact status: what was done, what was verified with counts, what was not verified, and what remained, with no claim of completion. The next turn resumed from that list. The final turn re-ran all checks on the final code before packaging |

**How the comparison (F2) fed the merge (B3–B5, C3, C5):**

| Finding in the comparison | Where it came from | Where it landed |
|---|---|---|
| Coalescing by engagement, crash-loop guard, re-run on raise, skip-when-fresh, mutation testing | Strengths of chat 2's solution | Kept as the base of the merge; mutation testing extended from 8 to 28 mutants (C3) |
| Adaptive limiter, shared Retry-After pause, work-conserving per-firm fairness | Strengths of chat 1's solution, absent from chat 2's code | Added to chat 2's worker (B3) |
| Permit released at timeout while the downstream may keep loading | A flaw in both solutions | Permit quarantine (B3) |
| The riskiest part (version semantics) described in chat 2's design but not implemented | Gap in chat 2's solution | Pure function with 15 table tests and 3 property tests (B4) |
| List view for a 40,000-engagement firm; per-pair vs composed summary cost | Open questions for chat 2's design | Per-firm cohort counts; a cost comparison with a fallback (B5) |
| Design length and extra AWS components | A concern about chat 2's design | Document cut from 2,642 to 2,299 words, WebSocket notifier removed (B5). The page-limit concern was a guess from word count, not a render (see section 6) |
| Contradictions between README, design and diagrams | Risk in any merged result | Consistency sweep and `CHANGES.md` (C5) |

### Phase G. Document AI usage

| Step | What the author asked | What the assistant produced |
|---|---|---|
| G1 · chat 2 | A summary of the AI help in chat 2, to append to the existing summary | A first chat-2 summary: the four exchanges, what was verified, corrections made during the session, and talking points |
| G2 · chat 2 | Update the cross-chat summary with chat 2, keeping its format and focusing on the step-by-step tables | The previous version of this document |
| G3 · chat 3 | A summary of the AI help in chat 3, to append to the existing summary | `prompt-ai-usage.md`: what was asked, what was built, verification results, a table of where the AI was wrong and how it was caught, and what the author still has to do |
| G4 · chat 3 | Update the cross-chat summary with chat 3, keeping its format and focusing on the step-by-step tables | The version before this one. This version applies edits from chat 1 (the orchestrator chat): the order column was removed, chat tags added, and the corrections in section 6 added |

## 3. Design decisions the assistant proposed

**Chat 1's original design:**
- **Do not load engagements on publish.** 800,000 loads at ~1 minute is 13,333 hours (about 11 days at 50 concurrent). One publish of an average product is about 6.7 hours. "Within seconds" is impossible on that path.
- **Ledger of facts plus a pure function.** Per-engagement basis, revision and decisions are recorded from engagement events. "Pending" is derived by a pure resolver over the version graph, so lost events are repaired by re-evaluation.
- **What "declined" pins:** one `(target, basisAtDecision)` pair, not the product or future versions.
- **Summaries per template version step,** generated once, validated, stored immutably (S3 Object Lock). The shown text is stored, never regenerated for audit.
- **Regional plane for engagement data, global plane for template content only.**
- **Challenged requirement:** "real time within seconds" for the narrative summary (indicator in seconds, narrative within minutes).
- **Riskiest part:** accuracy of the recorded basis during and after migration.

**Where chat 2's independent design differs** (input for the merge):
- It reached the same core decision on its own (derive status on read; load only for backfill and repair).
- It captures the binding as a side effect of loads the Engagement Service already performs (create, open, decide), so organic traffic does part of the backfill.
- Summaries are per (from, to) pair, a net diff from the applied version to the head, rather than per step. A deterministic change list is always shown as the authoritative list.
- "Declined" pins (applied, target), and anything contained in the declined target is not re-offered.
- The riskiest part is named as the version semantics themselves.

**What the final submission (chat 3) uses:**
- Chat 2's core design, kept unchanged: binding captured on existing loads, status derived on read, the slow load only for backfill and repair.
- **"Declined" pins the pair (applied, target),** implemented and tested. A decline recorded against a different applied version is ignored. Diverged lineage returns "needs review" instead of a guess. This is the same rule as chat 1's `(target, basisAtDecision)`, with the fields named differently.
- **List view through per-firm cohort counts,** updated in the same revision-conditioned transaction as the binding, instead of per-engagement pending flags.
- **Summaries per (from, to) pair** (≈ $400 a month), with composed per-step summaries (≈ $40) named as the cheaper fallback. The deterministic list stays the authority. Chat 1's own estimate for composed step summaries was ≈ $77 a month; the two estimates rest on different assumptions (chat 1 assumed 3 market branches and 5 locales plus a judge pass), so compare the structure of the two options, not the dollar totals.
- **No push channel.** The WebSocket notifier was removed as unjustified by any requirement, and that removal is part of the challenged requirement.
- **First-release cut:** keep the version function, revision-guarded writes, the hard concurrency cap, coalescing and fencing. Cut first the adaptive limiter and per-firm fairness, then the LLM narrative.
- **Riskiest part:** the version semantics.

## 4. Part 2 implementation (what exists)

**Chat 1:**
- Two durable phases: enumerate (checkpointed) then drain (leased, fenced).
- Two lanes: FAST (local store only) and PROBE (the slow downstream, admitted by a limiter).
- AIMD limiter with one decrease per cooldown. `Retry-After` pauses all admission. Throttles do not consume attempts.
- Per-firm fairness that is work-conserving (the cap applies only while other firms have work).
- Revision-guarded and stamp-guarded conditional writes to close two races (slow probe vs. user apply; slow worker vs. later decline).
- Sealed failure types, virtual threads, ports with in-memory adapters, 29 JUnit 5 tests.

**Chat 2:**
- One work item per engagement, merging overlapping publishes, backfill and repairs into a single downstream read.
- A capacity permit is taken before leasing, so leases never age while waiting for capacity.
- Leases with fencing tokens; crash-loop dead-lettering; a re-read when a newer request arrives mid-call; skipping the call when the binding was already confirmed.
- Throttles refund the attempt and make the lane yield. The planner checkpoints after enqueueing.
- Sealed outcomes, virtual threads, 32 JUnit 5 tests, and a runnable `demo` package.
- Not implemented: an adaptive (AIMD) limiter, per-firm fairness, and a distributed limiter (all documented as gaps).

**Chat 3 (final submission):** chat 2's worker, plus:
- **Adaptive limiter:** `AdaptiveCapacityLimiter` (AIMD, one decrease per cooldown, the grant as a hard ceiling) behind the existing `CapacityLimiter` port. The fixed `SemaphoreCapacityLimiter` stays as the first-release option.
- **Shared pause:** a Retry-After pause shared by all lanes, checked before the permit, after it, and between lease and call. In the production loop, each lane sleeps only until Retry-After.
- **Per-firm fairness:** round-robin across firms within a priority, with a work-conserving per-firm cap. It is skipped whenever every firm with ready work is at its cap, so a held permit is never idle.
- **Permit quarantine:** after a timeout, released when the abandoned call returns or the grace ends, exactly once.
- **Version semantics:** the pure function in the domain package, with table and property tests.
- **Tests and tooling:** 75 JUnit 5 tests; a `verification/` folder with the numbers script, the mutation harness and a Maven-free test runner.
- **Still not implemented:** a distributed (cross-process) limiter, and anything beyond quarantine for calls that keep running downstream after timeout plus grace. Both are documented in the README.

## 5. What was verified, and how

**Chat 1:**
- **Compiled and tested in the assistant's sandbox,** which had a JDK 21 runtime but **no Maven, no network and no JUnit**. Tests were run with a throwaway stand-in for the few JUnit APIs used. The author's own `mvn test` on the chat 1 solution was **not** confirmed by the assistant.
- 28 tests ran 8 times with no failures. After the fairness change, 29 tests passed in a further run (not repeated 8 times).
- The demo was run (2,000 engagements, 40 permits): max in flight never exceeded the ceiling, no dead letters. Elapsed time improved from about 4.2 s to 2.8 s after the fairness fix.
- Design arithmetic was recomputed with a script before writing the tables.
- SVG diagrams could **not** be rendered in the sandbox. Validity of the XML, text fit, label overlap and arrows crossing boxes were checked programmatically instead. The author should open them in a browser.
- **Chat 2's solution (F2):** compiled with `-Xlint:all -Werror` and its 32 tests passed in 3 runs on the same stand-in. Its demo (300 engagements, 10 permits) was run: max in flight equalled the grant and the only dead letter was the unreadable engagement. Chat 1 did **not** re-run chat 2's 8-bug mutation testing (that result is chat 2's own report), and did not render chat 2's document to count pages.
- Chat 1 has **not** seen chat 3's final code. Everything in the "Chat 3" paragraph below is taken from chat 3's own reports and has not been independently re-checked by chat 1.

**Chat 2:**
- **The author ran `mvn test` on the chat 2 solution: 32 tests, 0 failures, 0 errors, BUILD SUCCESS.** This is the only real Maven/JUnit confirmation so far.
- In the assistant's sandbox (no Maven, no network), compiled with `javac -Xlint:all -Werror` through the JDK compiler module. 32/32 passed on a JUnit stand-in, stable over 10 repeated runs.
- 8 deliberately injected bugs (fencing, capacity permit, lease reclaim, re-run on raise, monotonic write, freshness skip, checkpoint resume, throttle refund) were each caught by at least one test.
- Design arithmetic was recomputed with a script. The document was rendered to PDF to confirm it fits 4 pages.
- Diagrams **were** rendered (Mermaid CLI and headless Chromium) and inspected visually.
- The demo was run by the assistant (defaults; 1,000/40/50) and **by the author** (defaults; 1,000/40/500). In every run max in flight equalled the grant and the only dead letter was the permanently unreadable engagement.

**Chat 3:**
- **Environment:** the same kind of sandbox: JDK 21 runtime with the compiler module but no `javac` binary, no Maven, no network. Main and test sources compiled with real javac 21 and `-Xlint:all -Werror`. `--release 21` was unavailable, so source and target defaulted to 21.
- **Tests:** run on a minimal JUnit 5 API stand-in with fewer overloads than real JUnit, so anything that compiles there resolves in real JUnit. Baseline before changes: 32/32. Final: **75/75 in 30 of 30 consecutive runs.**
- **Mutation testing:** **28 injected bugs, 28 caught** on the final code. 8 are from chat 2; 20 are new, covering the cap, round-robin, pause, AIMD, quarantine, the version function and the old drain loop. Results are saved in `verification/mutation-results.txt`.
- **Design checks:** `check_numbers.py` found 33/33 figures present. `DESIGN.md` has 2,299 words and renders to 4 US Letter pages at 10 pt in headless Chrome.
- **Diagrams:** rendered and inspected. The architecture checker is verified to fail on injected overlaps.
- **Demo:** run on the final code in both configurations (results in E5).
- **`mvn test` was NOT run on the chat 3 (final) code.** The README says so and asks the author to run it and record the result.

## 6. Where the assistant's output was corrected or should be distrusted

**Chat 1 corrections:**
- **Fairness cap.** The first version was strict and left downstream capacity idle when only the big firm remained. The demo exposed it; it was changed to work-conserving and a test was added.
- **Evaluation ordering.** A graph-sequence-only stamp allows a slow worker to overwrite a user's later decline, so the stamp became `(engagementRevision, graphSequence)`.
- **Gaps found only after reading the other solution:** no coalescing across overlapping jobs (the ledger is keyed by publish and engagement), no guard against an engagement that crashes workers, no note that a timed-out call may still run downstream while its permit is released, and no mutation testing.
- **An imprecise status message.** After delivering the first solution, chat 1 told the author the 29 tests passed "repeated 8 times". Precisely, 28 tests ran 8 times and the 29th test, added with the fairness fix, ran once. Section 5 states the precise version. Quote section 5, not the chat message.
- **An unverified page-count claim.** In the comparison (F2), chat 1 said chat 2's design "likely runs past the 4-page cap", judging by word count. Chat 1 had not rendered it, and chat 2's own render showed 4 pages. Treat that remark as a guess that turned out wrong, not a finding.

**Cross-cutting:**
- **Numbers.** Prices (Bedrock, DynamoDB, pod cost), quotas and the 50-concurrent-load grant are planning figures recalled from memory. They are labelled as assumptions and must be verified before quoting.
- **Unverified claims to avoid:** do not say `mvn test` passed for the chat 1 or chat 3 solution unless you ran it; do not say the chat 1 diagrams were visually reviewed unless you did.

**Chat 2 corrections:**
- An arithmetic error (largest-firm backfill 13.9 h → 13.3 h), caught by recomputing every number.
- A first design draft of about 2,900 words, over the page limit, which was trimmed.
- Tooling slips: files written to the wrong directory because the shell lacked brace expansion, and `javac --release 21` unsupported in that JDK.
- One injected bug that did not compile; its empty result looked like a surviving mutant until checked.
- An auto-laid-out Mermaid architecture diagram that was valid but unreadable, replaced with a hand-laid-out SVG.
- A demo report that counted the archived engagement as confirmed.

**Chat 3 corrections:**
- **A design flaw every test passed.** The shared pause stopped admission ≈ 390 ms per 100 ms Retry-After, found only by measuring the demo. It was fixed and is now guarded by a real-clock test and mutant M28.
- **A benefit that did not exist.** The adaptive limiter was slower than the fixed limiter under the simulator's random throttling (18.3 s vs 7.3 s). It was reported instead of claimed.
- **A wrong test expectation.** A limiter test assumed +1 permit after two successes at limit 2; the real progression is 2 → 2.5 → 2.9. The test was fixed, not the code.
- **A flaky concurrency test.** It failed 1 run in 10 because it assumed calls overlapped. It was rewritten with explicit ordering; then 30/30 clean runs.
- **A test that could not fail.** A queue test for the per-firm cap passed with the cap removed. It was found by mutation testing (M09) and made deterministic.
- **A blind spot in the diagram checker.** It missed arrows crossing text until it was tested with deliberately broken layouts.
- **Inconsistencies found by the final sweep:** a README describing implemented features as missing, a stale `mvn test` claim, a decision log with no AWS home, mismatched Status API naming, and a stale summary-pair figure.
- **Tooling slips:** a test file written to the wrong directory (`cd $_`), so 35 tests appeared instead of 50; a Mermaid parse error from semicolons; a stray SVG at the project root; Linux-only font paths in the diagram checker; a background mutation run killed between turns. Each was caught by checking counts and outputs.
- **Tool limits.** The work did not fit in one turn; the assistant stopped twice and needed "Continue".

