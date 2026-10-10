# Terminal Benchmarks Agent Guide

`ketraterm-benchmarks` owns the JMH runner and benchmarks of public terminal APIs.
Read [Module.md](Module.md) before changing benchmark source-set wiring.

## Boundary

Benchmarks may depend on production modules to measure public behavior, but they
must not introduce production-only APIs or change terminal semantics for easier
measurement.

Keep benchmark setup realistic and explicit. Prefer stable, repeatable terminal
content over random data unless the benchmark is specifically measuring a random
workload.

Separate payload generation and fixture construction from the measured operation
when measuring steady-state behavior. Document setup, teardown, batching, and
reset boundaries; allocation profiling can include work outside the timed method.
Consume observable results and verify terminal semantics without putting assertions
inside a timed operation unless their cost is deliberately part of the workload.

## Testing

Benchmark code should compile with:

```text
./gradlew :ketraterm-benchmarks:jmhJar
```

Run semantic tests in the module whose behavior is measured. Harness compilation
checks benchmark integration; it does not establish performance or correctness.
Use the [benchmark guide](README.md) for running selected measurements. Preserve
benchmark modes and cold-start preconditions rather than applying warmed-suite
settings to every workload.

Keep allocation measurements in JMH with its GC profiler. Unit tests should
assert rendering, cache reuse, invalidation, and lifecycle semantics without
depending on JVM allocation counters or warmup timing. Keep internal Swing
benchmarks in the Swing module's associated `jmh` compilation so Gradle and IDE
visibility agree; do not widen production visibility for measurement.

Record the revision, environment, complete command, workload parameters, and raw
results with performance claims. Compare equivalent measurement boundaries and
report uncertainty; do not turn a historical result into an API guarantee.
