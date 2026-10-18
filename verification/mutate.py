#!/usr/bin/env python3
"""Mutation check for the fan-out worker: inject one known bug at a time and confirm a test fails.

Each mutant is an exact text replacement that must match exactly once (a mutant that does not apply, or
does not compile, is reported as an ERROR, never as a survivor). Runs the full suite once per mutant,
sequentially, so timing-based tests are not disturbed by parallel load.
Usage: mutate.py [mutant-id ...]
"""
import os, re, shutil, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "fanout-worker")
J = "src/main/java/com/caseware/templateupdates/fanout/"
Q, W, LIM = J + "adapter/inmemory/InMemoryWorkQueue.java", J + "app/FanOutWorker.java", J + "adapter/inmemory/AdaptiveCapacityLimiter.java"
VS, VG, TB = J + "domain/VersionSemantics.java", J + "domain/VersionGraph.java", J + "domain/TemplateBinding.java"

# (id, new?, description, [(file, old, new), ...])
MUTANTS = [
 ("M01", False, "remove fencing", [(Q, "item.state == State.LEASED && item.token == lease.token();", "item.state == State.LEASED;")]),
 ("M02", False, "remove capacity permit", [(W, "new HeldPermit(limiter.acquire());", "new HeldPermit(() -> { });")]),
 ("M03", False, "remove lease reclaim", [(Q, "case LEASED -> !leaseExpiresAt.isAfter(now);", "case LEASED -> false;")]),
 ("M04", False, "remove re-run on raise", [(Q, "if (raisedDuringLease) {", "if (false) {")]),
 ("M05", False, "remove monotonic write", [(J + "adapter/inmemory/InMemoryBindingProjection.java",
          "state.revision() > current.state().revision()) {", "true) {")]),
 ("M06", False, "remove freshness skip", [(W, "if (alreadyFresh(lease)) {", "if (false) {")]),
 ("M07", False, "remove checkpoint resume", [(J + "app/PublishFanOutPlanner.java",
          "Optional<String> cursor = progress.cursor();", "Optional<String> cursor = Optional.empty();")]),
 ("M08", False, "throttle counts as an attempt", [(W, 'throttled.retryAfter()), false, "throttled")', 'throttled.retryAfter()), true, "throttled")')]),
 ("M09", True, "no per-firm cap", [(Q, "if (liveLeases.getOrDefault(firm, 0) < perFirmCap) {", "if (true) {")]),
 ("M10", True, "per-firm cap not work-conserving", [(Q,
          "NavigableSet<String> eligible = underCap.isEmpty() ? new TreeSet<>(nextByFirm.keySet()) : underCap;",
          "if (underCap.isEmpty()) {\n            return Optional.empty();\n        }\n        NavigableSet<String> eligible = underCap;")]),
 ("M11", True, "no round-robin across firms", [(Q,
          "String firm = Optional.ofNullable(eligible.higher(lastServedFirm)).orElse(eligible.first());",
          "String firm = eligible.first();")]),
 ("M12", True, "no shared pause (throttle only affects its own call)", [(W,
          "pause.pauseUntil(now.plus(throttled.retryAfter()));", "")]),
 ("M13", True, "no pause check between lease and call", [(W, "if (paused.isPresent()) {", "if (false) {")]),
 ("M14", True, "AIMD: decrease on every overload (no cooldown)", [(LIM,
          "if (lastDecrease == null || !now.isBefore(lastDecrease.plus(cooldown))) {", "if (true) {")]),
 ("M15", True, "AIMD: jump to the ceiling on success", [(LIM, "limit = Math.min(ceiling, limit + 1.0 / limit);", "limit = ceiling;")]),
 ("M16", True, "AIMD: ceiling not enforced", [(LIM, "limit = Math.min(ceiling, limit + 1.0 / limit);", "limit = limit + 1.0 / limit;"),
          (LIM, "return Math.min(ceiling, (int) Math.floor(limit));", "return (int) Math.floor(limit);")]),
 ("M17", True, "permit leaked on timeout (quarantine never releases)", [(W,
          "                    call.cancel(true);\n                    held.close();\n", "                    call.cancel(true);\n")]),
 ("M18", True, "no quarantine: release the permit at the timeout", [(W,
          "        } catch (TimeoutException e) {\n            quarantine(permit, call);",
          "        } catch (TimeoutException e) {\n            call.cancel(true);")]),
 ("M19", True, "quarantine ignores call completion (always waits the full grace)", [(W,
          "call.get(config.timeoutGrace().toMillis(), TimeUnit.MILLISECONDS);",
          "Thread.sleep(config.timeoutGrace());\n                    if (false) {\n                        call.get(1, TimeUnit.MILLISECONDS);\n                    }")]),
 ("M20", True, "AIMD: throttle not reported as overload", [(W,
          "                permit.report(Feedback.OVERLOAD);\n", "")]),
 ("M21", True, "version function ignores withdrawn status", [(VG, "                .filter(Node::published)\n", "")]),
 ("M22", True, "declined pin ignores the applied version", [(VS,
          ".filter(d -> d.from().equals(binding.applied()));", ".filter(d -> true);")]),
 ("M23", True, "no diverged-lineage check", [(VS, "if (!graph.isAncestorOrSelf(v.id(), target)) {", "if (false) {")]),
 ("M24", True, "apply keeps the old decline", [(TB, "return new TemplateBinding(branch, target, null);", "return new TemplateBinding(branch, target, decline);")]),
 ("M25", True, "declined pin is strict (re-offers the declined target itself)", [(VS,
          "graph.isAncestorOrSelf(headId, decline.get().target())", "graph.isAncestor(headId, decline.get().target())")]),
 ("M26", True, "head is the oldest descendant, not the newest", [(VS, ".findFirst(); // newest first", ".reduce((a, b) -> b);")]),
 ("M27", True, "includes-declined flag never set", [(VS, "flags.add(Flag.INCLUDES_DECLINED);", "flags.remove(Flag.INCLUDES_DECLINED);")]),
("M28", True, "production loop pauses a whole drain (waits for the slowest in-flight call)", [(W,
          '        var stats = new Stats();\n        try (ExecutorService lanes = Executors.newVirtualThreadPerTaskExecutor()) {\n            List<Future<?>> running = new ArrayList<>();\n            for (int i = 0; i < config.lanes(); i++) {\n                running.add(lanes.submit(() -> {\n                    while (!stopRequested.getAsBoolean()) {\n                        switch (runLane(stats)) {\n                            case IDLE -> Thread.sleep(idlePause);\n                            case PAUSED -> sleepUntilAdmissionResumes();\n                        }\n                    }\n                    return null;\n                }));\n            }\n            awaitLanes(running);\n        }',
          '        while (!stopRequested.getAsBoolean()) {\n            DrainResult result = drain();\n            if (result.pausedUntil().isPresent()) {\n                Duration wait = Duration.between(clock.instant(), result.pausedUntil().get());\n                Thread.sleep(wait.compareTo(idlePause) > 0 ? wait : idlePause);\n            } else if (result.processed() == 0) {\n                Thread.sleep(idlePause);\n            }\n        }')]),
]

