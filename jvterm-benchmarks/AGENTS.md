# Terminal Benchmarks Agent Guide

`terminal-benchmarks` owns JMH benchmarks for performance-sensitive terminal
paths.

## Boundary

Benchmarks may depend on production modules to measure public behavior, but they
must not introduce production-only APIs or change terminal semantics for easier
measurement.

Keep benchmark setup realistic and explicit. Prefer stable, repeatable terminal
content over random data unless the benchmark is specifically measuring a random
workload.

## Testing

Benchmark code should compile with:

```text
./gradlew :terminal-benchmarks:compileKotlin
```

Run JMH when performance numbers are needed:

```text
./gradlew :terminal-benchmarks:jmh
```
