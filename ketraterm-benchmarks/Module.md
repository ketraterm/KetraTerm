# Module ketraterm-benchmarks

JMH runner for terminal performance measurements. [README.md](README.md) covers
running workloads and interpreting results.

## Dependencies

The runner depends on completion, completion-host, core, host, parser, input,
protocol, render-api, render-cache, session, and ui-swing to measure their APIs.
JMH core and its harness generator provide measurement infrastructure; Kotlin
coroutines support asynchronous workloads. These are development dependencies,
not part of the published terminal library.

## Source sets and harness

Public-API benchmarks live in [src/jmh](src/jmh/kotlin/io/github/ketraterm/benchmark).
Benchmarks that need internal Swing helpers live in
[the Swing module's associated JMH compilation](../ketraterm-ui-swing/src/jmh/kotlin/io/github/ketraterm/benchmark).
The central runner generates and packages both sets in one executable JAR without
widening production visibility.

`swingBenchmarkClasses` consumes the Swing module's `benchmarkElements` variant
without transitive dependencies. `jmhImplementation` extends this configuration,
and `jmhRunBytecodeGenerator` consumes its classes to produce a shared benchmark
list and JAR.

The [build script](build.gradle.kts) defines suite defaults; individual benchmark
annotations define workload parameters and direct-JAR defaults.
