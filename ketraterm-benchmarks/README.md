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
| Input encoding and session output through transport completion | `TerminalInputBenchmark`, `TerminalPasteBenchmark`, `TerminalSessionOutputBenchmark` |
| Published-frame access and viewport projection | `TerminalRenderLeaseBenchmark`, `RetainedFrameViewportBenchmark` |
| Completion evaluation and directory scanning | `TerminalCompletionBenchmark`, `TerminalDirectoryCompletionBenchmark`, `TerminalLearnedRankingBenchmark` |
| Swing text, search, hyperlinks, and painting | `TerminalTextRenderingBenchmark`, `TerminalSearchRefreshBenchmark`, `TerminalHyperlinkDiscoveryBenchmark`, `SwingPaintBenchmark` |

Read the selected fixture before interpreting a result. Isolated parsing uses a
no-op command sink; frame publication is separate from painting; directory scans
use a temporary directory on the filesystem selected by the JVM. These workloads
measure different boundaries and cannot substitute for an end-to-end terminal
measurement.

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
