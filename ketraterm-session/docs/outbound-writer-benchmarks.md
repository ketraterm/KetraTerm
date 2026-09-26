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

## Checkpoint 2b: background bulk encoding

Compared committed checkpoint `5e9eb30b` with background paste/replacement
encoding using the same Java 25 runtime and completed-output benchmarks. The
first comparison used the short one-fork settings above:

| Workload | Checkpoint 2a ops/ms | Checkpoint 2b ops/ms | Checkpoint 2a B/op | Checkpoint 2b B/op |
| --- | ---: | ---: | ---: | ---: |
| Published render-cache consumption, one session | 743 | 333 | 0.054 | 0.123 |
| Legacy ASCII input encoder | 35,380 | 37,289 | < 0.001 | < 0.001 |
| Session ASCII keys, bursts of 64, per key | 5,239 | 5,314 | 5.535 | 7.172 |
| Session paste, 64 KiB | 4.639 | 8.247 | 50.400 | 128.346 |

The initial key allocation difference prompted a longer comparison: two forks,
three 1-second warmups and five 1-second measurements per fork.

| Session keys, per key | Checkpoint 2a | Checkpoint 2b |
| --- | ---: | ---: |
| Throughput, ops/ms | 5,498 ? 653 | 7,187 ? 1,972 |
| Allocation, B/op | 5.988 ? 1.328 | 5.656 ? 3.023 |

The intervals overlap; this repeat did not reproduce the initial allocation
increase. Bytecode inspection also found no allocation instructions or boxed
counters in the new writer drain. Ordinary keys still use reusable byte storage,
with allocation from coroutine/channel scheduling. These measurements do not
justify a general throughput improvement claim.

Bulk requests now allocate a captured operation and queue record once per
paste/replacement. They retain the immutable source and stream encoding through
fixed scratch rather than growing the byte ring to fit the entire payload.
The higher paste B/op reflects this fixed request cost; it is neither a
per-byte cost nor a render-frame cost. The deterministic tests additionally
verify output beyond 8 MiB, the shared 16-operation / 16,777,216-work-unit budget,
and queued/active reservations under blocked writes and close.

The unchanged render-cache workload varied substantially in throughput in these
short runs; its allocation remains near the measurement floor. No render-path
speedup or regression is established by this comparison. Real PTY throughput
and native latency are outside this counting-connector benchmark.

Raw JSON/logs are local build artifacts named
`osc52-bulk-{baseline,current}-jmh` and `osc52-bulk-{baseline,current}-keys`.
For the longer run, select `TerminalSessionOutputBenchmark.keys` and use
`-wi 3 -i 5 -w 1s -r 1s -f 2 -prof gc` with the command below.

## Checkpoint 3: headless clipboard reads

Compared committed checkpoint `c21362c1` with the headless read lifecycle on
Java 25. Both used two forks, three 1-second warmups and five 1-second
measurements per fork, with the GC profiler and the same completed-output
workloads. No clipboard requests are issued by these workloads: the comparison
checks whether the added lifecycle/reservation changes ordinary input or render
consumption when clipboard reads are idle.

| Workload | Baseline ops/ms | Read-capable ops/ms | Baseline B/op | Read-capable B/op |
| --- | ---: | ---: | ---: | ---: |
| Published render-cache consumption, one session | 609 | 603 | 0.056 | 0.045 |
| Session ASCII keys, bursts of 64, per key | 5,691 | 6,608 | 5.432 | 4.679 |
| Session paste, 64 KiB | 6.932 | 7.083 | 123.619 | 126.043 |

The confidence intervals overlap for throughput and allocation. These runs show
no measurable regression and do not establish a speedup. The render workload
remains near the allocation measurement floor. Normal byte draining adds no
payload objects or boxed counters; clipboard requests allocate only when
admitted. Clipboard preparation deliberately allocates bounded UTF-8/Base64
storage outside parser/input locks and is covered by correctness/resource tests,
not a claim of allocation-free clipboard execution.

Validation used an ignored Gradle initialization script that redirected build
outputs, because a running standalone instance held the normal JARs open on
Windows. The isolated baseline checkout was removed after measurement. Raw
JSON/logs remain local build artifacts named
`osc52-read-{baseline,current}-jmh`.

Reproduce with the command below, selecting
`TerminalSessionOutputBenchmark|TerminalCoroutineSessionBenchmark.consumePublishedCaches`
and using `-wi 3 -i 5 -w 1s -r 1s -f 2`.

## Reproduction

Build `:ketraterm-benchmarks:jmhJar` in both checkouts. Run the Java 25 executable
with the corresponding JMH jar:

```text
java -jar <jmh-jar> "TerminalSessionOutputBenchmark|TerminalInputBenchmark.encodeAsciiKeyLegacy|TerminalCoroutineSessionBenchmark.consumePublishedCaches" -p sessionCount=1 -wi 2 -i 3 -w 1s -r 1s -f 1 -prof gc -rf json -rff <results.json>
```

Use longer measurements and more forks before drawing throughput conclusions
for a release. The raw JSON/logs for this comparison are local build artifacts
named `osc52-output-{baseline,current}-jmh`; they are not checked in.
