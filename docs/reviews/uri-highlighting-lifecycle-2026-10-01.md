# URI highlighting scheduling and lifecycle — Stage 3

Stage 3 builds on the committed retained index (`11082887`) on
`fix/uri-highlighting`. The [repair map](../terminal-feature-gap-map.md#uri-highlighting-staged-repair)
owns scope and the remaining stages. Changes are uncommitted for user verification.

## Ownership and scheduling

Discovery belongs to the terminal binding. Removing or hiding the Swing component
preserves prepared results and pending analysis. Unbind, binding replacement and
disposal cancel owned work and release retained text/actions. A provider that ignores
cancellation retains the serialization slot until it returns; its obsolete result
cannot publish. Cancellation before the coroutine's first dispatch also releases
the slot, so rapid unbind/rebind cannot strand the replacement binding.

Content generation, source/provider epochs and explicit lifecycle reconciliation
produce work demand. Viewport/cursor frames project prepared results without
initiating detection or reopening exhausted recovery. An active analysis uses the
latest visible range to prioritize missing content, then continues history admission.
Independent detection/publication is bounded to 64 logical lines. Ordered providers
retain Stage 2's bounded context window; their persistent continuation and full replay
remain Stage 5.

Failures and provider-local cancellation leave source unprocessed. Three delayed
retries (100 ms, 500 ms, 2 s) recover without new output. Successful empty results
are retained. After exhaustion, new content, source/provider invalidation or explicit
bind/show/focus reconciliation permits another attempt. Binding cancellation is
propagated using the coroutine owner's active state, as specified by
[Kotlin's cancellation contract](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/ensure-active.html).
Operational provider failures report their exception type without terminal text.

## Publication and edited targets

Visible row changes invalidate edited actions synchronously. A visible wrapped
prefix may depend on an off-screen live tail: after content changes, bounded
absolute-range reads compare only the relevant primitive row identities/generations.
The index changes after the session lock is released. Reads intersect the returned
range because a request inside the live grid may start before its requested row.
Text extraction and detector execution remain on the worker.

After detection, bounded worker copies validate current source rows again, including
output that has not yet reached the published viewport. A final primitive metadata
check precedes publication on the EDT. Invalid lines remain unprocessed; valid
independent results can publish even when another line changed. Context-dependent
results require valid context. This also rejects replacement/reflow/reset/disposal
results and preserves complete destinations for partially retained wrapped links.

## Lifecycle routes

| Trigger | Shared Swing handling | Verification |
|---|---|---|
| Bind/rebind | Reconcile the published frame, demand and pointer | Real component with controlled worker/UI handoffs |
| Tool-window/tab content hidden in-place | `SHOWING_CHANGED`; keep discovery, clear hidden hover, reconcile on show | Container visibility regression while work is pending |
| Component removed/reinserted | `removeNotify` preserves discovery; `addNotify` reconciles | Prepared link and stationary hover return without a requested frame |
| Terminal loses/regains focus | Clear stale hover/modifiers, reconcile on focus return | Focus event round trip with no terminal output |
| Application window loses/regains focus | Ancestor window focus listener; removed on detachment/disposal | Hook implementation and ownership inspected; native OS event ordering remains manual verification |

Lifecycle return samples the current pointer through
[`Component.getMousePosition`](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/java/awt/Component.html#getMousePosition())
outside painting and ordinary pointer updates. This handles a stationary pointer
and avoids restoring hover when the pointer moved outside while inactive. Headless
execution avoids native pointer access; deterministic fixtures inject its position.

The IntelliJ pane binds the shared component during creation and disposes it when
the pane closes. Temporary visibility changes do not introduce a second discovery
owner. Synthetic Swing events establish these component contracts; they do not
claim to be a captured native IntelliJ/OS event trace. User verification should
exercise tool-window hide/show and application switching separately. Native traces,
real-provider readiness profiling and full Plugin Verifier remain Stage 7 integration
work.

## Validation

The controlled suite covers retry exhaustion, successful empty retention, failure
and provider interruption without new output, coalescing, visible-first admission,
bounded publication, cancellation before dispatch and during suspension, serialized
uncooperative providers, unpublished edits/reset, unrelated concurrent output,
off-screen wrapped-tail invalidation and unchanged-tail preservation. Existing
retention, Unicode, selection, mouse and rendering tests remain part of the full
Swing gate.

All **1,036 Swing tests** and **225 IntelliJ plugin tests** pass, with no failures
or skipped tests. Standalone compilation and the JMH harness build pass. The
plugin's coroutine ABI guard passes with explicit cancellation causes. Project
configuration verification retains its existing warning about the explicit
coroutine dependency; dependency packaging is unchanged in this stage.

Reproduce correctness and compilation with:

```text
./gradlew spotlessApply :ketraterm-ui-swing:test :ketraterm-app:compileKotlin :ketraterm-benchmarks:jmhJar
./gradlew -p ketraterm-intellij-plugin test verifyPluginProjectConfiguration
```

Prepared-path allocation measurements use the Stage 2 JMH workload and limits:
80×24, 160×48 and 240×48 grids, with 1,000 and 10,000 retained rows. Discovery,
source validation, lifecycle pointer sampling and changed-result application may
allocate. They are excluded from the prepared scrolling/projection/action-lookup
claim. Active real-provider profiling remains Stage 7.

JMH 1.37 on JetBrains JDK 25.0.4.1+1-b610.67, one fork, two one-second warmups
and three one-second measurements, records **0 owner-thread allocated bytes in
every iteration across all six configurations**. No concurrent build/profiling work
ran during measurement. Raw results are in the ignored
`build/uri-stage3/prepared-thread-allocations.json`; reproduce with:

```text
java -jar ketraterm-benchmarks/build/libs/ketraterm-benchmarks-0.3.0-SNAPSHOT-jmh.jar TerminalHyperlinkProjectionBenchmark.countPreparedAllocations -f 1 -wi 2 -w 1s -i 3 -r 1s -prof gc -rf json -rff prepared-thread-allocations.json
```

Both feature maps and the Swing migration notes describe this slice. Changelogs
remain consolidated around the completed user-facing repair, rather than stages.