def run(mutant):
    mid, new, desc, edits = mutant
    work = tempfile.mkdtemp(prefix=f"mut-{mid}-")
    dst = os.path.join(work, "fanout-worker")
    shutil.copytree(SRC, dst, ignore=shutil.ignore_patterns("target"))
    for path, old, rep in edits:
        f = os.path.join(dst, path)
        text = open(f).read()
        if text.count(old) != 1:
            return mid, new, desc, "ERROR", f"pattern matched {text.count(old)} times in {path}"
        open(f, "w").write(text.replace(old, rep))
    result = os.path.join(work, "result.txt")
    env = dict(os.environ, RESULT_FILE=result, TEST_TIMEOUT="20")
    p = subprocess.run([os.path.join(HERE, "run-tests.sh"), dst, "1"], capture_output=True, text=True, env=env)
    if not os.path.exists(result):
        return mid, new, desc, "ERROR", "did not compile: " + (p.stderr.strip().splitlines() or ["?"])[0]
    out = open(result).read()
    failed = re.findall(r"^FAIL (\S+)", out, re.M)
    summary = re.search(r"SUMMARY (.*)", out).group(1)
    shutil.rmtree(work, ignore_errors=True)
    return mid, new, desc, ("CAUGHT" if failed else "SURVIVED"), f"{summary}; " + ", ".join(failed)

if __name__ == "__main__":
    wanted = set(sys.argv[1:])
    for m in MUTANTS:
        if not wanted or m[0] in wanted:
            mid, new, desc, verdict, detail = run(m)
            print(f"{mid} | {'new' if new else 'existing'} | {desc} | {verdict} | {detail}", flush=True)
