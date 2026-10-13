# KetraTerm benchmarks

JMH measurements for parser, core, input, session, render-cache, completion, and
Swing behavior. This is a development module, not a consumer library.

## Build and run

Use JDK 25 and the repository Gradle wrapper; see the
[development prerequisites](../CONTRIBUTING.md). Run commands
from the repository root. On Windows, use `gradlew.bat` in place of `./gradlew`.

Compile and package the benchmark harnesses without running measurements:

```text
./gradlew :ketraterm-benchmarks:jmhJar
```

The executable JAR is written under `ketraterm-benchmarks/build/libs`. Replace
`<jmh-jar>` below with its path. List available benchmarks and command options:

```text
java -jar <jmh-jar> -l
java -jar <jmh-jar> -h
```

Run a selected warmed parser workload and save the raw results:

```text
java -jar <jmh-jar> ".*TerminalParserBenchmark.parseIsolated" -p workload=ascii -wi 3 -i 5 -w 1s -r 1s -f 3 -t 1 -prof gc -rf json -rff parser.json
```

Filters are regular expressions over benchmark names. `-p` selects an annotated
parameter; inspect the benchmark's source for available values. An invocation may
process a whole input buffer or batch, so an operation is not necessarily one
byte, cell, or key.

The full warmed suite is available through:

```text
./gradlew :ketraterm-benchmarks:jmh
```

The [Gradle configuration](build.gradle.kts) defines suite defaults and excludes
the first-input benchmark. Direct JAR runs use annotations unless overridden.

## Select a measurement boundary

| Question | Representative benchmarks |
| --- | --- |
| Parsing without core mutation | `TerminalParserBenchmark`, `TerminalGraphemeBenchmark` with `pipeline=parser` |
| Core writes, scrolling, erasure, and frame copying | `TerminalCoreWriteBenchmark`, `TerminalCoreScrollBenchmark`, `TerminalCoreEraseBenchmark`, `TerminalBufferBenchmark` |
| History disposal and reuse after extended attributes are cleared | `TerminalStorageLifecycleBenchmark` |
| Input encoding and session output through transport completion | `TerminalInputBenchmark`, `TerminalPasteBenchmark`, `TerminalSessionOutputBenchmark` |
| Published-frame access and viewport projection | `TerminalRenderLeaseBenchmark`, `RetainedFrameViewportBenchmark` |
| Completion evaluation and directory scanning | `TerminalCompletionBenchmark`, `TerminalDirectoryCompletionBenchmark`, `TerminalLearnedRankingBenchmark` |
| Swing text, search, hyperlinks, and painting | `TerminalTextRenderingBenchmark`, `TerminalSearchScanBenchmark`, `TerminalSearchRefreshBenchmark`, `TerminalHyperlinkDiscoveryBenchmark`, `SwingPaintBenchmark` |

Read the selected fixture before interpreting a result. Isolated parsing uses a
no-op command sink; frame publication is separate from painting; directory scans
use a temporary directory on the filesystem selected by the JVM. These workloads
measure different boundaries and cannot substitute for an end-to-end terminal
measurement.

### Reverse-wrap cursor motion

[TerminalReverseWrapBenchmark](src/jmh/kotlin/io/github/ketraterm/benchmark/TerminalReverseWrapBenchmark.kt)
measures direct core and parser/host CUB with `Int.MAX_VALUE` on a fixed 80×24
soft-wrapped screen. `mode=0` measures ordinary clamping, `45` checks wrapped
rows, and `1045` cycles using cell-offset arithmetic. Each operation includes
cursor positioning and CUB; construction, seeding, and input encoding occur
outside measurement. Trial teardown verifies the final cursor and history.

```text
java -jar <jmh-jar> ".*TerminalReverseWrapBenchmark.*" -p mode=0,45,1045 -wi 3 -i 3 -w 1s -r 1s -f 2 -t 1 -prof gc -rf json -rff reverse-wrap.json
```

### Application mouse behavior

[TerminalMouseBehaviorBenchmark](src/jmh/kotlin/io/github/ketraterm/benchmark/TerminalMouseBehaviorBenchmark.kt)
measures one packed routing-state read or four parser/host preference changes.
The terminal, parser, and bytes are constructed before measurement. Trial
teardown verifies the resulting flags and untouched cursor/history. The GC
profile covers these primitive state/protocol paths, rather than Swing event
construction or an entire painted frame.

```text
java -jar <jmh-jar> ".*TerminalMouseBehaviorBenchmark.*" -wi 3 -i 3 -w 1s -r 1s -f 2 -t 1 -prof gc -rf json -rff mouse-behavior.json
```

### First parser input

