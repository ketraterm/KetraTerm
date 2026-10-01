# URI highlighting baseline — 2026-09-30

Stage 0 evidence on `fix/uri-highlighting`, based on production commit
`4945ad2a138327d5fe312f423190e45449ee01bb`. This change adds replay fixtures,
semantic tests, and measurement workloads; it does not change production
behavior. The ordered implementation scope and gates live in the
[canonical gap map](../terminal-feature-gap-map.md#uri-highlighting-staged-repair).

## Captured agy output

Correction from the October 1 regression investigation: the original sanitizer
assigned every OSC 8 opening the same replacement ID without checking equality.
The fixture alone therefore cannot establish original ID grouping. A fresh isolated
capture subsequently confirmed the five URL fragments and caption do share an ID
and destination in the installed agy. That protocol identity does not establish
the desired visual hover grouping; the caption must remain separate.

The [sanitized ConPTY capture](../../ketraterm-ui-swing/src/test/resources/hyperlinks/README.md)
comes from a fresh signed-out instance of the locally installed agy executable,
at **176×32**. It preserves all 7,502 bytes of layout and control structure,
including CR/LF, cursor movement, styling and OSC 8 boundaries. Hostname,
query values, and explicit ID were replaced with equal-length inert values.
The original capture and isolated application data were deleted.

The 704-character URL is emitted as five fragments of **174/174/174/174/8**
characters. Each fragment reopens OSC 8 with the **same explicit ID and full
destination**. The separate “Click here to authenticate” segment uses that
same ID and destination too. This is application-authored row layout; joining
only adjacent soft-wrapped rows cannot reconstruct the intended hover group.

`TerminalHyperlinkControllerTest` replays the actual bytes through the session,
checks cell identity and destination preservation, and activates each displayed
segment through a recording handler. Chunk variants split OSC, UTF-8, and
styling sequences. Observed hover bounds are diagnostic output, not assertions
that the current broken grouping should remain.

Replay locates the five URL segments at zero-based rows **9–13**, columns
**[1,175)**, and the caption at row **17**, columns **[1,29)**. All six have
ID `1` and `lineWrapped=false`; the last URL row contains eight target
characters followed by linked padding. Hovering each segment currently
reports only that row's bounds. These observations reproduce the reported
presentation failure while activation still resolves the complete target.

The capture is a fresh reproduction of the reported screen. The screenshot's
original geometry and executable version cannot be recovered from pixels.
The installed executable had no Windows file/product-version metadata.

## Lifecycle evidence

`SwingTerminalHyperlinkLifecycleTest` uses a real Swing component, actual
container removal/reattachment, controlled session scheduling and explicit
queued UI handoffs. No elapsed-time threshold establishes correctness.

| Transition | Detected link available | Detector calls | Content generation |
|---|---|---:|---:|
| Initial publication | Yes | 1 | 31 |
| Focus loss/return, no output | Yes | 1 | 31 |
| Component removed | No | 1 | 31 |
| Component reattached, before another frame | No | 1 | 31 |
| Explicit frame publication | Yes | 2 | 31 |

The diagnostic entry point records missing links without encoding them as
expected test behavior. The three tests assert successful full-target
activation, preservation across pure focus changes, and retained text/binding
plus recovery after a frame. The no-frame recovery assertion belongs with the
lifecycle fix.

Source explains the trace: `SwingTerminal.removeNotify()` resets discovery;
`addNotify()` does not reschedule it. Scheduling currently follows consumed
render publications. This is a confirmed detach/reattach failure, not proof
that every IDE tool-window hide or operating-system application switch emits
that sequence. Native tool-window/application-switch event tracing remains
unverified; pure focus notifications alone did not reproduce the report.

## Shared discovery measurements

`TerminalHyperlinkDiscoveryCompletionBenchmark` measures changed-frame
submission on the EDT through completed background analysis and EDT result
publication. A reusable completion handshake prevents measuring submission
alone. Scenarios contain the stated number of URLs on every row; the final
character of the last URL changes on every update. Completion requires that
link's published action to open the new complete destination. Immediate
invalidation and duplicate notifications cannot complete the operation.

The deterministic detector uses the current IntelliJ-style full-viewport
context. Each result constructs a target string and a capturing action, so
those discovery allocations are included. SDK filters, read actions and
painting are excluded. Work counters report actual detector calls, lines and
results; they are not optimization targets that require repeated work.

| Grid / URLs per row | Mean elapsed | p95 | p99 | Allocated bytes / completed frame | Analyzed lines / results |
|---|---:|---:|---:|---:|---:|
| 80×24 / 1 | 37.067 µs | 59.600 µs | 89.216 µs | 9,448 | 24 / 24 |
| 160×48 / 5 | 121.707 µs | 172.032 µs | 217.856 µs | 42,274 | 48 / 240 |
| 240×48 / 8 | 156.101 µs | 226.304 µs | 281.600 µs | 61,424 | 48 / 384 |

Every completed update made one detector call in this baseline, rescanning all
rows despite changing only one URL character. Allocations include worker/EDT work,
dispatch and the measurement handshake. They are **not** recurring paint-path
allocation measurements.

Runtime: Windows 10.0.26300 AMD64, AMD Family 25 Model 117, JBR
25.0.4.1+1-b610.67, JMH 1.37. Two forks, three one-second warmups and five
one-second measurements per scenario; sample-time mode and GC profiler.
The selected run had no concurrent agent build, but external workstation activity was
uncontrolled and iteration timings varied substantially. These short runs
establish diagnostic reference points, not latency acceptance thresholds.

Local raw evidence is under `ketraterm-benchmarks/build/results/jmh/`:
`hyperlink-stage0-completion.json`, `.log`, and `.metadata.json`. The metadata
records source and harness hashes. Earlier progress-only experiments are
preserved separately under the `hyperlink-stage0-completion-progress` prefix;
they are not the active-discovery workload above. Changing an actual
destination ensures a future correct optimization still has semantic work
to complete. Setup verifies initial coverage, while measurement-time counters
allow later incremental implementations to reduce detector work.

## Frame preparation and storage

The existing `TerminalHyperlinkDiscoveryBenchmark` isolates EDT frame copying,
link carry and scheduling at 80×24 with one link per row. Its worker is paused
after initial discovery; 256 frames amortize each EDT dispatch. Under the same
runtime, fork/warmup/measurement counts and GC profiler:

| Frame workload | Mean time / frame, JMH error | Allocated bytes / frame, JMH error |
|---|---:|---:|
| Unchanged text | 3.999 ± 0.519 µs | 1.017 ± 0.259 |
| Last-row progress changes | 5.774 ± 0.458 µs | 657.102 ± 0.464 |

This includes amortized dispatch and all-thread GC accounting. It does not
measure active detector completion, painting, or EDT-exclusive allocation,
and the near-one-byte unchanged result is not proof of strict zero allocation.
Raw evidence uses the `hyperlink-stage0-frame-carry` prefix in the same local
JMH results directory. The run had no concurrent agent build.

Source-based retained-storage accounting, **not a measured heap size**:
two overlay `IntArray`s contribute `8RC` bytes; snapshot cell-start/end maps
contribute `8U`; snapshot row IDs, generations, fingerprints and wrap flags
contribute nominally `25R`. Here `R` includes cached rows/overscan, `C` is
columns, and `U` is mapped UTF-16 length after trimming. Builder mapping
scratch adds `8B` bytes, initially `B=256`.

For 80×24 fully occupied ASCII rows (`U=1920`), this is 31,320 bytes plus
initially 2,048 bytes of scratch. It excludes strings, headers, collections,
actions, coroutine requests and render-cache planes; Unicode can increase
`U`. Arrays/list capacities retain their high-water marks until collection.
One in-flight request can also retain an earlier viewport. Host actions may
retain objects beyond these structures, so the formula is no heap-retention
guarantee.

## IntelliJ provider measurements

`IntellijTerminalHyperlinkBaselineTest` runs the actual detector against SDK
**IU-262.8665.258** and its seven registered console providers. A disposable
no-op observer measures provider construction and entry without replacing the
SDK providers. Inputs use reserved web domains and a real temporary project
file; navigation is never executed.

Each workload has two warmup requests followed by five measured requests.
Elapsed time is recorded in JSON stdout, never used as a unit-test threshold.

| Input | 32 logical lines, median | 128 lines, median | 512 lines, median |
|---|---:|---:|---:|
| Eight web URLs per line | 1.994 ms | 1.580 ms | 6.688 ms |
| Mixed web/file URI/path/stack-trace rows | 2.285 ms | 3.389 ms | 7.899 ms |
| File URI/path/stack-trace rows | 0.783 ms | 1.476 ms | 2.837 ms |

The 512-line web workload returned 4,096 links; its slowest measured request
took 39.499 ms. The observer was reconstructed five times in every measured
workload. Web-only lines never reached it: the current URL result bypasses
console filtering on that line. These counts describe the observer, not every
internal SDK filter invocation.

The fixture verified HTTP and `file:` URI ranges. Its bare paths and Java
stack-trace rows exercised the real providers but produced no additional
links; the table does not establish successful path/stack-trace navigation or
full terminal parity. It excludes Swing copies, projection, painting, queueing
and native IDE activity. Provider allocations and retained heap have not yet
been profiled in a running IDE.

A separate controlled indexing test explicitly resubmits the same input in
smart → dumb → smart mode. It observes **2 → 1 → 2** results and verifies that
the index-dependent provider is skipped while indexing. This proves provider
availability after resubmission, not automatic UI reanalysis after indexing.

The selected first measurement's raw JSON records and loaded provider names
are preserved locally in
`ketraterm-intellij-plugin/build/reports/hyperlink-baseline/stage0-first-measurement.xml`.
Subsequent verification runs write fresh measurements to the ordinary
`build/test-results/test/TEST-io.github.ketraterm.intellij.ui.IntellijTerminalHyperlinkBaselineTest.xml`.

## Architectural evidence

- `TerminalHyperlinkViewport` owns only cache-visible logical lines and
  overscan. Leaving that range discards results, including successful empty
  results. A changed discarded-row count clears everything, including
  surviving history. Full-viewport context invalidates provider-dependent
  results on viewport/text changes.
- `TerminalHyperlinkController.resolveHoveredSpan()` follows cell adjacency
  and soft-wrap flags. The agy capture carries the correct semantic ID and
  destination already; the loss occurs while resolving the hover geometry.
- Overlay actions are rebuilt with negative array-position IDs.
  `SwingTerminal` context-menu closures retain those IDs, not stable action
  snapshots; later overlay updates can change their meaning.
- `IntellijTerminalHyperlinkDetector` rebuilds provider filters for every
  request inside one read action, and its URL-first branch skips providers
  when a URL matches. The neutral result API drops presentation, visibility,
  hover callbacks and popup metadata. Ordered filters have no retained
  cross-viewport state or result-producer dependency position.
- Session absolute-range render reads already exist. There is no need for
  URI parsing in core or a second terminal/document model. The current
  content generation cannot by itself distinguish a history rewrite/reflow
  from ordinary append and eviction.

The reference design was checked against the targeted IntelliJ SDK sources
and JetBrains' [filter wrapper](https://github.com/JetBrains/intellij-community/blob/master/plugins/terminal/src/org/jetbrains/plugins/terminal/hyperlinks/filter/CompositeFilterWrapper.kt),
[frontend hyperlink processing](https://github.com/JetBrains/intellij-community/blob/master/plugins/terminal/frontend/src/com/intellij/terminal/frontend/view/hyperlinks/FrontendTerminalHyperlinksProcessing.kt)
and [result metadata](https://github.com/JetBrains/intellij-community/blob/master/plugins/terminal/src/org/jetbrains/plugins/terminal/hyperlinks/TerminalFilterResultInfo.kt).
Their ownership and metadata inform the adapter; their editor/document
machinery is not a proposed shared-module dependency. The
[OSC 8 specification](https://gist.github.com/egmontkob/eb114294efbcd5adb1944c9f3cb5feda)
defines explicit-ID grouping separately from anonymous same-destination links.
The public console-filter API does not guarantee fresh provider instances or
a reset operation; arbitrary singleton third-party state cannot be assumed
replayable.

## Reproduction commands

Run formatting first, then the focused tests from the repository root:

```text
./gradlew spotlessApply
./gradlew :ketraterm-ui-swing:test --tests '*TerminalHyperlink*Test' --tests '*SwingTerminalHyperlinkLifecycleTest'
./gradlew -p ketraterm-intellij-plugin spotlessApply test --tests '*IntellijTerminalHyperlinkBaselineTest' --tests '*IntellijTerminalUrlFilterTest'
./gradlew :ketraterm-benchmarks:jmhJar
```

Run the generated JMH jar with a JDK 25 runtime, without concurrent builds:

```text
java -jar ketraterm-benchmarks/build/libs/ketraterm-benchmarks-0.3.0-SNAPSHOT-jmh.jar TerminalHyperlinkDiscoveryCompletionBenchmark -f 2 -wi 3 -w 1s -i 5 -r 1s -bm sample -prof gc -rf json -rff hyperlink-stage0-completion.json
java -jar ketraterm-benchmarks/build/libs/ketraterm-benchmarks-0.3.0-SNAPSHOT-jmh.jar 'TerminalHyperlinkDiscoveryBenchmark.refreshFrame' -f 2 -wi 3 -w 1s -i 5 -r 1s -prof gc -rf json -rff hyperlink-stage0-frame-carry.json
```

Both `SwingTerminalHyperlinkLifecycleTest` and
`TerminalHyperlinkControllerTest` have diagnostic `main` entry points; run
them from the IDE using the UI module's test runtime classpath. Their output
distinguishes focus return, component reattachment before a frame, explicit
frame recovery and agy hover geometry.

Verification passed: **79 focused Swing tests and 12 IntelliJ tests**, zero
failures/errors/skips, root and plugin formatting, and benchmark harness
compilation. `graphify update .` completed; its Kotlin parser reported partial
extraction in three existing files (`IntellijTerminalHyperlinkDetector`,
`TerminalParserTest`, `SwingTerminalSearchBar`), so exact source reads remain
authoritative. No production code, public API or changelog changed.

Stage 0 contains no failing/skipped bug assertions. Native focus/show event
capture, prepared-scroll allocation budgets, retained-history memory, and
real IDE allocation profiling are not established by these measurements.
