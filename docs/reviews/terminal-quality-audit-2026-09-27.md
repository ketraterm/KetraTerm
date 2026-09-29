# Terminal quality audit — 2026-09-27

This audit identifies reproducible security, output-loss, state-restoration,
embedding, and interactive-performance defects. The module structure provides
a sound foundation for addressing them.
Fix these within their existing owners; the evidence does not justify a broad
rewrite or another layer of factories, wrappers, or modules.

Reviewed revision: `d26fa4553962ab11cc1de34843728cf1f1e745c3` (`0.3.0-SNAPSHOT`).
This contains the audit and subsequent correct-behavior regression tests, without
production fixes. The feature/gap maps remain the
authoritative capability inventory. Findings below record evidence at this
revision; their follow-up status belongs in the [gap map](../terminal-feature-gap-map.md).

The [IntelliJ embedding audit](intellij-embedding-audit-2026-09-27.md) compares the
Ghostty integration and reviews host-owned runtime, shell and suggestion boundaries.

## Original audit evidence and limits

| Verification | Result |
|---|---|
| Root `test :ketraterm-benchmarks:jmhJar` | Passed; benchmark harness built. |
| IntelliJ `test check` | Passed; 219 tests, no skips; plugin archive and bundled-stdlib check passed. This is not Plugin Verifier. |
| PTY `test -Dterminal.pty.host=true` | Passed on Windows; 61 tests, no reported skips. This does not validate Unix-only branches. |
| Differential, resize/reflow, independent grid-model smoke campaigns | All passed, 100 cases each; xterm.js oracle 6.0.0. Four oracle self-tests also passed. |
| Three isolated Gradle consumers | All failed with missing public dependencies (R08); the same sources compiled when those dependencies were explicitly supplied. |
| Focused protocol, lifecycle, render, and shutdown probes | Reproduced the defects described below without changing production code. |
| Whole-component Swing paint smoke | 5,591.795 B/paint for unchanged 80×24 ASCII on this Windows/JDK 25.0.3 run. |
| `spotlessApply` | Ran successfully; its unrelated existing line-wrap change was reverted to keep this audit free of production edits. |

The root run includes cached compilation; subsequent regression counts below use
the root modules' JUnit XML, excluding the nested plugin build. Local
campaign manifests report `commitSha: unknown`; the revision above records the
checkout actually reviewed. Local logs are under `build/release-audit`.

The paint smoke used one fork, one one-second warmup and two one-second
measurements with JMH GC profiling. The benchmark normalizes its 64-paint batch
with `@OperationsPerInvocation`. It includes Java2D and amortized EDT dispatch,
excludes flowing output/selection/search, and is **not** a latency baseline or
an attribution of all bytes to KetraTerm. Deterministic probes prove array/object
replacement separately; source-level construction does not prove a particular
post-JIT allocation rate.

Read the root/module guides, Gradle dependency declarations, public contracts,
canonical maps, CI workflows, and targeted implementations/tests. Graphify was
used for navigation, with important relationships verified in source; no graph
rebuild was needed. Protocol and library recommendations were checked against
primary sources linked beside the relevant findings.

This is not exhaustive verification of every protocol combination, native font
backend, OS, IDE version, or hostile-input sequence. macOS/Linux native runs,
long campaigns, Plugin Verifier, installed-package smoke tests, and sustained
JBR/JFR performance measurements were not performed here. Passing existing
tests does not discharge the new regression cases below.

## Confirmed findings

P1 means fix before stable; P2 means a correctness or maintainability item that
needs correction or an explicit release decision; P3 is lower-impact API
robustness. Ordering reflects impact, not implementation effort.

### R01 — P1: unsupported DECRQSS reflects control bytes into application input

