# Consumer authority and render-reader ABI

Session consumers use `shellIntegrationState: TerminalShellIntegrationView` for
live queries, primitive copies and observation. The producer retains its
`TerminalShellIntegrationState` for recording and clearing. The state implements
the view directly: no copied model, forwarding object or second lifecycle is
introduced. Individual reads are thread-safe; multiple reads need not observe
the same revision. Listener registrations belong to their consumers, and closing
a session does not close or clear a host's model.

```kotlin
val producer = TerminalShellIntegrationState()
val session = TerminalSession.create(
    terminal, connector,
    shellIntegration = TerminalShellIntegrationFactory.host(producer),
)
val consumer: TerminalShellIntegrationView = session.shellIntegrationState
producer.recordCurrentWorkingDirectory("file:///project")
check(consumer.currentWorkingDirectoryUri() == "file:///project")

session.readPublishedFrame { cache ->
    drawCells(cache.codeWords, cache.columns, cache.rows)
}
```

`readPublishedFrame` borrows the latest copied cache without exposing session's
publisher. It is safe from any thread and remains usable after closure. Before
first publication it returns null without invoking the callback. It does not
request publication; use `requestRender` and observe `renderGeneration` when a
fresh viewport is required. The callback must not mutate or retain the cache or
its arrays, close the session, or reenter session mutation/publication. Borrowed
primitive storage remains available without an immutable-array wrapper; this is
an ownership contract, not protection against deliberate casts or array writes.

A standalone `TerminalRenderPublisher` remains mutable for its actual owner.
Its `readCurrent` and session's `readPublishedFrame` inline only the callback and
`try/finally`, preserving Kotlin non-local returns and failure cleanup. Non-inline
acquisition returns an existing cache reference; release resolves that reference
privately. There is no per-read lease object. Buffer count, indexing, locks and
reader counts are no longer reader ABI.

Publisher acquire/release methods are public because session is a separate
module. Prefer the scoped readers. Manual bridge users must pair each non-null
acquisition exactly once in `finally`; session's own bridges are internal and
JVM-synthetic. Foreign or unleased references reject without damaging counters.
Duplicate release while another reader holds the same cache is caller misuse
that a reference witness cannot distinguish without allocating a lease token.

## Verification and migration

The baseline root suite and ABI checks passed. Three focused regressions then
failed on the original API: `TerminalHostShellIntegrationTest.session consumer
API does not expose shell publication authority`, its companion render-publisher
test, and `TerminalRenderPublisherTest.reader ABI does not expose buffer
bookkeeping`. External Java compilation tests now reject publication/clearing
through session reads while accepting ordinary observation and frame borrowing.
Owner tests cover empty publication, failure, non-local return, pinned concurrent
readers, buffer recycling, invalid release and retained reads after closure.
Existing projection boundary/overflow and listener lifecycle tests retain their
assertions against the same owner implementation.

This is an intentional pre-freeze source and binary change. Replace
`session.renderPublisher.readCurrent { ... }` with
`session.readPublishedFrame { ... }`. Producers must retain the state passed to
their integration rather than recover it from a session. `TerminalShellIntegration.state`
also exposes the view; concrete producers may override it with their retained
mutable state. Recompile affected clients, including callers that inlined the
old publisher lease algorithm or shell projection default-call descriptors.
See the [compatibility contract](library-compatibility.md) for the recorded
retained-client baseline changes.

## Allocation measurement

`TerminalRenderLeaseBenchmark` measures warmed, uncontended empty/published leases
over 80×24 frames, a session reader with a capturing callback, and primitive shell
viewport projection. A deliberately
escaping callback is the allocation control. Setup/publication and Swing painting
are outside these measured reads. Bytecode inspection also confirms that scoped
readers contain no lease construction or buffer indexing.

Run on JVM 25 with JMH 1.37 and its GC profiler:

```text
./gradlew :ketraterm-benchmarks:jmhJar
java -jar ketraterm-benchmarks/build/libs/ketraterm-benchmarks-0.3.0-SNAPSHOT-jmh.jar TerminalRenderLeaseBenchmark -wi 3 -i 5 -w 1s -r 1s -f 2 -prof gc
```

Local Windows measurements on Temurin 25.0.3, two forks, ten measured samples
(mean ± JMH's reported error):

| Operation | ns/op | GC-profiler B/op |
| --- | ---: | ---: |
| Published publisher read, before | 17.12 ± 1.04 | < 0.001 |
| Published publisher read, after | 14.78 ± 2.03 | 0.001 ± 0.004 |
| Published session read, after | 19.76 ± 3.73 | 0.0006 ± 0.0017 |
| Shell view, 24-row projection | 165.11 ± 28.77 | 0.0040 ± 0.0116 |
| Escaping callback control, published | 24.96 | 16.00 |

Empty publisher/session reads likewise measured below 0.002 B/op. Session and
shell rows use a confirmation run with five one-second warmups and five
three-second measurements per fork (`-p frame=published -wi 5 -r 3s`). Longer
samples reduced the small allocation residuals; the 16-byte control remained
clearly distinguishable in the original run.

Together with source/bytecode inspection, these results support preserved zero
terminal-owned steady-state allocation for these operations. The GC profiler
also counts JVM, harness and background activity: residual totals are not a claim
of literally zero JVM allocation. Concurrent build activity and local timing
variance preclude a speedup claim. These measurements exclude UI-platform costs,
frame copying/growth, metadata snapshot requests and caller-created callbacks.
