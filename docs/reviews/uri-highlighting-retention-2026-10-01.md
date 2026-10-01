# URI highlighting retention — Stage 2

Stage 2 on `fix/uri-highlighting`, following the committed Stage 1 contracts
(`c8c74b34`). Scope and subsequent gates remain in the
[canonical repair map](../terminal-feature-gap-map.md#uri-highlighting-staged-repair).

## Retained ownership

`TerminalHyperlinkIndex` replaces `TerminalHyperlinkViewport`; there is one
result model. Its primary and alternate indexes retain logical text, mappings,
successful results and successful empty analysis until affected source leaves
retention or its history-content generation changes. An admission frontier
revisits the previous live-grid boundary, including rows edited and admitted
before their next published frame. Eviction releases affected action references;
surviving occurrence IDs remain unchanged. A partially retained wrapped occurrence
keeps its full prepared destination until its final source row is evicted.

Occurrence IDs are independent of projection slots and are never reused. A
primitive-key action table avoids boxed-ID allocation during interaction, while
ordinary Kotlin objects/collections represent discoveries and changed results.
No pooling or generic analysis framework was introduced.

`TerminalHyperlinkSourceScan` copies through the session's existing absolute-range
reader in bounded primitive batches (64 rows/4,096 cells; one exceptionally wide
row may exceed the cell budget). Text assembly occurs outside the mutation lock.
It reads backward to a logical boundary and continues across copy batches until a
soft-wrapped line is complete. A source mutation during copying rejects the batch.
Publication validates visible text and identities, separately from presentation-only
row-generation changes. A source ahead of the published cache drains its observed
demand once and reconciles on a subsequent published generation.

Prepared projection uses two reusable primitive planes. Binary searches clip
UTF-16 mappings to visible cells before traversing link text. Scrolling prepared
content performs neither detection nor text/action construction. Missing content
remains asynchronous; display never waits for a provider.

Ordered requests currently include at most 64 preceding and 64 pending logical
lines. This prevents the retained model from introducing whole-history filtering
on each append/edit. Persistent provider continuation and reconstruction of full
ordered state remain Stage 5 work; this bounded context is not a claim that
arbitrary stateful providers have already been repaired.

## Verification

Deterministic tests use real core frame readers and controlled coroutine/EDT
handoffs. They cover first-frame prepared-history activation and empty-result
reuse on 80×24, 160×48 and 240×48 grids; saturated eviction and partial wrapped
eviction through background reconciliation; a 924-character URL
crossing physical copy boundaries and a clipped viewport; off-screen live edits;
edited live-row admission; independent primary/alternate restoration and clear;
reflow; source mutation during copying; source-ahead reconciliation; progress-only
updates preserving occurrence IDs before and after rediscovery; row-identity
replacement with an identical URL; repeated
action-table retirement; and bounded ordered discovery across 1,000 linked rows.

All 1,022 Swing tests, standalone/shared-host compilation, JMH harness build and
all 225 IntelliJ plugin tests pass. The plugin project configuration task succeeds
with its existing explicit-coroutine-dependency warning. Full Plugin Verifier,
native lifecycle traces and real-provider allocation/latency profiling remain
Stage 7 integration work.

## Prepared-path measurement

JMH 1.37, JetBrains JDK 25.0.4.1+1-b610.67, Windows; one benchmark thread and no
concurrent builds. Every retained row contains a detected URL. The measured
operation copies a prepared viewport, jumps 37 rows, projects IDs, and resolves
an action. It excludes discovery, Swing dispatch, painting and provider callbacks.

Two forks, three one-second warmups and five one-second measurements:

| Grid | Retained rows | Mean time/frame ± JMH error (µs) |
|---|---:|---:|
| 80×24 | 1,000 | 11.11 ± 2.54 |
| 80×24 | 10,000 | 11.60 ± 1.25 |
| 160×48 | 1,000 | 17.11 ± 1.11 |
| 160×48 | 10,000 | 27.77 ± 7.95 |
| 240×48 | 1,000 | 31.76 ± 5.33 |
| 240×48 | 10,000 | 43.61 ± 7.76 |

The GC profiler reports approximately 0.007 MB/s across all cases, or
0.077–0.305 B/frame, with no measured collections. Because that counter includes
other JVM/profiler threads, a separate JMH allocation workload measures the
owner thread directly with `ThreadMXBean`. One fork, two one-second warmups
and three one-second measurements records **0 allocated bytes in every
measurement iteration for all six configurations**. These measurements establish
the prepared storage/projection path, not a whole-terminal-frame allocation or
active-discovery readiness guarantee. Final integration profiling remains Stage 7.

Reproduce after `./gradlew :ketraterm-benchmarks:jmhJar` with a JDK 25 runtime:

```text
java -jar ketraterm-benchmarks/build/libs/ketraterm-benchmarks-0.3.0-SNAPSHOT-jmh.jar TerminalHyperlinkProjectionBenchmark.scrollPreparedHistory -f 2 -wi 3 -w 1s -i 5 -r 1s -prof gc -rf json -rff prepared-projection.json
java -jar ketraterm-benchmarks/build/libs/ketraterm-benchmarks-0.3.0-SNAPSHOT-jmh.jar TerminalHyperlinkProjectionBenchmark.countPreparedAllocations -f 1 -wi 2 -w 1s -i 3 -r 1s -prof gc -rf json -rff prepared-thread-allocations.json
```

Raw local runs are under `build/uri-stage2/` (ignored). Both feature maps and
the Swing migration notes describe Stage 2. Changelogs remain unchanged until
the meaningful user-facing repair is consolidated. Changes are uncommitted for
user verification; later stages are not part of this review.
