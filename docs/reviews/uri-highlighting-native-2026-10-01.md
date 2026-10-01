# URI highlighting: IntelliJ discovery and native interaction

Stages 5 and 6 are implemented together at the user's request on
**fix/uri-highlighting**. Changes remain uncommitted for verification.

## Discovery and ownership

One retained Swing index now accepts independently scheduled text-derived and
ordered results. Each lane serializes its own invocations. URL and local-path
discovery can publish while a contextual console filter is suspended.
Independent results retain token dependencies; ordered results identify the
source, consumed context and producer position separately.

Ordered providers continue across bounded 64-line requests with cumulative
offsets independent of the viewport. An earlier edit advances the analysis epoch
and reconstructs provider state from retained content. Unaffected occurrences keep
their identities/actions. A producer-to-source index removes earlier highlights
when that producer replays empty. Ordinary eviction prunes only retired offset
mappings and retained records; it does not restart the append-only chain.

All applicable console providers run on mixed-content lines. URL detection has a
cached native UrlFilter, and local paths use LocalFileSystem/OpenFileHyperlinkInfo.
Relative paths use the command directory associated with the output's stable line
identity, falling back to the launch directory when command metadata is absent.
The fallback matches workspace launch behavior. Invalid line/column numbers do
not silently navigate to line one.

Provider configuration, indexing, roots, VFS and theme notifications invalidate
discovery through a binding-owned flow, including when no output arrives.
Painting never queries the IDE, VFS, provider actions or theme services.

A cancellable coroutine read action covers one line/provider invocation. A retry
after provider invocation never calls that mutated instance again; cancellation
discards the chain, and bounded owner recovery replays with fresh filter instances.
See the [official coroutine read-action contract](https://plugins.jetbrains.com/docs/intellij/coroutine-read-actions.html).
The implementation and metadata adapter were checked against the installed
IntelliJ 2026.2 SDK, including its supported listener/navigation APIs.

ConsoleFilterProvider has no general reset operation. A provider returning the
same filter instance on reconstruction is rejected rather than assumed resettable.
This failure uses bounded ordered-lane recovery; independent links remain available.
Weak identity guards do not keep retired providers alive. Binding/provider teardown
releases ordered state only after its invocation exits, including cancellation
before worker dispatch.

## Presentation and interaction

The plugin converts native normal, hover, followed and visibility metadata into
neutral immutable styles. The retained action table prepares primitive colors,
underline styles and thickness for painting. Hover/active/followed state selects
those records without allocating. Terminal-authored underlines and concealment
retain precedence; selection/search painting remains above link backgrounds.

Visible native links activate directly; implicit links require Ctrl, or Cmd on
macOS. Navigation occurs on release only when the pressed occurrence remains the
target and no drag happened. Modifier clicks preserve existing selection, while
direct links permit selection dragging. Application mouse reporting keeps priority
and the existing Shift override. Hidden cells cannot expose a hit or menu target.

Provider hover callbacks, popup actions and navigation popup anchors stay attached
to stable actions. Hover bounds reuse discovery-owned storage. Menu actions retain
their original destination even after output changes or rebinding.
Overlaps prefer OSC 8, then visible links, narrower ranges and stable provider order.

## Verification

Focused tests cover independent publication during slow ordered work, configuration
recovery without output, bounded replay, backward ranges across batches, removal
on empty replay, preserved occurrences, overlap precedence, release/drag/target
changes, implicit modifiers, concealment, lifecycle, native paint records and
selection/underline precedence. Real SDK tests exercise mixed links, all providers,
append/eviction/replay, cancellation recovery, historical directories, path bounds,
styles, hover callbacks, popup actions and navigation anchors.

Validation commands:

    .\gradlew.bat spotlessApply :ketraterm-ui-swing:test :ketraterm-app:compileKotlin :ketraterm-benchmarks:jmhJar
    .\gradlew.bat -p ketraterm-intellij-plugin spotlessApply test verifyPluginProjectConfiguration
    graphify update .

Swing: 1,053 tests; IntelliJ: 232 tests. Both suites have zero failures/skips.
Standalone compilation and JMH harness generation pass. Graphify updates successfully,
with three known partial Kotlin extraction warnings; Kotlin compilation succeeds.
Plugin configuration verification retains the existing explicit-coroutine-library
warning. Full Plugin Verifier and native desktop profiling remain in Stage 7.

## Prepared-path allocation measurements

JMH 1.37, JBR 25.0.4.1+1-b610.67: one fork, two one-second warmups and three
one-second measurements. No builds or Graphify runs were concurrent.
Every measured iteration in all 12 configurations recorded **zero owner-thread
bytes**, measured with ThreadMXBean separately from background GC-profiler activity.

| Workload | Grid | Retained rows | Mean µs/op |
|---|---|---:|---:|
| Group hover/modifier/projection | 80×24 | — | 6.43 |
| Group hover/modifier/projection | 160×48 | — | 37.60 |
| Group hover/modifier/projection | 240×48 | — | 36.34 |
| Prepared history projection | 80×24 | 1,000 | 7.00 |
| Prepared history projection | 80×24 | 10,000 | 14.60 |
| Prepared history projection | 160×48 | 1,000 | 17.51 |
| Prepared history projection | 160×48 | 10,000 | 23.98 |
| Prepared history projection | 240×48 | 1,000 | 22.76 |
| Prepared history projection | 240×48 | 10,000 | 30.25 |
| Native style/run preparation | 80×24 | — | 9.27 |
| Native style/run preparation | 160×48 | — | 35.07 |
| Native style/run preparation | 240×48 | — | 103.63 |

These short measurements are not latency thresholds or comparative speedup claims.
The style workload alternates prepared hover/active/followed states across dense
five-cell runs and reads primitive foreground/background/underline records.
They exclude discovery, storage growth, AWT repaint scheduling/rasterization and
provider callback internals. Active-discovery latency and retained-heap profiling
remain Stage 7 work.

    java -jar ketraterm-benchmarks/build/libs/ketraterm-benchmarks-0.3.0-SNAPSHOT-jmh.jar "TerminalHyperlink(Hover|Projection|Style)Benchmark.countPreparedAllocations" -f 1 -wi 2 -w 1s -i 3 -r 1s -prof gc -rf json -rff build/uri-stage56/prepared-thread-allocations.json

Raw output is under ignored build/uri-stage56/. JMH's Unsafe warning comes from
the existing harness. Native agy/IDE visual verification remains the user's review
step. Both feature maps and migration notes are updated; changelogs remain
consolidated for the completed user-visible repair rather than per-stage entries.