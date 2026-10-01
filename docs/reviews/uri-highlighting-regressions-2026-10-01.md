# URI highlighting regression investigation — 2026-10-01

Desktop review after Stages 5–6 contradicted the claimed readiness. The earlier
tests established several internal contracts, but some encoded the wrong user
behavior and most scheduler tests bypassed the real source-validation path.
Completion gates are reopened in the canonical feature/gap maps.

## Confirmed defects and corrections

- **Separate agy links hover together.** The application emits five raw-URL
  fragments and a separate authentication caption using one explicit OSC 8 ID and
  destination. The controller and painter equated protocol identity with the
  entire visual hover region. The revised projection separates disconnected
  visible occurrences while joining overlapping adjacent rows and actual soft
  wraps. Detected links retain their detector occurrence identity. Destination
  resolution remains unchanged.
- **The original grouping evidence was unsound.** Its sanitizer replaced every
  OSC 8 ID with one literal without checking equality. The original fixture was
  not sufficient evidence of original ID grouping. A fresh isolated 176×32
  ConPTY capture confirms six openings, one ID, one 704-character target, and
  fragment lengths 174/174/174/174/8 plus the caption. No credentials or
  authorization code were submitted. This confirms the current emission, not the
  undocumented equality check in the original sanitizer.
- **IDE file events blank prepared links.** Every application VFS batch used to
  invalidate both indexes and cancel both discovery lanes. Content-only and empty
  batches are now ignored. Same-detector configuration refresh keeps retained
  source and prepared links until replacement batches arrive. Replacement actions
  receive fresh identities even when their ranges/styles match, and successful
  empty results remove old links. Replacing the detector or binding still retires
  old results immediately. The configuration subscription stays alive through
  ordinary refreshes.
- **Unrelated output suppresses ordered results.** A valid historical batch was
  rejected unless the entire index had already analyzed the newest global content
  generation. A deterministic real-source test reproduced this before correction.
  Publication now validates the requested rows and all preceding rows that were
  mutable when the request began. Earlier edits, including edits subsequently
  admitted to history, still reject dependent results. Validation is bounded by
  the live screen plus the current batch rather than the retained history.
- **Generic paths use the wrong native policy.** Bare paths were constructed as
  visible links with direct activation. IntelliJ's terminal marks them implicit:
  subtle ordinary hover, Ctrl/Cmd activation and hyperlink styling while activated.
  URLs and file URIs remain visible. The adapter now preserves native custom hover
  attributes and normal/followed fallback rather than replacing missing hover
  attributes with the normal style.
- **Valid providers can be rejected.** The fresh-instance identity guard imposed
  a contract that ConsoleFilterProvider does not offer. It could disable an entire
  ordered chain when a provider returned a reusable stateless filter. The guard
  and weak-reference retirement bookkeeping are removed. Factories are requested
  again on replay; factories own the lifetime semantics of returned filters.
- **Cursor ownership hides native hover feedback.** Every ordinary mouse move
  first cleared prompt-gutter hover by writing the default cursor. The hyperlink
  controller's unchanged-occurrence fast path then returned without restoring its
  hand cursor. This affected both OSC 8 and detected visible links, whose native
  hover can preserve their existing style. `SwingTerminal` now owns the actual
  cursor and combines gutter state with hyperlink cursor intent. Tests dispatch
  consecutive mouse events through the component rather than only testing the
  hyperlink controller in isolation. Stationary frame/focus reconciliation shares
  the mouse router's reporting/Shift policy; suppressed hover keeps pointer
  coordinates so disabling reporting restores the current target. Component
  raster tests verify ordinary implicit hover, Ctrl/Cmd activation and modifier
  release across the entire displayed occurrence.
- **Plain directories are excluded.** Path discovery explicitly rejected
  `VirtualFile.isDirectory` and required a separator or dot even for existing
  children. The native `OpenFileHyperlinkInfo` action already handles directories.
  Discovery now accepts existing directory paths and single-component children,
  retaining implicit-link policy and excluding navigation noise such as `.` and
  `..`. File URI directories use the existing native URL filter. Native SDK tests
  exercise exact IDE navigation targets for project content and exact filesystem
  targets otherwise, intercepting the service to avoid opening an OS window.

## Storage and allocation correction

The user clarified that only recurring terminal frame/paint work needs a strict
allocation constraint. Link discovery and interaction may allocate ordinary
Kotlin objects. The custom open-addressed action table and its packed four-state
style copies made this design harder to maintain without establishing a memory
benefit: the original immutable styles were retained too.