[BufferResponseChannel.kt:475](../../ketraterm-core/src/main/kotlin/io/github/ketraterm/core/buffer/impl/BufferResponseChannel.kt#L475)
echoes the unsupported query, and its `enqueueAsciiString` writes each UTF-16
value as a byte. DCS bodies reach it through UTF-8 decoding in
[DcsDispatcher.kt:59](../../ketraterm-parser/src/main/kotlin/io/github/ketraterm/parser/ansi/dcs/DcsDispatcher.kt#L59).

The inert request `DCS $q LF AUDIT_MARKER LF ST` produces
`1b503024720a41554449545f4d41524b45520a1b5c`: literal newlines and supplied text
in a reply forwarded to process stdin. U+010A also becomes LF through truncation.
This establishes control-byte injection; shell command execution was not tested.
Response-family denial correctly suppresses the reply.

Emit the fixed empty failure `DCS 0 $r ST` required by the
[xterm specification](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html#h2-Device-Control-functions).
Keep exact selector admission and add byte-stream tests for control bytes,
Unicode low-byte truncation, malformed UTF-8, splits, and denial. Do not attempt
to sanitize and reflect arbitrary unsupported text.

### R02 — P1: large movement counts corrupt state or consume unbounded CPU

[CursorEngine.kt:153](../../ketraterm-core/src/main/kotlin/io/github/ketraterm/core/engine/CursorEngine.kt#L153)
and `cursorRight` add counts before clamping. From `(1,1)`,
`CSI 2147483647 B` produces row `-2147483648`; the equivalent `C` produces that
column. A following `X` disappears. Parser numeric saturation makes these
reachable from ordinary terminal output.

The same file's `cursorForwardTab` and `cursorBackwardTab` (`:198`, `:214`)
repeat the entire count after the cursor has reached its boundary.
`CSI 2147483647 I/Z` therefore requests billions of iterations under session
mutation serialization. This CPU amplification was source-confirmed in the initial
audit. Subsequent regressions run the huge tab count in an isolated child JVM
with forced cleanup and a hang guard, so it cannot strand the test worker.

[HostCommandAdapter.kt:473](../../ketraterm-host/src/main/kotlin/io/github/ketraterm/host/HostCommandAdapter.kt#L473)
also implements `CSI S/T` by repeating the unbounded supplied count of core
scroll operations. Bound that work while preserving region and history
accounting; stopping redundant tab traversal alone does not close this finding.

Clamp against remaining distance before addition; audit origin-relative math
and bound tab traversal by reachable stops/columns. Acceptance must cover
maximum/saturated counts, both directions, margins, chunking, recovery and
nonnegative in-bounds cursor coordinates. Bounded payload size alone is not a
bound on processing work.

### R03 — P1: process exit can discard buffered PTY output

[PtyConnector.kt:200](../../ketraterm-pty/src/main/kotlin/io/github/ketraterm/pty/PtyConnector.kt#L200)
reports process exit independently of its output reader. Session closure then
rejects later bytes. A controlled fake waits until the reader enters `read`,
allows the watcher to observe exit, then releases `LAST LINE`: retained output
is empty although exit code 7 is recorded.

Coordinate process-exit observation with stream draining before publishing the
terminal closure event. Preserve explicit cancellation for local close and
read failure; do not solve this with a sleep. Cover both watcher/EOF orders,
trailing partial UTF-8, short-lived commands, blocked reads and local shutdown.

### R04 — P1: read failure leaves a child that later close cannot destroy

[TerminalSession.kt:957](../../ketraterm-session/src/main/kotlin/io/github/ketraterm/session/TerminalSession.kt#L957)
closes state on `onError` without closing the connector. The early return in
`transitionToClosed` prevents subsequent `close()` from doing so.
[PtyConnector.kt:190](../../ketraterm-pty/src/main/kotlin/io/github/ketraterm/pty/PtyConnector.kt#L190)
reports a reader exception without destroying the process.

The controlled read-failure probe yields `sessionClosed=true`,
`childAlive=true`, `childDestroyed=false` after an explicit session close.
Separate idempotent transport cleanup from first-wins lifecycle reporting, and
guarantee failure cleanup. Test read/write/start failures, duplicate events and
local close after remote failure; retain the first meaningful close result.

### R05 — P1: shutdown loses the last rendered state even after bytes were parsed

[TerminalSession.kt:1014](../../ketraterm-session/src/main/kotlin/io/github/ketraterm/session/TerminalSession.kt#L1014)
cancels publication before `parser.endOfInput`; its worker also stops once state
is closed. [SwingTerminal.kt:1052](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/api/SwingTerminal.kt#L1052)
observes only published render generations, with no final core-read fallback.

With queued dispatchers: publish a blank initial frame, accept `LAST LINE`,
close, then drain tasks. Core begins with `L`, the published frame still begins
with zero, and generation remains 1. This is distinct from R03: bytes already
reached core.

Define finalization ordering that flushes parser input and publishes the final
coherent frame before publication is stopped. Account for active reader leases
and synchronized-output mode. Verify the final frame and lifecycle event with
controlled schedulers and both early/late collectors; keep this responsibility
in session, not a second Swing read path.

### R06 — P1: chunk boundaries change emoji placement and can delete text

The previously documented late-variation-selector defect is reproduced at
[MutationEngine.kt:637](../../ketraterm-core/src/main/kotlin/io/github/ketraterm/core/engine/MutationEngine.kt#L637).
At width four, column two, `U+2764 U+FE0F X` in one chunk retains the heart and
wraps `X`. Splitting after U+2764 loses the heart and leaves `X` at row zero,
column three. Transport packet boundaries must not determine visible text.

Specify provisional placement and handle both widening VS16 and narrowing VS15,
including prior wrapping/scrolling. Compare whole versus every split for cells,
cursor, retained history and selection, with margins and both screens. This is
current correctness work, not an optional Unicode extension.

### R07 — P1: the adapter's pen mirror undoes restored core attributes

[HostCommandAdapter.kt:230](../../ketraterm-host/src/main/kotlin/io/github/ketraterm/host/HostCommandAdapter.kt#L230)
restores core's pen, but later SGR application (`:1214`) rewrites it from stale
adapter fields. `ESC[31m ESC7 ESC[34m ESC8 A ESC[1m B` prints red `A` and blue
bold `B`; both should be red. [DECSC includes SGR state](https://ghostty.org/docs/vt/esc/decsc).

This is a demonstrated ownership defect: two mutable representations of one
fact. Prefer core-owned incremental pen updates, or reconcile every restore
through an existing complete pen contract. Do not add a second saved-pen stack.
Test subsequent partial SGR after DEC/SCO and 1048/1049 restores, reset/default
slots, individual colors/styles and hyperlink state.

### R08 — P1: independently consumed public modules do not compile

Three isolated Gradle Java consumers, each depending only on one module,
failed using the current composite build's published variants:

| Artifact | Missing public type | Declaration |
|---|---|---|
| `ketraterm-host` | `TerminalCommandSink` superclass of `HostCommandAdapter` | [build.gradle.kts:26](../../ketraterm-host/build.gradle.kts#L26): core/parser/protocol are `implementation`. |
| `ketraterm-parser` | `NotificationLevel` used by `TerminalCommandSink.showNotification` | [build.gradle.kts:27](../../ketraterm-parser/build.gradle.kts#L27): protocol is `implementation`. |
| `ketraterm-completion` | `Flow` returned by `TerminalCompletionEngine` | [build.gradle.kts:28](../../ketraterm-completion/build.gradle.kts#L28): coroutines are `implementation`. |

Export dependencies appearing in public signatures using
[Gradle's API configuration](https://docs.gradle.org/current/userguide/java_library_plugin.html#sec:java_library_separation),
without indiscriminately exposing all implementation dependencies. Add Kotlin
and Java consumer compilation against temporary Maven publications, not just
project dependencies; include runtime/resource smoke tests. The monorepo's
larger classpaths currently conceal these defects.

### R09 — P1 performance: active search reallocates and scans all history on the EDT

[TerminalSearchController.kt:150](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/search/TerminalSearchController.kt#L150)
copies all retained history and searches it on each content change.
[TerminalRenderCache.kt:641](../../ketraterm-render-cache/src/main/kotlin/io/github/ketraterm/render/cache/TerminalRenderCache.kt#L641)
replaces exact-height planes as the search cache grows; `SwingTerminal` supplies
no row reserve for that cache.

At roughly 1,000 retained rows × 80 columns, ten single-row updates replaced
all planes ten times: **28,987,200 bytes** of primitive cell-plane payload,
excluding metadata/headers/clusters. At 10,000 × 80, the same sizing implies
roughly **28.8 MB per growing-history refresh**. The copy holds the session
mutation lock; the copy and subsequent full scan occupy the EDT. This violates
the no-allocation objective and performs history-sized work on the EDT even
though plain painting uses copied frames. Latency was not measured here.

First amortize retained-search storage. Then make changing-history search
bounded/cancellable with revision-safe results, avoiding full history work per
EDT frame. Verify eviction, old-row edits, wrapped matches, active result
preservation, query replacement and close. Benchmark changing 80/160-column
history; the existing unchanged six-column search benchmark does not cover it.

### R10 — P2: cursor-position replies ignore origin mode

[BufferResponseChannel.kt:378](../../ketraterm-core/src/main/kotlin/io/github/ketraterm/core/buffer/impl/BufferResponseChannel.kt#L378)
always encodes absolute coordinates. With top margin 2, left margin 3, DECOM
enabled and `CUP 1;1`, normal/private CPR return `2;3` instead of `1;1`.
[DSR coordinates are origin-relative](https://ghostty.org/docs/vt/csi/dsr).
Apply the active origin/margins before one-based encoding. Cover normal/private
forms, origin off/on, vertical-only margins, reset, chunks and denial.

### R11 — P2: the advertised DECRQSS cursor-style selector is incorrect

[BufferResponseChannel.kt:465](../../ketraterm-core/src/main/kotlin/io/github/ketraterm/core/buffer/impl/BufferResponseChannel.kt#L465)
matches bare `q`; pre-review host tests also used that invalid selector. After `CSI 5 SP q`,
the valid `DCS $q SP q ST` fails while bare `q` succeeds. Match `SP q`, as
specified by [xterm](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html#h2-Device-Control-functions),
and test literal wire bytes plus denial. R01's failure fix must cover rejected
selectors; correcting only the test expectation would preserve the defect.

### R12 — P2: charset save state crosses screen boundaries

The parser's [ParserState.kt:121](../../ketraterm-parser/src/main/kotlin/io/github/ketraterm/parser/runtime/ParserState.kt#L121)
has one saved charset slot, whereas core keeps screen-local cursor
slots. `ESC(0 ESC7 ESC[?47h ESC(B ESC7 ESC[?47l ESC8 q` prints literal `q`
instead of U+2500 from the primary screen's saved DEC charset.
See also [CommandDispatcher.kt:449](../../ketraterm-parser/src/main/kotlin/io/github/ketraterm/parser/ansi/CommandDispatcher.kt#L449).
[DECSC's saved state includes charsets](https://ghostty.org/docs/vt/esc/decsc).

Coordinate charset saves with effective screen transitions while retaining
parser ownership of charset decoding. Cover DEC/SCO, 1048, all screen-switch
forms, repeated transitions and reset/default slots. Do not introduce a second
independently guessed active-buffer state.

### R13 — P2: search and hyperlink detection treat wrap padding as real spaces

[TerminalSearchModel.kt:145](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/search/TerminalSearchModel.kt#L145)
and [TerminalHyperlinkLineSnapshot.kt:77](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/api/TerminalHyperlinkLineSnapshot.kt#L77)
skip wide trailing cells but omit the `WRAP_PADDING` distinction. In a
four-column core containing `abc界`, search finds the nonexistent `c 界` but
misses `c界`; hyperlink detection receives the artificial space too.

Omit padding while retaining real spaces, logical-to-cell offsets and cache
fingerprints. Verify wide scalars/clusters across wraps, reflow, highlights and
detector spans. Copy/export already has the correct policy; align this duplicated
text knowledge without forcing unrelated consumers into a large new abstraction.

### R14 — P2: window resize silently changes the selected text

[SwingTerminal.kt:518](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/api/SwingTerminal.kt#L518)
routes ordinary component resize to reflow at `:1918` without clearing or
remapping physical selection coordinates. Settings-driven resize does clear it.
An actual bound Swing component selected an 80-character row; resizing through
its component listeners to 40 columns left selection present but copied only
40 characters.

Use one explicit resize policy: minimally clear invalidated selection, or
deliberately preserve logical anchors. Test ordinary/font resize, narrowing,
widening, eviction, block and linear selections. Retaining stale coordinates
is not selection preservation.

### R15 — P2: one failing close prevents the rest of workspace cleanup

[TerminalWorkspace.kt:201](../../ketraterm-workspace/src/main/kotlin/io/github/ketraterm/workspace/TerminalWorkspace.kt#L201)
and `close()` at `:224` stop on a connector/listener exception. With two fake
sessions and the second connector throwing on close, one tab remains, its
session is open, its connector was never closed, and the workspace scope stays
active. The probe releases remaining resources explicitly afterward.

Attempt every owned cleanup and cancel the scope in `finally`, preserving or
aggregating failures. Product disposal should likewise release every pane.
Test throwing connectors/listeners, multiple failures and repeated shutdown.

### R16 — P2 performance: every selected paint constructs a new selection

[SwingTerminal.kt:997](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/api/SwingTerminal.kt#L997)
calls `getViewportSelection`; [TerminalSelectionController.kt:212](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/api/TerminalSelectionController.kt#L212)
constructs a new `CellSelection` even for unchanged state. The probe confirms
distinct objects on consecutive reads. Preserve public snapshot semantics while
reusing the internal projection keyed by selection/viewport state. Add a selected
whole-paint benchmark and clipping/bidi/block regressions. Measure actual
allocation after JIT optimization; do not infer byte counts from `new` alone.

### R17 — P3: parser slice validation accepts an overflowing range

[TerminalParser.kt:78](../../ketraterm-parser/src/main/kotlin/io/github/ketraterm/parser/impl/TerminalParser.kt#L78)
checks `offset + length`, which overflows. `accept(ByteArray(4), 1, Int.MAX_VALUE)`
returns without rejection or consumption. Use subtraction-based bounds checks
or the JDK range helper, and audit the same pattern in `HostResponseQueue`.
Test exact-end, empty, negative and overflow ranges with unchanged parser state
after rejection. This is embedding misuse robustness, not the remotely supplied
cursor-count issue in R02.

## Architecture and public API contracts

**Preserve the existing boundaries.** Primitive protocol/render/transport
contracts, flat core storage, copied render ownership and leased publication
are useful boundaries. The completion engine's cold structured source scope,
host-provided I/O, bounded learning and separate versioned persistence are
appropriate. No additional completion security defect was confirmed; its
plaintext/digest limitations are already documented. Input scratch reuse and
mode snapshots should remain synchronous inside session serialization.

Coroutines are useful for owned workers, cancellation and suspending host work.
Replacing primitive mutation loops or short monitors with flows/actors would
not fix these findings. A large file alone is not a reason to split a module.
The concrete ownership corrections are the pen mirror (R07), parser/core saved
state coordination (R12), and lifecycle sequencing (R03–R05/R15).

### G01 — establish an enforceable compatibility boundary before freezing it

No tracked ABI dumps, ABI check or explicit-API configuration was found in the
root/library builds. Broad public constructors and data classes make future
changes costly: `TerminalSession`, `SwingSettings`, `HostPolicy`, and
`TerminalProfile` are concrete review targets. Data classes are not inherently
defects, but adding constructor properties also changes generated `copy` and
default-call signatures; source-compatible changes can still break JVM clients.
See [Kotlin's compatibility guidance](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html).

Before v1, inventory intended extension points, decide Kotlin/JDK minimums and
deprecation policy, then baseline the intended public ABI with
[Kotlin Gradle ABI validation](https://kotlinlang.org/docs/gradle-binary-compatibility-validation.html).
Use explicit API checking for published modules and run ABI validation in CI;
do not silently bless every current public declaration by dumping it first.
Retain an already-compiled consumer to exercise compatible upgrades.

Also settle ownership of `TerminalSession.terminal`, its public constructor and
mutable `renderPublisher`: these expose ways to bypass session serialization or
construct inconsistent collaborator sets. This is an API risk, not a reproduced
concurrent-consumer bug. Restrict accidental surfaces or specify enforceable
ownership/lifetime rules while a pre-v1 change is still possible. Preserve
necessary custom connector/renderer freedom.

### G02 — make release artifacts depend on verification of their revision

[publish-binaries.yml](../../.github/workflows/publish-binaries.yml) publishes
after packaging jobs only. It has no dependency on tests or conformance;
[tests.yml](../../.github/workflows/tests.yml) runs on PR/manual events, and
Maven's separate workflow tests only its Ubuntu build. A failing quality
workflow therefore need not prevent a GitHub binary release.

Create one release verification gate for the exact tag commit: root checks,
native PTY/GUI coverage on supported OSes, generated smoke campaigns, isolated
artifact consumers, ABI checks, plugin packaging/verification and installed
application startup. Validate tag/version agreement. Keep signed/runtime
packaging concerns in their existing product builds.

Native PTY tests are opt-in and CI does not pass `terminal.pty.host=true`.
Plugin CI currently invokes `test`, so it also misses the existing
`check -> verifyNoBundledKotlinStdlib` package guard. Configure and run
[Plugin Verifier](https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html)
for the declared IDE range (`sinceBuild=262`, no upper bound); one IDEA test
fixture is not evidence for all IDE/runtime combinations. Make skipped and
opt-in coverage visible rather than counting a green aggregate as complete.

### G03 — align documentation and performance claims with executable evidence

The reviewed README claims flawless TUI rendering, 60+ FPS without stuttering,
and a zero-allocation memory profile. No reproducible whole-product evidence
supports those guarantees, and R09/R16 plus the paint smoke contradict a literal
zero-allocation frame contract. Define separate budgets for warm terminal-owned
paths, changing content/growth, platform paint, first-use fonts/emoji and end-to-end
latency. Record hardware, OS, JDK/JBR, geometry, input rate, history, GC and workload.

Documentation corrections in this audit remove the broad guarantees and qualify
known partial behavior. Remaining integration docs need executable examples:
the host README imports the removed public `parser.TerminalParser`; core's
`TerminalBuffer` KDoc says all coordinates are zero-based although margin and
rectangle APIs accept DEC-style coordinates. The stale synchronous-write and
lock-free-lease descriptions were corrected in this audit. Compile minimal examples and keep
threading, coordinate and lifetime contracts beside their public APIs.

The additional IDE launch-lock risk merits a focused follow-up: product close
and disposal take `workspaceLock`, also held while a PTY session is created.
A blocked native launch can delay the EDT's cleanup. This was source-reviewed,
not reproduced; establish an entered/release launch test before redesigning it.

## Small, verifiable implementation sequence

| Order | Change set | Exit evidence |
|---|---|---|
| 1 | R01/R02/R11/R17: reply allowlist and bounded arithmetic/work | Hostile byte-stream and range tests, exact replies/denial, parser/core/host suites. |
| 2 | R03/R04/R05/R15: terminal completion and cleanup | Deterministic watcher/reader/worker order tests, final visible frame, failure cleanup, native short-command smoke. |
| 3 | R06/R07/R10/R12: grid and saved-state correctness | Whole/split equivalence, saved-state cross-products, CPR exact bytes, model/differential campaigns. |
| 4 | R13/R14: logical text and selection fidelity | Search/detector/copy assertions across wrapping, reflow, bidi and both selection modes. |
| 5 | R08/G01: standalone artifacts and public API surface | Isolated Kotlin/Java published consumers, reviewed ABI baseline, compiled-client upgrade test. |
| 6 | R09/R16: interactive allocation and search work | Changing-content and selected-paint JMH/JFR, bounded memory, controlled cancellation and EDT handoff tests. |
| 7 | G02/G03: release proof and concise contracts | Exact-tag gated builds, supported-runtime smoke/Verifier, executable examples, scoped measured claims. |

New graphics, richer native keyboard adapters, SSH and IDE placement remain
prioritized through the existing gap map. They do not need to precede fixing
data loss, unsafe replies or basic embedding. Any decision to require them for
v1 is a product-scope decision, not a prerequisite invented by this review.

## Regression coverage

The follow-up tests assert the intended behavior and remain enabled while the
defects are open. They replace the scratch probes that detected buggy behavior.
Existing expectations for reflected DECRQSS failures, bare `q`, premature PTY
closure, and skipped transport disposal were corrected. No production fix or
changelog entry accompanies this test work.

| Finding | Owning tests | Covered dimensions |
|---|---|---|
| R01 | [HostStatusQueryTest](../../ketraterm-host/src/test/kotlin/io/github/ketraterm/host/HostStatusQueryTest.kt), existing core/host response tests | Fixed empty failure for unknown ASCII, controls, Unicode low-byte aliases and malformed UTF-8; split boundaries; response denial; parser recovery. |
| R02 | [HostCursorStateTest](../../ketraterm-host/src/test/kotlin/io/github/ketraterm/host/HostCursorStateTest.kt), [HostBoundedWorkTest](../../ketraterm-host/src/test/kotlin/io/github/ketraterm/host/HostBoundedWorkTest.kt), `CursorEngineTest` | Maximum/saturated down/right counts, margins and recovery; bounded SU/SD with guard rows; CHT/CBT isolated-process completion and final cells/cursor. |
| R03 | [PtyConnectorTest](../../ketraterm-pty/src/test/kotlin/io/github/ketraterm/pty/PtyConnectorTest.kt), [PtyRealProcessTest](../../ketraterm-pty/src/test/kotlin/io/github/ketraterm/pty/PtyRealProcessTest.kt) | Exit before released final read at three read-buffer sizes; EOF before exit; 12,000-byte native final burst and marker. |
| R04 | `PtyConnectorTest`, [TerminalSessionTest](../../ketraterm-session/src/test/kotlin/io/github/ketraterm/session/TerminalSessionTest.kt) | Read failure before/after bytes, process/stream disposal, local-close teardown error suppression; local/remote/error first-event retention, repeated close and recursive callback. |
| R05 | `TerminalSessionTest` | Pending final UTF-8 output across local/remote/error close, synchronized output on/off; incomplete UTF-8 EOF replacement exactly once; already-published positive control. |
| R06 | [HostGraphemeTest](../../ketraterm-host/src/test/kotlin/io/github/ketraterm/host/HostGraphemeTest.kt) | VS16 widening and VS15 narrowing, every split, primary/alternate screen, right edge and bottom scrolling; exact text, cells/clusters, cursor and history equivalence. |
| R07 | `HostCursorStateTest` | DEC/SCO/1048/1049 restore followed by partial SGR; saved foreground/background/underline/styles and unsaved default pen. |
| R08 | [TerminalLibraryConsumerCompilationTest](../../ketraterm-testkit/src/test/kotlin/io/github/ketraterm/testkit/TerminalLibraryConsumerCompilationTest.kt) | Java compilation against isolated exported API variants: host superclass and construction, parser protocol parameters, completion Flow; library/runtime positive controls. |
| R09 | [SwingTerminalSearchTest](../../ketraterm-ui-swing/src/test/kotlin/io/github/ketraterm/ui/swing/api/SwingTerminalSearchTest.kt), [TerminalSearchRefreshBenchmark](../../ketraterm-ui-swing/src/jmh/kotlin/io/github/ketraterm/benchmark/TerminalSearchRefreshBenchmark.kt) | Actual active Swing search at two growing history sizes; aggregate destination-array growth budget and exact result counts; changing-content JMH at 6/80/160 columns and 0/1,000/10,000 retained rows. |
| R10 | `HostStatusQueryTest` | Normal/private CPR, origin off/on, vertical and horizontal margins, byte splits, absolute-coordinate recovery and denial. |
| R11 | `HostStatusQueryTest`, corrected core/host response tests | Literal `SP q` success, bare `q` rejection, byte splits and policy denial. |
| R12 | `HostCursorStateTest` | Screen-local charset save/restore across 47/1047/1049, DEC/SCO forms and byte splits. |
| R13 | [TerminalSearchModelTest](../../ketraterm-ui-swing/src/test/kotlin/io/github/ketraterm/ui/swing/search/TerminalSearchModelTest.kt), [TerminalHyperlinkLineSnapshotTest](../../ketraterm-ui-swing/src/test/kotlin/io/github/ketraterm/ui/swing/api/TerminalHyperlinkLineSnapshotTest.kt) | Wide scalar/cluster, artificial padding versus written space, widening/narrowing reflow, highlight spans and UTF-16-to-cell ownership. |
| R14 | [SwingTerminalSelectionTest](../../ketraterm-ui-swing/src/test/kotlin/io/github/ketraterm/ui/swing/api/SwingTerminalSelectionTest.kt) | Ordinary component resize, narrowing/widening, linear/block selection; selection may clear or preserve the exact previously copied text. |
| R15 | [TerminalWorkspaceTest](../../ketraterm-workspace/src/test/kotlin/io/github/ketraterm/workspace/TerminalWorkspaceTest.kt) | Connector/listener/combined failures; remaining tabs, sessions, notifications and scope cleaned; secondary failure retained; repeated close. |
| R16 | [TerminalSelectionControllerTest](../../ketraterm-ui-swing/src/test/kotlin/io/github/ketraterm/ui/swing/api/TerminalSelectionControllerTest.kt), [TerminalUiBenchmark](../../ketraterm-ui-swing/src/jmh/kotlin/io/github/ketraterm/benchmark/TerminalUiBenchmark.kt) | Unchanged internal projection reuse, viewport clipping and mode changes, immutable prior snapshots and clear; selected/unselected whole-paint JMH. |
| R17 | [TerminalParserTest](../../ketraterm-parser/src/test/kotlin/io/github/ketraterm/parser/TerminalParserTest.kt) | Overflowing slice rejection without altering pending CSI, exact-end empty slices and subsequent valid recovery. |

Run the owning module's `test` task for a focused check, or collect all failures:

```text
./gradlew spotlessApply
./gradlew test :ketraterm-benchmarks:jmhJar --continue
./gradlew :ketraterm-pty:test --tests "*PtyRealProcessTest" "-Dterminal.pty.host=true"
```

These matrices cover the identified failure modes, not every terminal state or
release acceptance condition. Split tests execute every two-chunk partition,
not every possible multichunk partition. Unicode edge tests do not exhaust
custom-margin/resize interleavings. The PTY race assertion is valid for either thread
order, but triggering the current defect is scheduler-dependent. Coroutine cases
share a controlled scheduler. Huge tab inputs run under `-Xint` in disposable
child JVMs with 30-second hang guards; that timeout is not a latency target.

R09's deterministic regression measures copied array capacity, not all JVM
allocation or EDT latency. Cancellable background search and revision-safe
publication still need controlled scheduling tests with their implementation.
JMH workloads provide measurement points, not an established zero-allocation
release budget. R08 covers exported Gradle project variants; Kotlin consumers,
temporary Maven publications and compiled-client upgrades remain G01 work.
G01–G03 also require API/product decisions, supported-platform runs and release
workflow verification; no unit test can substitute for those decisions.

### Follow-up validation results

The final root run reported **4,140 cases: 4,027 passed, 85 failed and 28 skipped**
in module JUnit XML, excluding IntelliJ. Compilation, formatting and benchmark
packaging succeeded. The 85 failures assert the unfixed defects; none are marked
expected-to-fail, disabled, or changed to accept the observed wrong behavior.

| Owning module | Regression failures |
|---|---:|
| Core | 3 |
| Host | 43 |
| Parser | 1 |
| PTY | 4 |
| Session | 12 |
| Testkit consumers | 4 |
| Swing UI | 15 |
| Workspace | 3 |

The separate opt-in Windows `PtyRealProcessTest` run passed all **14 cases**, with
no skips, including the final-output burst. This is one platform/run, not proof
against every native shutdown interleaving. The root skip total includes those
14 opt-in cases plus campaign/platform guards. The nested IntelliJ build was not
rerun for this test-only follow-up; its earlier audit result is recorded above.

Short JMH smoke runs completed for selected 80×24 ASCII/styled/cluster painting
and changing 80-column search with 1,000 history rows (one fork, one one-second
warmup/measurement, GC profiler). These validate the workload harnesses, not a
release budget. Logs and the pre-native root summary are under
`build/release-audit`; the native run replaces the PTY module's default XML.

`git diff --check` passed. The formatter's unrelated existing `SwingTerminal`
line wrap was restored, leaving no production-code changes. `graphify update .`
completed; it reported partial extraction warnings for three Kotlin files, so
the graph remains navigation evidence rather than a substitute for compilation.
