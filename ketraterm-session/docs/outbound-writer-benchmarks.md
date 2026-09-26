# Outbound writer checkpoint measurements

Measured on Windows with Eclipse Temurin 25, comparing committed checkpoint
`f43b01f2` with the bounded session writer. The new
`TerminalSessionOutputBenchmark` was copied into the isolated baseline checkout
so both implementations ran the same workload. Measurements used JMH's GC
profiler, one fork, two 1-second warmups and three 1-second measurements.

These are short local measurements with wide confidence intervals, not portable
performance guarantees. They expose the cost of asynchronous dispatch and check
for allocation changes; they do not measure real PTY latency or a blocked pipe.
The deterministic session tests cover blocked output separately.

| Workload | Baseline ops/ms | Queued ops/ms | Baseline B/op | Queued B/op |
| --- | ---: | ---: | ---: | ---: |
| Published render-cache consumption, one session | 634 | 670 | 0.084 | 0.075 |
| Legacy ASCII input encoder | 35,672 | 36,907 | < 0.001 | < 0.001 |
| Session ASCII keys, bursts of 64, per key | 91,604 | 6,400 | 0.001 | 5.469 |
| Session paste, 64 KiB | 7.336 | 4.728 | 12.735 | 47.664 |

Both output workloads wait for the counting connector to consume every byte.
They therefore compare completed output, rather than comparing old completion
with new acceptance. The synchronous baseline's connector does no blocking I/O.
The queued implementation includes an I/O dispatcher handoff and wake-up costs.
Its byte payload storage is reused; coroutine/channel scheduling still allocates.
Do not describe the complete input path as allocation-free.

The unchanged encoder and render-cache workloads remain near the measurement
floor for allocation. Their throughput differences are within the noise of this
short run; no speedup is claimed. The output measurements show a substantial
throughput cost for thread handoff against a no-op synchronous sink. The benefit
is that slow real writes no longer occupy parser/input monitors or the UI thread.

## Reproduction

Build `:ketraterm-benchmarks:jmhJar` in both checkouts. Run the Java 25 executable
with the corresponding JMH jar:

```text
java -jar <jmh-jar> "TerminalSessionOutputBenchmark|TerminalInputBenchmark.encodeAsciiKeyLegacy|TerminalCoroutineSessionBenchmark.consumePublishedCaches" -p sessionCount=1 -wi 2 -i 3 -w 1s -r 1s -f 1 -prof gc -rf json -rff <results.json>
```

Use longer measurements and more forks before drawing throughput conclusions
for a release. The raw JSON/logs for this comparison are local build artifacts
named `osc52-output-{baseline,current}-jmh`; they are not checked in.