Stable occurrence ownership now uses an ordinary map and reference counts.
Viewport projection retains references to existing immutable presentations beside
its reusable IDs. Painting selects from these references without map lookup,
boxing IDs or constructing per-cell objects. One style-selection function owns
normal/hovered/active/followed fallback. There is no new discovery framework or
pooling layer. Memory savings are not claimed without measurement.

The native comparison follows the actual reworked terminal's
[generic file filter](https://github.com/JetBrains/intellij-community/blob/master/plugins/terminal/src/org/jetbrains/plugins/terminal/hyperlinks/filter/TerminalGenericFileFilter.kt),
[provider wrapper](https://github.com/JetBrains/intellij-community/blob/master/plugins/terminal/src/org/jetbrains/plugins/terminal/hyperlinks/filter/CompositeFilterWrapper.kt),
and [frontend metadata application](https://github.com/JetBrains/intellij-community/blob/master/plugins/terminal/frontend/src/com/intellij/terminal/frontend/view/hyperlinks/FrontendTerminalHyperlinksProcessing.kt).
Installed IntelliJ 2026.2 bytecode was checked for the corresponding hover and
activation behavior; this is not an assumption that every editor behavior is a
terminal behavior.

## Discovery simplification — 2026-10-02

The maintainability review identified four removable sources of complexity:

- Production, tests and discovery benchmarks now use the same bound-source
  copying and validation path. An absent source means unbound; it no longer selects
  a second viewport-only detector route. Index tests explicitly ingest complete
  logical snapshots without adding a production mode for their convenience.
- Independent readiness and ordered progress are distinct. Removing an ordered
  result after its earlier dependency changes no longer queues discovery for an
  unchanged independent URL. Reconciliation checks only configured contexts.
- The retained index owns result validation and splitting. The intermediate
  accumulator and exposed split-result type are removed. Independent results must
  remain within their request; ordered results may reference earlier retained
  source, but incomplete ranges cannot publish partial links.
- The suspending public detector returns a completed `List<SwingHyperlink>`.
  The sink interface and unused request cumulative-offset arrays/accessors are
  removed together with their callers. Console cumulative offsets remain inside
  the IntelliJ adapter, where they describe the ordered provider chain.

The independent and ordered workers remain separate so slow console providers
cannot block ordinary URL discovery. Cancellation, bounded source copying,
generation validation, stable action ownership and reusable frame projection
remain necessary contracts. Discovery and result construction use normal Kotlin
collections; this cleanup introduces no pool, framework or replacement pipeline.
Public migration notes and the scoped ABI baseline describe the intentional API
change. No performance improvement is inferred from removing code.

Moving the scheduler fixtures onto the actual source path exposed another
validation defect: a result with an unchanged URL dependency was rejected after
neighbouring progress text changed. Independent publication now validates the
latest observed snapshot against the actual source, then compares the original
result's dependency against that validated text. Only snapshots with the same
logical anchor and line identity are eligible. Ordered validation still uses its
captured dependencies. Regressions cover changed targets both before and after
viewport publication; an unseen source edit cannot publish an obsolete action.

Verification of this simplification:

- Full Swing suite: 1,078 tests; full IntelliJ suite: 237 tests. Both report zero
  failures, errors or skips. Swing was rerun after the final unbound-source guard
  correction, including cancellation when the host also clears its cached frame.
- Nine library export checks and both isolated consumer modes pass. The Swing
  consumer compiles and runs the new return-list API, pointer activation,
  configuration refresh and unbinding through public entry points.
- The intentional ABI baseline update passes its check. Root/plugin formatting,
  standalone compilation, JMH caller compilation and packaging pass. JMH was not
  run for this cleanup, so it makes no new allocation or latency claim.
- Plugin configuration verification completes with the existing explicit
  coroutines dependency warning; this is not a full Plugin Verifier run.
- Graphify updated to 15,010 nodes and 35,605 edges. Its four existing partial
  Kotlin parser warnings remain; source and compiler results take precedence.

```text
.\gradlew.bat spotlessApply :ketraterm-ui-swing:test :ketraterm-app:compileKotlin :ketraterm-benchmarks:jmhJar
.\gradlew.bat :ketraterm-ui-swing:updateKotlinAbi :ketraterm-ui-swing:checkKotlinAbi
.\gradlew.bat :ketraterm-testkit:test --tests '*TerminalLibraryConsumerCompilationTest' :ketraterm-testkit:publishedConsumerTest
.\gradlew.bat -p ketraterm-intellij-plugin spotlessApply test verifyPluginProjectConfiguration
graphify update .
```

Both canonical maps and public migration notes are updated. The existing staged
agy fixture is preserved. Changes remain uncommitted; no changelog entry is added
for this internal cleanup.

## Remaining architectural limits

The ordered lane currently analyzes trailing unused screen rows. Later output
into one of those rows looks like an earlier edit and can replay all retained
history. Neither the render contract nor core line storage records a consumed
hard-break/output frontier. An explicitly emitted empty line can have the same
cells and stamps as an unused row; trimming blank text would discard meaningful
filter input, and cursor-based guessing would create incorrect invalidations.
This needs a deliberate source contract before claiming work proportional to
changed content. The retained index and independent URL path do not require
replacement to address it.

Explicit OSC 8 links still preserve application-authored underline styles.
When an application already underlines a link, unmodified hover may only change
the pointer; modifier hover provides foreground emphasis. Current JetBrains
[OSC 8 frontend source](https://github.com/JetBrains/intellij-community/blob/master/plugins/terminal/frontend/src/com/intellij/terminal/frontend/view/hyperlinks/FrontendOsc8HyperlinksProcessing.kt)
sets these decorations to implicit links. KetraTerm's permanent dotted OSC 8
underline therefore still differs from that implementation. This source path is
newer than the installed 262.8665.258 SDK and was not verified in that runtime.
The remaining correction belongs in host-resolved OSC 8 presentation through
the existing settings/style boundary, not URI parsing or another discovery path.

Structural filesystem changes remain a conservative provider refresh. Targeted
file dependencies and real provider latency require integration measurement.
These corrections do not establish complete IDE visual parity or whole-frame
allocation guarantees.

## Public API and consumer follow-up

The allocation correction keeps immutable Kotlin objects as the authoritative
link metadata. Style selection now belongs to an internal renderer extension,
not a JVM-public member on `SwingHyperlinkPresentation`. `javap` confirms the
presentation class contains only its public data contract and generated members.
Action KDoc describes retained occurrence lifetime and EDT callbacks. Detector
KDoc specifies that changed configuration and its generation must be published
before a flow notification; an unchanged-generation signal preserves prepared
results without rediscovery.

The existing published-consumer build now includes an isolated Swing host with
one direct library dependency. It compiles Kotlin suspension/Flow usage and Java
functional actions against staged Maven artifacts, without project substitution,
internal access or testkit dependencies. Actual hover/activation callbacks
acknowledge installed results and configuration refresh without output. Explicit
subscription cancellation acknowledges unbinding, and the host retains ownership
of its session and connector. Callback handshakes avoid sleep-based assertions.

Kotlin 2.4.20's built-in ABI validator supplies a scoped, reviewable signature
snapshot for hyperlink contracts and the embedding entry points. A deliberate
getter-name mismatch was rejected by `checkKotlinAbi`; the exact original baseline
was restored afterward. Internal implementation declarations are excluded by
Kotlin visibility metadata, while generated public default-argument/data-class
members remain under tool control. The validator omits the public Java no-argument
`SwingTerminal` overload because its primary constructor is internal; the Java
consumer explicitly constructs and disposes that overload. This baseline and
current consumers do not establish compiled-client compatibility across releases.

Verification for this follow-up:

- 149 focused Swing contract, scheduling and painting tests pass, with no failures,
  errors or skips; 9 isolated library-export compilation tests also pass.
- Kotlin and Java consumers compile and execute in both Gradle-metadata and
  POM-only modes. The Swing smoke awaits the first published frame before pointer
  input, then awaits installed-result callbacks; it does not assume worker speed.
- The ABI check passes against the restored baseline. CI invokes it explicitly.
  Bytecode inspection confirms the presentation helper's removal.
- Formatting and JMH caller compilation pass. No benchmarks or additional plugin
  tests were run for this API/test-only follow-up. Graphify was refreshed with the
  same four partial-parser warnings documented below.

```text
.\gradlew.bat spotlessApply
.\gradlew.bat :ketraterm-ui-swing:checkKotlinAbi :ketraterm-ui-swing:test --tests '*SwingHyperlinkContractTest' --tests '*TerminalHyperlinkDiscoverySchedulingTest' --tests '*TerminalTextRunStyleTest' --tests '*GridPainterTest' --tests '*TerminalTextPainterTest'
.\gradlew.bat :ketraterm-testkit:test --tests '*TerminalLibraryConsumerCompilationTest' :ketraterm-testkit:publishedConsumerTest :ketraterm-benchmarks:jmhJar
graphify update .
```

## Verification of the cursor, directory and storage follow-up

- Full Swing suite: 1,072 tests, zero failures/errors/skips. Coverage includes
  actual component mouse dispatch, focus/gutter transitions, mouse-reporting and
  Shift transitions, ordinary/active hover pixels, projection replacement/reset,
  and captured-action lifetime.
- Full IntelliJ suite: 237 tests, zero failures/errors/skips against the installed
  2026.2 SDK, including native file/directory navigation and file line/column targets.
- Root/plugin formatting, standalone compilation and existing JMH caller
  compilation pass. No benchmarks were run for this follow-up; older allocation
  measurements below do not validate the replacement storage implementation.
- Graphify refreshed. Its parser reports partial extraction for four files,
  including `SwingTerminal`; Kotlin compilation passes. Source remains the
  authority where graph extraction is incomplete.

The first projection test incorrectly assumed height reduction would leave its
link on screen. It now shrinks columns while keeping that occurrence visible and
still checks discarded projection references. The directory test initially treated
a physical temporary directory as module content. It now verifies project
membership explicitly and exercises both native navigation routes.

```text
.\gradlew.bat spotlessApply
.\gradlew.bat :ketraterm-ui-swing:test :ketraterm-app:compileKotlin :ketraterm-benchmarks:jmhJar
.\gradlew.bat -p ketraterm-intellij-plugin spotlessApply
.\gradlew.bat -p ketraterm-intellij-plugin test
graphify update .
```

Desktop visual verification and the remaining architectural work stay open in the
canonical repair map. This follow-up does not claim a complete native-parity or
performance gate. All changes remain uncommitted.

## Verification before the cursor, directory and storage follow-up

These results describe the earlier corrective diff, not the follow-up above:

- Swing: 1,063 tests, zero failures/skips. The subsequently added same-ID
  press/release assertion passed in the focused 31-test controller suite.
- IntelliJ: 234 tests, zero failures/skips against the installed 2026.2 SDK.
  Plugin configuration verification succeeds with the existing explicit-coroutine
  dependency warning; this is not a full Plugin Verifier run.
- Root/plugin formatting, standalone Kotlin compilation and JMH compilation pass.
- Graphify updated to 15,024 nodes/35,679 edges. Its existing three partial-parser
  warnings remain; Kotlin compilation succeeds.
- JMH prepared hover and style workloads: one fork, two one-second warmups and
  three one-second measurements. All three measurements of all nine configurations
  reported exactly zero owner-thread allocated bytes via ThreadMXBean. Grids are
  80×24, 160×48 and 240×48; hover includes both OSC 8 connected occurrences and
  detected semantic occurrences. Discovery, storage growth, AWT repaint scheduling,
  native callbacks and full raster painting are outside this measurement. This
  does not establish discovery latency or a comparative speedup.

The pre-fix real-source append regression failed; it passes with the corrected
validation. Tests also preserve rejection after an earlier unpublished edit,
including an edit scrolled into history before publication. Native VFS tests use
supported event constructors and the platform write-action context.

Reproduction commands:

```text
.\gradlew.bat spotlessApply :ketraterm-ui-swing:test :ketraterm-app:compileKotlin :ketraterm-benchmarks:jmhJar
.\gradlew.bat -p ketraterm-intellij-plugin spotlessApply test verifyPluginProjectConfiguration
.\gradlew.bat :ketraterm-ui-swing:spotlessCheck :ketraterm-ui-swing:test --tests '*TerminalHyperlinkControllerTest*'
java -jar ketraterm-benchmarks/build/libs/ketraterm-benchmarks-0.3.0-SNAPSHOT-jmh.jar "TerminalHyperlink(Hover|Style)Benchmark.countPreparedAllocations" -f 1 -wi 2 -w 1s -i 3 -r 1s -prof gc -rf json -rff build/uri-regression/prepared-allocations.json
graphify update .
```

Raw allocation JSON/log are under ignored `build/uri-regression`. The staged ANSI
fixture is preserved unchanged. Changes remain uncommitted for user verification;
changelogs will describe the completed user-visible fix rather than this
investigation's individual patches.
