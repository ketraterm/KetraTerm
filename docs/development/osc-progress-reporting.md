# OSC 9;4 progress verification

Baseline: `b342a6aec`, 2026-10-30. Existing scoped tests ran before production
changes. The new parser reproduction expected five progress callbacks and got
an empty list. The IDE reproduction also demonstrated missing progress labels.
The historical finding remains in the [canonical gap map](../terminal-feature-gap-map.md#resolved-audit-findings);
the [protocol contract](../reference/protocol.md#progress-reporting) separates wire
semantics, compatibility conventions and the user-approved five-minute expiry.

| Suite | Baseline cases | Verified cases | Failures | Skipped/aborted |
| --- | ---: | ---: | ---: | ---: |
| Protocol | 22 | 23 | 0 | 0 |
| Parser | 814 | 820 | 0 | 0 |
| Host | 594 | 600 | 0 | 13 |
| Session, including default dispatcher | 509 | 520 | 0 | 0 |
| Workspace, including default dispatcher | 130 | 131 | 0 | 6 |
| Standalone | 235 | 241 | 0 | 0 |
| Shell integration | - | 73 | 0 | 0 |
| PTY | - | 95 | 0 | 14 |
| Swing UI | 1,729 | 1,729 | 4 | 0 |
| IntelliJ plugin | 267 | 269 | 0 | 0 |
| Retained compiled clients | - | 46 | 0 | 0 |

The 13 inherited host aborts are `HostGraphemeTest` R06 cases: their existing
assertions execute before the known-failure guard. They were unchanged. Workspace
skips are four unavailable fish/zsh cases and two opt-in PTY cases; PTY's 14 native
host cases retain their opt-in guard. No tests were disabled or assertions weakened.

The complete Swing suite was also run in an isolated archive of the untouched
baseline with the same JDK and test JVM settings. Both runs contain the same four
failures, with identical test identities and assertion messages:

| Test identity | Remaining assertion |
| --- | --- |
| `TerminalShapedGlyphVectorCacheTest.clipped glyph batches preserve full vector pixels across marks ligatures and batch boundaries` | `rtl=false, cellWidth=4, scale=1.0, clip=0..4`: expected `-1`, actual `7252`. |
| `TerminalTextPainterTest.ComplexTextRendering.bounded shaping retains the same Arabic context as a short run`, `beh` | Logical cell 2, array index 121: expected `0`, actual `-1`. |
| Same method, `lam-alef` | Logical cell 3, array index 205: expected `-1`, actual `0`. |
| Same method, `marked-beh` | Logical cell 2, array index 53: expected `-1`, actual `0`. |

Focused coverage:

- [Parser](../../ketraterm-parser/src/test/kotlin/io/github/ketraterm/parser/OscProgressTest.kt) and [host](../../ketraterm-host/src/test/kotlin/io/github/ketraterm/host/HostProgressTest.kt): states/defaults, zero/100, invalid numbers, overflow, exact payload limits, chunking, cancellation/recovery, legacy sinks, permissions and reset ordering.
- [Session](../../ketraterm-session/src/test/kotlin/io/github/ketraterm/session/TerminalProgressTest.kt): retained reads, expiry boundaries, identical refreshes, late workers, nanosecond wrap, subscriber reentry, owner cancellation, revocation and closure.
- [Workspace](../../ketraterm-workspace/src/test/kotlin/io/github/ketraterm/workspace/TerminalWorkspaceProgressTest.kt): first output after publication, conflation, originating tab and observer cleanup.
- [Standalone](../../ketraterm-app/src/test/kotlin/io/github/ketraterm/app/ui/TabManagerProgressTest.kt) and [IDE](../../ketraterm-intellij-plugin/src/test/kotlin/io/github/ketraterm/intellij/services/KetraTermProjectTerminalPersistenceTest.kt): real byte-to-product routing, state labels, selection/split focus, unchanged raw titles/persistence, coalescing, queued revocation/close and disposal.

All 33 added regression cases passed. Formatting and the four changed library
ABI checks passed. Existing constructor/copy shapes remain intact; progress is
an optional sink capability and an optional host-observer method. Published
source consumers passed all four combinations of Kotlin 2.4.0/2.4.20 and
Gradle/POM-only metadata, plus all 46 retained-binary upgrade cases. Publications
were staged locally; no remote publication or baseline replacement was performed.

Validation used full JetBrains JDK 25, serial Gradle workers and task-local JVM
limits. Initial attempts hit native compiler-memory exhaustion, including an
uncapped source-consumer smoke JVM; bounded reruns passed unchanged assertions.
New test fixture construction and source encoding were corrected. The standalone
progress-label test initially expected `Working\u2026` and received `Working\u0085`;
the IDE's existing `testSearchTextResolvesStatusAndCountMessages` initially received
misdecoded UTF-8 instead of `Searching\u2026`. Baseline Unicode was restored and
the original assertions retained.

Progress processing allocates receipt/snapshot objects on reports; formatting
runs on presentation changes and painting reads cached labels. No new per-frame
allocation or latency claim is made. Native taskbar appearance remains platform
owned; deterministic product tests inject its publication boundary. No installed
product or Plugin Verifier pass is claimed.

`graphify update .` completed with sequential extraction: 19,184 nodes,
47,214 edges and 733 communities. Its Kotlin extractor reported ten partially
parsed files; compiler/test results remain authoritative. No LLM extraction or
labeling was run. All 184 local Markdown link targets were checked.

Across these suites, 4,510 cases passed, four inherited rendering cases failed,
and 33 cases retained their existing skip/abort conditions.

## Rendering runtime investigation

Follow-up on 2026-10-31 isolated the four local failures to the selected test
runtime. On this same Windows host, unchanged code/fonts and default resource
settings produced:

| Test runtime | Cases | Failures |
| --- | ---: | ---: |
| Temurin 25.0.3+9, affected classes | 135 | 0 |
| Temurin 25.0.3+9, complete Swing suite | 1,729 | 0 |
| JBR 25.0.4.1+1-b610.67, affected classes | 135 | 4 |
| IDE-bundled JBR 25.0.4+1-b508.27, affected classes | 135 | 4 |

The controlled vendor comparison kept the Gradle JVM on Temurin and changed only
the test launcher. Both launches used the default 512 MiB test heap, without
custom CPU/code-cache/JIT limits. The Windows CI logs for baseline commit
`b342a6aec` confirm Temurin 25.0.4+101.0, actual execution of the Swing test task,
and a successful build (run 41056463129, job 124065117653).

Thus the earlier four failures are runtime-specific local observations, not
unconditional repository failures or evidence of a green-CI discrepancy. The
precise rendering/font-engine difference inside these JDK builds has not been
isolated. Prior local test runtime history is not established. No IDE JVM,
repository toolchain settings, fonts, production code or assertions were changed
by this investigation; temporary launcher scripts and logs remain under build/.

The IDE project SDK named `25` resolves to local Temurin 25.0.3. Its Gradle JVM
entry `jbr-25` instead resolves to the IDE bundle. SDK registration labels still
say JBR 25.0.3, but the installed bundle's release file reports 25.0.4; that bundle
also reproduces all four failures. Therefore this is not confined to the newly
selected downloaded JBR build. Direct IDE tests using project SDK `25` provide a
plausible explanation for earlier local success, but past launcher/cache history
was not available to establish that retrospectively.

## Circular presentation and product preferences

Follow-up on 2026-10-31 replaces active shell icons with thin progress rings in
both products. Determinate states use the reported percentage; indeterminate
work rotates. Tab names stay unchanged. Cached tooltip/accessibility descriptions
and selected-window status retain state and percentage. Hidden progress restores
the profile icon. The shared Swing helpers expose size, colors, phase and explicit
animation lifecycle to library hosts. One visibility-bound clock per presentation
owner stops when hidden, inactive or disposed.

The original title preference and persistence remain unchanged. Progress is always
enabled in both products, independently of title permission. Library hosts can
deny progress separately through `HostPolicy.progressPolicy`; session revocation
clears retained progress. Regressions check both directions of permission
independence and title-policy replacement during active progress. Existing startup
permission-revocation tests remain unchanged.

`HostPolicy` adds the progress field at the end of its constructor. Internal
Kotlin declarations preserve the original JVM constructor/default-constructor and
copy/default-copy signatures, including calls from existing Java source.

Deterministic ring tests cover size/phase bounds, colors, percentage extent,
elapsed-time rotation, graphics-state preservation, EDT ownership, visibility,
completion and disposal. Product tests cover raw tab names, icons, selection,
accessibility, retained updates, reset and closure.

A warmed Windows Temurin 25.0.3 allocation probe measured zero bytes in each of
two one-million-iteration phase-update loops. Fifty thousand indeterminate
Java2D paints to a 16-pixel ARGB image with antialiasing enabled allocated
4,400,000 bytes (88 bytes/paint). JFR paint-stack allocation samples identified
JDK `ArcIterator` instances. Shapes, strokes and colors are retained by the icon;
this measurement does not claim zero Java2D/Swing repaint or timer allocation.
The probe and JFR capture remain under ignored build/ output.

Follow-up validation on Windows/Temurin 25.0.3 passed the affected root suites:
601 host, 520 session (including default-dispatcher coverage), 131 workspace,
84 Swing-host and 242 standalone cases: 1,578 total, no failures, 19 existing skips.
The original Java constructor tests compile, and the tracked host client runs
unchanged against the new host JAR with Kotlin 2.4.0 and 2.4.20. Formatting and
shared Swing-host ABI checks passed. Retained JVM entry points are explicitly
marked published ABI so subsequent snapshots continue tracking them.

The final IntelliJ fixture suite passed all 269 cases on JBR 25.0.4.1, including
OSC progress presentation with application-title permission explicitly denied.

Final reruns also passed all five changed-module ABI checks and the complete
published-consumer matrix: Kotlin 2.4.0/2.4.20, Gradle/POM metadata, Java/Kotlin
source smokes and all 46 retained-binary upgrade cases. Standalone progress
fixtures explicitly deny title permission too; all 242 application cases passed.
No tests were disabled or assertions weakened.

Graphify refreshed 19,216 nodes and 47,261 edges without LLM extraction. Its
extractor reported 11 partially parsed files; Kotlin compilation and the relevant
tests passed. Changed Markdown relative links and Git whitespace checks passed.
