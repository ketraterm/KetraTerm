# Terminal maintainability review

Reviewed on 2026-10-03 at `c6089750` on `audit/terminal-api-design`. The working
tree was initially clean. This review adds regression tests and documentation;
production code, public signatures, compatibility baselines and changelogs are
unchanged. Status and ownership are tracked in the
[gap map](../terminal-feature-gap-map.md#maintainability-review).

## Assessment and scope

The module boundaries are sound. Parser, core, host mapping, input encoding,
session synchronization, transport, rendering and product integration retain
distinct owners. The optional shell-integration producer remains replaceable by
an IntelliJ-owned model. Hosts can supply presentation, completion, clipboard and
settings without adopting standalone windows or workspace policy.

The principal maintenance problem is incomplete ownership of failure and
reentrant transitions. Some essential workers can disappear without terminating
their owner; some optional callbacks can stop essential observation or interrupt
cleanup. There are also duplicated title facts, unchecked integer conversions
and inconsistent configuration serialization. These require focused corrections,
not another module split or a rewrite of every coordinator.

The review covers all root runtime/library modules, standalone and the separate
IntelliJ build, dependency exports, representative public extension paths,
construction and shutdown, parser/grid/input bounds, frame/cache ownership,
completion concurrency and persistence, tests, CI and documentation. Graphify
provided cross-file navigation; important claims were checked against source.
File size and graph degree were investigation signals, not findings.

Completion source I/O remains outside the engine; persistence has a bounded
codec and a single worker. Render caches keep copied primitive data separate
from font and Swing policy. Existing compatibility fixtures and module guides
provide useful boundaries. No new dependency cycle or need for an independent
session-free Swing renderer was established. This is a broad source and runtime
review, not proof that every path is defect-free or a full-frame allocation audit.

## Findings

P1 findings can stall an essential pipeline or strand a process. P2 findings
affect correctness, extension safety, responsiveness or contracts needed before
API freeze. P3 findings are concrete documentation or maintenance debt.

### M01 — P1: Kitty mode pops have unbounded work and incorrect exhaustion

[ScreenBuffer](../../ketraterm-core/src/main/kotlin/io/github/ketraterm/core/state/ScreenBuffer.kt#L232)
executes `repeat(count)` although its keyboard stack holds only 32 entries. The
parser admits saturated counts, so `CSI < 2147483647 u` can monopolize serialized
processing. The safe regression also shows that popping an exhausted, previously
overflowed stack restores nonzero flags. The
[Kitty protocol](https://sw.kovidgoyal.net/kitty/keyboard-protocol/#progressive-enhancement)
requires emptying the stack to reset flags. Bound work by retained depth and
apply the empty-stack reset once. Reconcile existing tests that describe the
incorrect initial-flags restoration; do not preserve it in a faster loop.
The saturated operation was not executed in-process; the missing work bound is
source-confirmed, while exhaustion is reproduced with small counts.

### M02 — P1: unexpected cancellation kills the sole session writer

The [outbound worker](../../ketraterm-session/src/main/kotlin/io/github/ketraterm/session/TerminalSession.kt#L303)
rethrows every `CancellationException`. When a connector or encoder throws one
while the session is active, the supervised child exits but the session stays
open and can accept more input without a writer. Two regressions reproduce this.
Distinguish owner-requested shutdown from unexpected essential-worker failure;
retain the cause and close the session without retrying a possibly partial write.

### M03 — P1: a failing byte listener strands a live PTY process

[PTY output pumping](../../ketraterm-pty/src/main/kotlin/io/github/ketraterm/pty/PtyConnector.kt#L212)
catches only `IOException`. An ordinary exception from `onBytes` terminates the
reader without error delivery or process disposal; the watcher can remain in
`waitFor()`. A synchronized fake-process regression reproduces the leak.
Make the connector supervise its reader boundary, preserve the original failure,
and release the process and streams once while maintaining final-byte ordering.

### M04 — P2: completion can wait forever for a vanished source

[Merged collection](../../ketraterm-completion/src/main/kotlin/io/github/ketraterm/completion/engine/MergedCompletionEngine.kt#L151)
supervises source children but waits for exactly one channel result per source.
An unexpected `Error`, such as a provider `AssertionError` or `LinkageError`,
finishes a child without sending a result or failing the collector. A deterministic
regression reproduces the suspended request after the child has terminated.
Ensure every terminal child outcome either completes accounting or terminates
collection and cancels siblings. Do not hide unexpected failures or rely on a
timeout to repair missing structured completion.

### M05 — P2: popup failures interrupt completion-binding release

[Resource replacement](../../ketraterm-ui-swing-host/src/main/kotlin/io/github/ketraterm/ui/swing/host/SwingCompletionBinding.kt#L86)
and [close](../../ketraterm-ui-swing-host/src/main/kotlin/io/github/ketraterm/ui/swing/host/SwingCompletionBinding.kt#L124)
hide the popup before detaching live observation. A throwing or cancelling custom
view leaves focus listeners, observation or old feedback attached; repeat close
cannot finish cleanup. Four regressions reproduce both operations and failures.
Detach owned observation independently of host presentation, and keep provider
and feedback ownership coherent during replacement.

### M06 — P2: an unfinished show can undo reentrant hide or disposal

[Suggestion showing](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/suggestion/SwingShellSuggestionController.kt#L88)
calls the custom view before setting visibility. If that callback hides or
disposes the terminal, the older show resumes and makes the component visible.
Both paths fail regressions. Commit the transition before notifying the host and
prevent a superseded show from publishing further state.

### M07 — P2: optional title presentation can kill workspace observation

The [workspace state job](../../ketraterm-workspace/src/main/kotlin/io/github/ketraterm/workspace/TerminalWorkspace.kt#L145)
parents a foreground-title collector whose host callback can fail. That failure
cancels essential session-close observation and removes shell metadata listeners.
The regression reproduces lost directory state/delivery and remote-close
notification together. Separate optional presentation failure from essential
tab lifetime and report it at the host boundary.

### M08 — P2: reentrant close publishes an obsolete selected tab

[Tab closure](../../ketraterm-workspace/src/main/kotlin/io/github/ketraterm/workspace/TerminalWorkspace.kt#L232)
captures the next selection before calling `tabClosed`. That callback can close
the survivor or select another tab; the outer operation still announces its old
selection. Two regressions compare callbacks with actual workspace state.
Revalidate or supersede pending selection publication after host reentry; keep
the workspace as the authoritative selection owner.

### M09 — P2: a nested frame read invalidates its enclosing lease

[Core frame borrowing](../../ketraterm-core/src/main/kotlin/io/github/ketraterm/core/render/CoreTerminalRenderFrame.kt#L45)
reuses one validity flag and viewport projection. An inner read clears them on
exit, making the still-running outer callback unusable. Public contracts do not
prohibit nesting. A public-factory regression permits either safely supported
nesting or early explicit rejection, but requires the enclosing lease to survive.
The smallest correction is a documented nonreentrancy check before mutation if
nested leases are not needed; no new lease architecture is implied.

### M10 — P2: adapter title mirrors disagree with public core state

[Adapter title stacks](../../ketraterm-host/src/main/kotlin/io/github/ketraterm/host/HostCommandAdapter.kt#L709)
save adapter-owned title copies even though core exposes authoritative title
mutation. Legal serialized core updates followed by protocol push/pop restore
stale titles. A real byte-stream regression reproduces the divergence.
Retain stacks and notifications in the adapter, but read current title facts
from core; preserve existing public adapter accessors where possible.

### M11 — P2: accepted mouse coordinates overflow before encoding policy

[Mouse events](../../ketraterm-input/src/main/kotlin/io/github/ketraterm/input/event/TerminalMouseEvent.kt#L39)
accept `Int.MAX_VALUE`, but [encoding](../../ketraterm-input/src/main/kotlin/io/github/ketraterm/input/impl/MouseEncoder.kt#L215)
adds one in `Int`; [explicit pixels](../../ketraterm-input/src/main/kotlin/io/github/ketraterm/input/impl/MouseEncoder.kt#L114)
share the problem. Decimal and UTF-8 encoding throw before emitting bytes;
legacy clamping incorrectly suppresses the event. Six exact-byte regressions cover
cell, pixel and fallback paths. Widen before one-based conversion and apply each
encoding's range policy before narrowing. Align pixel-coordinate prose with the
existing zero-based event contract.

### M12 — P2: configuration save/reload corrupts accepted strings

[Configuration output](../../ketraterm-workspace/src/main/kotlin/io/github/ketraterm/workspace/config/TerminalWorkspaceConfigManager.kt#L264)
quotes five fields by raw interpolation; only startup commands use a quoting
helper. Quotes followed by `#` truncate values, and newlines alter parsing or
cause fallback. Twenty round-trip cases cover five fields and four value families;
ten fail and ten pass. Use one correct TOML string-encoding rule for accepted
values. Any new single-line field restriction must be an explicit API decision,
not silent corruption or default substitution.

### M13 — P2: lowering hyperlink retention does not restore the bound

[Policy replacement](../../ketraterm-host/src/main/kotlin/io/github/ketraterm/host/HostCommandAdapter.kt#L88)
changes the limit, but [admission](../../ketraterm-host/src/main/kotlin/io/github/ketraterm/host/HostCommandAdapter.kt#L1134)
evicts at most one entry before adding another. A `4 → 1` reduction therefore
leaves four retained keys after new admissions. The OSC regression verifies the
bound after admission and removal-before-registration callbacks; it does not
require immediate setter-time eviction. Reconcile under the existing serialized
registry owner, not by mutating confined maps inside the volatile policy setter.

### M14 — P2: standalone tab navigation uses hash order

[Neighbor lookup](../../ketraterm-app/src/main/kotlin/io/github/ketraterm/app/ui/TabManager.kt#L221)
iterates `HashMap` keys, while the displayed tab strip uses insertion order.
Ctrl+Tab can therefore disagree with visible adjacency. This is source-confirmed;
no window/PTY integration reproduction was added. Ask the existing tab-strip
owner for neighbors rather than maintaining another order. Verify both
directions, wraparound and deletion through the product's real navigation path.

### M15 — P2: IntelliJ pane creation lacks resource rollback

[Pane creation](../../ketraterm-intellij-plugin/src/main/kotlin/io/github/ketraterm/intellij/ui/KetraTermTerminalPane.kt#L365)
binds Swing and acquires search/shortcut resources before final reconciliation.
Reconciliation registers an application-service listener before the documented
throwing `resourcesFor` call. The caller stores the pane in `panesByTabId` only
after creation returns, so cannot close the unfinished pane. Source confirms
the missing rollback; IntelliJ fault injection was not executed. Construction
must own acquired resources until successful return and reuse pane cleanup once
the pane exists. Session closure alone does not release component/service listeners.

### M16 — P2: the external-frame fallback constructs a palette per read

[The default palette getter](../../ketraterm-render-api/src/main/kotlin/io/github/ketraterm/render/api/TerminalRenderFrame.kt#L129)
constructs immutable color storage; [cache acceptance](../../ketraterm-render-cache/src/main/kotlin/io/github/ketraterm/render/cache/TerminalRenderCache.kt#L643)
reads it each frame. Built-in core overrides the getter with cached state, so
this concerns valid external implementations inheriting the default. A private
shared immutable fallback removes the structural allocation source without an
API change. Surviving allocations, bytes and latency were not measured; verify
with a representative external-frame JMH/GC case rather than an identity assertion.

### M17 — P2: command-output export writes synchronously on the EDT

[Standalone export](../../ketraterm-app/src/main/kotlin/io/github/ketraterm/app/ui/TabManager.kt#L745)
calls `Files.writeString` directly after the chooser. A slow destination blocks
all terminal input and painting on that EDT. The blocking call is source-confirmed;
no filesystem latency was measured. Keep chooser/result presentation on the EDT,
move writing to product-owned I/O work, and verify cancellation and error delivery
with an explicitly gated writer.

### M18 — P2: connector startup has contradictory lifecycle documentation

[Lifecycle prose](../../ketraterm-transport-api/docs/connector-lifecycle.md#L22)
requires idempotent `start`; the [public interface](../../ketraterm-transport-api/src/main/kotlin/io/github/ketraterm/transport/TerminalConnector.kt#L39)
does not settle repeats, while PTY and its tests explicitly enforce start-once.
Shipped startup behavior is consistent; this is a documentation/extension
contract defect. Choose the normative interface rule and align secondary docs
and external connector coverage before freezing it.

### M19 — P2: post-close session mutation policy is unspecified

[Theme updates](../../ketraterm-session/src/main/kotlin/io/github/ketraterm/session/TerminalSession.kt#L546)
ignore closure; resize, width, cursor and input policy have different guards.
Closed sessions suppress normal render publication while supporting retained
reads. Existing contracts do not promise an immutable post-close state or settle
all setters. This is an API decision, not a proven defect. Define which mutating
operations, including resize and input/security policy, are supported, rejected
or ignored after closure, and whether supported changes republish retained render
state. Then test that policy without requiring hosts to inspect implementation details.

### M20 — P3: embedding examples and completion architecture are stale

[Workspace examples](../../ketraterm-workspace/README.md#L99) implement
`colorChanged` with `Int` instead of the current nullable string and construct
profiles with `name` instead of `id`. These signatures are source-confirmed
mismatches; the snippets were not compiled in this review.
[Completion architecture](../../ketraterm-completion/docs/completion-architecture.md#L271)
still describes a debounce timer and custom-painted standalone list despite the
current Flow pipeline and standard list view. Correct the examples against
representative source consumers, remove stale implementation prose, and keep
capability/status inventories in the canonical maps.

### M21 — P3: parser tests maintain unused UTF-8 state

[ParserState fields](../../ketraterm-parser/src/main/kotlin/io/github/ketraterm/parser/runtime/ParserState.kt#L92)
and their reset method have no production reader or decoder writer. Only tests
populate/assert them; the actual decoder is owned by `TerminalParser`. Remove
the fictional state and its implementation-mirroring assertions while retaining
byte-stream reset, malformed-input and EOF tests. This is dead-state maintenance
debt, not evidence of broken UTF-8 decoding.

## Regression coverage and validation

The added tests assert intended behavior and use scheduler control, explicit
signals and EDT turns. The completion regression allows isolation or propagation
of an unexpected failure, but forbids a stranded collector. The nested-frame
regression permits early rejection or safe nesting. Workspace fault injection
accepts the exact injected diagnostic only after behavior assertions and teardown
succeed; unrelated or suppressed failures still fail. No production fix, disabled
test, weakened existing assertion or new known-failure wrapper is included.

| Finding | Regression owner | Added cases | Current failures |
| --- | --- | ---: | ---: |
| M01 | `ScreenBufferTest`, counted pop exhaustion | 1 | 1 |
| M02 | `TerminalSessionOutboundTest`, connector/encoder cancellation | 2 | 2 |
| M03 | `PtyConnectorTest`, byte-listener failure | 1 | 1 |
| M04 | `MergedCompletionEngineTest`, missing terminal source outcome | 1 | 1 |
| M05 | `SwingCompletionBindingTest`, close/replacement × failure/cancellation | 4 | 4 |
| M06 | `SwingTerminalThreadingTest`, reentrant hide/dispose | 2 | 2 |
| M07/M08 | `TerminalWorkspaceTest`, title failure and reentrant selection | 3 | 3 |
| M09 | `CoreTerminalRenderFrameTest`, enclosing lease survival | 1 | 1 |
| M10/M13 | `HostCommandAdapterTest`, titles and lowered hyperlink limit | 2 | 2 |
| M11 | `MouseEncoderTest`, maximum accepted coordinates | 6 | 6 |
| M12 | `TerminalConfigTest`, string round trips | 20 | 10 |
| **Total** | | **43** | **33** |

Three existing app/plugin settings tests were strengthened to verify that every
listener runs when exception instances are reused. They pass. A suspected
self-suppression defect was rejected: compiled Kotlin calls the stdlib helper,
which already guards identical exceptions. These are passing controls, not findings.

| Check | Result |
| --- | --- |
| Initial completion, host-I/O and persistence suites | Pass before adding the new completion regression. |
| Root `spotlessApply test checkKotlinAbi spotlessCheck --continue` | All tasks complete; nine test tasks fail only on the new regression identities. |
| Final core/host and workspace reruns | Verify corrected Kitty semantics, additional OSC policy coverage and policy-neutral title fault injection. |
| Root test XML after final rerun | 4,839 cases: 33 failures, zero errors, 42 skips/aborts; remaining 4,764 pass. |
| Separate IntelliJ `spotlessApply test spotlessCheck --continue` | 244 cases pass, no failures, errors or skips. |
| ABI and formatting | Root seventeen-library `checkKotlinAbi`, root and plugin Spotless checks pass. No baseline changes. |

The final owner-suite reruns supplement the full root run; the aggregate is not
presented as one subsequent green full-suite execution. All failures are listed
by finding above and remain deliberately visible. Initial test-compilation
mistakes in two new fixtures were corrected before recording these results.

M01's saturated count, M14/M15 product integration, M16 allocation impact and
M17 slow export still need dedicated verification during their fixes. M18/M19
require contract decisions before choosing assertions. M20 examples need an
executable consumer; M21 removal must preserve existing behavioral coverage.
Graphify update completed with seven partial Kotlin AST-extraction warnings;
important paths were verified in source and compiled tests, not inferred edges.
Native PTY opt-in, differential campaigns/nightly shards, published/retained
compiled-client upgrade tasks, non-Windows runtime, Plugin Verifier, installed
products and full-frame JMH were not rerun. Existing G02/G03 gates remain
separate and incomplete.

## Suggested correction groups

Start with M01's bounded-work correction, then M02/M03's essential worker
lifetime. Coordinate the remaining changes through these owner-based groups.

1. **Essential worker termination:** M02/M03/M04. Fix each owner independently
   while aligning cause retention, shutdown and cancellation semantics.
2. **Bounded and correct protocol state:** M01/M11/M13. Restore finite work and
   range invariants; retain byte-stream coverage and update security/resource docs.
3. **Host callbacks and reentry:** M05/M06/M07/M08/M15. Use explicit state,
   notification and resource phases. Keep ordinary transitions out of generic
   cleanup wrappers; introduce a shared rule only for actually repeated behavior.
4. **Authoritative facts and durable values:** M09/M10/M12/M14. Settle lease
   reentry, remove title duplication, encode accepted strings and use visible tab order.
5. **Pre-freeze contracts and responsiveness:** M16/M17/M18/M19, followed by
   M20/M21 cleanup. Measure the palette path and settle lifecycle promises before
   refreshing the development baseline; then proceed to G02 and G03.

Small fixes need not be folded into one architectural rewrite. Changes to a
shared state transition should be designed together, then verified in the owner
and a representative external-host path. Existing A01–A15 evidence remains valid;
these findings extend the review to different paths and do not freeze stable APIs.