[TerminalParserFirstInputBenchmark](src/jmh/kotlin/io/github/ketraterm/benchmark/TerminalParserFirstInputBenchmark.kt)
measures one parser input in each fresh JVM fork. Parser construction and payload
encoding happen before timing; lazy initialization triggered by the first input
is included. Its setup requires single-shot mode, one thread, no warmup, one
measurement iteration, and a batch size of one.

```text
java -jar <jmh-jar> ".*TerminalParserFirstInputBenchmark.*" -wi 0 -i 1 -f 15 -t 1 -bm ss -tu us -prof gc -rf json -rff first-input.json
```

GC-profiler results include harness activity and do not isolate Unicode table
initialization allocation.

### Storage lifecycle

[TerminalStorageLifecycleBenchmark](src/jmh/kotlin/io/github/ketraterm/benchmark/TerminalStorageLifecycleBenchmark.kt)
uses public core operations on a 120×24 terminal. `discardHistory` measures ED 3
while preserving visible content and the cursor; `clearAll` also clears the
viewport and homes the cursor. Both are single-shot measurements with 10,000
history rows. `content=ascii` fills 119 cells; `cluster` replaces the first cell
with a two-codepoint combining cluster. `clusterEnd` fills the first 119 cells
with ASCII and puts a cluster in the final column; `denseClusters` fills all
120 columns with clusters. The latter two cases exercise disposal when cluster
handles extend to the row's end and when every cell owns a handle.
Each iteration repopulates the same buffer with preallocated payloads outside
timing. Iteration teardown checks history disposal, visible cells, clusters,
and cursor semantics.

```text
java -jar <jmh-jar> ".*TerminalStorageLifecycleBenchmark.(discardHistory|clearAll)" -bm ss -wi 15 -i 15 -f 2 -t 1 -prof gc -rf json -rff storage-history.json
```

Keep single-shot mode and a batch size of one for those two methods. GC-profiler
totals include history repopulation and semantic teardown; they do not isolate
allocation in history disposal.

`writeShiftErase` writes an ASCII row, inserts/deletes a cell at its midpoint,
and erases its trailing half for all 24 rows. `clearScreen` repeatedly clears an
already blank viewport. `extendedHistory=never` uses rows that have never held
extended attributes; `cleared` first writes every cell with a hyperlink and
clears the viewport with a zero hyperlink pen. These warmed methods retain the
same rows and have no invocation or iteration setup. One operation covers one
viewport; trial teardown verifies default attributes and the resulting cells.

```text
java -jar <jmh-jar> ".*TerminalStorageLifecycleBenchmark.(writeShiftErase|clearScreen)" -wi 3 -i 5 -w 1s -r 1s -f 2 -t 1 -prof gc -rf json -rff storage-steady.json
```

### Fresh-terminal log ingestion

[TerminalLogIngestionBenchmark](src/jmh/kotlin/io/github/ketraterm/benchmark/TerminalLogIngestionBenchmark.kt)
ingests a fixed 6,000-line ASCII log into a fresh fixture for each shot. Its
`pipeline` parameter selects direct core writes, isolated parsing, or parser-to-core
ingestion. Core and host variants verify retained cells, wrapping, history
eviction, and the final cursor during teardown.

```text
java -jar <jmh-jar> ".*TerminalLogIngestionBenchmark.ingest" -p pipeline=host -p columns=80 -p maxHistory=13107 -p chunkBytes=4096 -bm ss -wi 60 -i 30 -f 3 -t 1 -tu ms -prof gc -rf json -rff log-ingestion.json
```

Keep single-shot mode and a batch size of one. This workload excludes transport,
session publication workers, compatibility projection, and frontend rendering.
GC-profiler totals include fixture setup and must not be reported as ingestion
allocation alone.

## Report results

Record the Git revision, JDK build, JVM arguments, OS, CPU, filesystem or graphics
environment where relevant, complete command, parameter values, and raw JMH
output. Compare the same workload, timing boundary, warmup, forks, and profiler
settings. Check semantic tests before attributing a difference to an optimization.

Report score uncertainty and allocation units alongside the score. Small or
rounded allocation results do not establish that a public API never allocates;
setup, harness activity, background work, and warmed caches can affect the result.
Use additional forks and repeated runs when a difference is close to measurement
noise.

See [Module.md](Module.md) for source sets and harness dependencies. The
[Swing rendering guide](../ketraterm-ui-swing/docs/bifurcated-text-rendering.md)
provides rendering workload context.

`TerminalStorageLifecycleBenchmark.rewriteClusters` rewrites all 120 × 24 cells
with prebuilt clusters of 2 or 32 codepoints. The buffer and payload are created
once per trial; allocation profiling measures sustained writes and payload reuse
after JMH warmup. Trial teardown verifies every payload and cursor placement.
`resizeClusters -p length=32` alternates 2- and 32-codepoint payloads across that
viewport to force dead regions and compaction. Its payloads are also prebuilt;
the final retained prefix is verified outside the measured loop.
