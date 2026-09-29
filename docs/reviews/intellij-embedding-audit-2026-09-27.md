# IntelliJ embedding audit — 2026-09-27

This audit examines a concrete embedding scenario: IntelliJ owns its windows,
tabs, process environment and optional shell
integration/completion, while reusing KetraTerm's terminal and rendering stack.
The original review records findings rather than production fixes; E02 and E04
include later scope clarifications. Current status belongs in the feature/gap maps.

KetraTerm revision: `d26fa4553962ab11cc1de34843728cf1f1e745c3` plus the uncommitted
audit/regression work. The [terminal quality audit](terminal-quality-audit-2026-09-27.md)
remains applicable. Capability status belongs in the feature/gap maps.

## What JetBrains actually embeds

Inspected IntelliJ source revision
[`fae38642e8c633fcc2cf2b5a4a98725d0afb4472`](https://github.com/JetBrains/intellij-community/tree/fae38642e8c633fcc2cf2b5a4a98725d0afb4472/plugins/terminal),
dated September 27. JetBrains uses **libghostty-vt as a headless emulator**,
retaining its own terminal frontend. This is not an embedded Ghostty window or
GPU renderer. Its FFM wrapper invokes native parsing, grid/scrollback/reflow,
terminal modes, replies, and key/mouse encoders; the surrounding Kotlin session
owns stream processing, response forwarding and lifecycle.
Sources: [GhosttyTerminalEmulator](https://github.com/JetBrains/intellij-community/blob/fae38642e8c633fcc2cf2b5a4a98725d0afb4472/plugins/terminal/emulator/src/com/intellij/terminal/emulator/impl/ghostty/GhosttyTerminalEmulator.kt),
[GhosttyTerminalSession](https://github.com/JetBrains/intellij-community/blob/fae38642e8c633fcc2cf2b5a4a98725d0afb4472/plugins/terminal/frontend/src/com/intellij/terminal/frontend/session/ghostty/GhosttyTerminalSession.kt).

JetBrains also owns its PTY/process setup, AWT/IDE input policy, grid-to-text/style
projection, `EditorImpl` rendering, selection, tabs and disposal. Its shell
controller interprets proprietary OSC 1341; completion remains an IDE service.
Sources: [output projector](https://github.com/JetBrains/intellij-community/blob/fae38642e8c633fcc2cf2b5a4a98725d0afb4472/plugins/terminal/frontend/src/com/intellij/terminal/frontend/session/ghostty/TerminalEmulatorOutputProjector.kt),
[editor factory](https://github.com/JetBrains/intellij-community/blob/fae38642e8c633fcc2cf2b5a4a98725d0afb4472/plugins/terminal/frontend/src/com/intellij/terminal/frontend/view/impl/TerminalEditorFactory.kt),
[shell controller](https://github.com/JetBrains/intellij-community/blob/fae38642e8c633fcc2cf2b5a4a98725d0afb4472/plugins/terminal/frontend/src/com/intellij/terminal/frontend/session/TerminalShellIntegrationController.kt),
[completion service](https://github.com/JetBrains/intellij-community/blob/fae38642e8c633fcc2cf2b5a4a98725d0afb4472/plugins/terminal/frontend/src/com/intellij/terminal/frontend/view/completion/TerminalCommandCompletionService.kt).

The source's [registry declaration](https://github.com/JetBrains/intellij-community/blob/fae38642e8c633fcc2cf2b5a4a98725d0afb4472/plugins/terminal/resources/META-INF/terminal.xml#L44)
enables Ghostty by default while describing it as experimental; a nearby Kotlin
comment still describes the older default. This snapshot does not establish
released-product rollout. The inspected integration APIs are internal, so they
are an ownership reference, not a stable extension contract to copy.

## KetraTerm adoption paths

| Host wants to retain | Existing composition | Assessment |
|---|---|---|
| Windows, tabs, process/remote connector and product services | `TerminalSession.create` + `SwingTerminal` + `SwingHostServices` | Best-supported path. No KetraTerm workspace, PTY launcher or completion engine required. |
| Its renderer and frontend models | Core/parser/host/input, optionally session; consume public render frames/cache | Existing contracts support this. Independent artifact consumption still needs R08/G01 verification. |
| Its session/runtime, while reusing our complete Swing renderer | Not a supported composition: `SwingTerminal.bind` requires `TerminalSession` | E04 scope decision: independent Swing renderer reuse is not required; no extraction is planned. |

The existing IDE pane is a useful example, not the library integration API. It
supplies native clipboard, fonts, dispatch, shortcuts, context menus, scrollbar
and completion presentation through host services. `unbind` and `dispose` leave
the session open; the host owns session lifetime. Rebinding cancels old view
collection and clears view state. See
[SwingHostServices](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/api/SwingHostServices.kt)
and [KetraTermTerminalPane](../../ketraterm-intellij-plugin/src/main/kotlin/io/github/ketraterm/intellij/ui/KetraTermTerminalPane.kt).

## Findings

### E01 — P2: Swing focus reporting is disconnected

[SwingTerminalInputController](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/input/SwingTerminalInputController.kt#L95)
updates cursor/popup focus state but never calls the session's focus encoder.
Applications enabling DEC 1004 receive neither `CSI I` nor `CSI O` from these
events. The input/session implementation already supports both.

Forward `TerminalFocusEvent` through the currently bound session. Keep mode
gating and byte encoding in input/session. Six new cases in
[SwingTerminalThreadingTest](../../ketraterm-ui-swing/src/test/kotlin/io/github/ketraterm/ui/swing/api/SwingTerminalThreadingTest.kt#L64)
cover default/enabled/reset mode, unbinding, disposal and rebinding. The enabled
and replacement-session cases fail on missing bytes; the controls pass.

### E02 — API gap: replacing the complete shell integration model

At the audited revision, `TerminalSession.create` always installed an OSC 133
recorder, bounded command extractor, and OSC-based startup readiness. A different
script emitting OSC 7/133 worked, but a host retaining its existing semantic shell
model could not replace those producers. Public timeline mutations did not supply
active editing or readiness, and the standard parser did not expose JetBrains'
OSC 1341 protocol.

The initial active-edit source addressed suggestions only. The clarified
embedding requirement covers prompt and command boundaries, history metadata,
directories, editing, and readiness together, with no second OSC recorder.
The selected design keeps neutral model/runtime contracts in session and moves
KetraTerm's recorder/extractor into an optional shell-integration module. Hosts
adapt existing semantic events and flows; custom wire parsing and shell-script
installation remain theirs. Startup can use host readiness through the session's
ordered writer without requiring OSC bytes.

Current implementation and regression status are recorded only under
[embedding contracts](../terminal-feature-gap-map.md#embedding-contracts).
The [session composition guide](../../ketraterm-session/README.md#host-owned-shell-integration)
shows host selection, stable-line capture, and ownership requirements.

### E03 — API gap: custom-context completion lacks an explicit request path

[`requestShellSuggestions`](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/api/SwingTerminal.kt#L1314)
is documented as automatic and obeys the automatic-popup preference. The explicit
`requestActiveShellSuggestions` bypasses that preference but obtains its context
only from the bound session. There is no public request combining host-supplied
context, explicit intent and the reusable provider/cancellation pipeline.

This is not a failing implementation of the current contract. The host can
collect its own provider and call `showShellSuggestions`, or own its entire
popup. A narrow explicit-trigger option would let it retain our request lifecycle
without adopting our shell model. Keep provider, view, keymap and acceptance
replacement independent; those seams already exist.

### E04 — API decision: renderer reuse and configuration authority

[`SwingTerminal.bind`](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/api/SwingTerminal.kt#L613)
requires `TerminalSession`; [GridPainter](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/render/GridPainter.kt#L44)
is internal. Binding applies width policy, palette, cursor/paste settings and grid
geometry. The bind documentation's observation-only wording is therefore
incomplete: this view also configures the session.

**Scope decision recorded 2026-09-28:** Independent reuse of KetraTerm's Swing
renderer is not required. Keep its `TerminalSession` dependency; the proposed
independent rendering boundary is no longer planned. Hosts using their own
renderer retain the existing core/render contracts, with optional session reuse.
Replacing shell integration or suggestion providers remains separate E02/E03
work. The canonical decision and remaining public binding-documentation work
are recorded under [embedding contracts](../terminal-feature-gap-map.md#embedding-contracts).

One session currently publishes one viewport. Two independently scrolling views
of one process are not supported by binding two components; separate sessions
are separate pipelines, not two views of the same process. This is a documented
composition limit, not another newly discovered race.

### E05 — P2 boundary defect: workspace bootstrap installs standalone helpers

[`TerminalShellIntegrationBootstrap.apply`](../../ketraterm-workspace/src/main/kotlin/io/github/ketraterm/workspace/TerminalShellIntegrationBootstrap.kt#L76)
installs `ketra`/`ketra.bat`, prepends their directory to `PATH`, and sets
`KetraTerm_CONFIG_PATH` to the default standalone TOML file whenever supported
shell integration is enabled. Its `ketra config` command opens that file. The
IntelliJ product instead stores IDE/project settings through its own services.
This optional workspace layer therefore brings unrelated product configuration
and command policy into an otherwise neutral local-session bootstrap.

Move the standalone helper/config augmentation to product wiring or an explicitly
selected product operation. Preserve neutral OSC 133/OSC 7 hooks and caller-owned
environment values. Hosts using their own connector can already avoid workspace
entirely; fixing this should not add a new general launch framework.

Three regressions in
[TerminalShellIntegrationBootstrapTest](../../ketraterm-workspace/src/test/kotlin/io/github/ketraterm/workspace/TerminalShellIntegrationBootstrapTest.kt#L345)
cover default Bash bootstrap, host-supplied Bash configuration/PATH, and PowerShell
with mixed-case `Path`. They assert that shell markers remain available while
configuration, executable lookup and standalone helper installation stay
host-owned. All three expose the current interference. They replace the previous
test that required the product coupling.

## Verification requirements

- Build external Kotlin and Java consumer fixtures for the supported adoption
  paths, using published artifacts. Verify construction and disposal without
  accidental PTY/workspace/product dependencies. R08 remains open.
- Exercise an IDE-owned connector and shell model, custom completion, native
  keymap, tab/editor-container reparenting and project disposal. Assert queued
  callbacks cannot revive disposed views or reach a replacement session. API-gap
  acceptance tests must use the chosen contract, not invented future method names.
- Review the low-level custom-encoder exception before API freeze: supplied
  encoders own their sink and remain synchronous; they cannot automatically join
  the private session writer. Also make `create`'s required render-reader
  capability explicit; its `TerminalBuffer` argument is runtime-cast today.
- Verify supported IDE runtimes and dependency visibility. The plugin targets
  2026.2/262+ and excludes its own JNA; its manifest does not declare the new
  `intellij.libraries.jna` module. JetBrains' [September 25 announcement](https://platform.jetbrains.com/t/intellij-platform-transition-from-jna-to-ffm-for-native-access/5124)
  requires that declaration for IDE-bundled JNA use in 2026.3. This is a concrete
  compatibility risk to verify, not a reproduced crash. Hosts supplying their
  own connector avoid KetraTerm's PTY/JNA dependency entirely.
- Continue the original review's sustained performance, native-platform,
  protocol/model campaigns and release gates. Neither Ghostty's architecture nor
  passing targeted tests establishes whole-product zero allocation or stability.

## Validation and limits

Source review used Graphify navigation and direct API/build/source inspection;
the graph was updated after the test additions. Upstream code was not built or
benchmarked. Both focused suites compiled and ran:

| Suite | Tests | Passed | Expected failures |
|---|---:|---:|---:|
| `SwingTerminalThreadingTest` | 27 | 25 | 2 |
| `TerminalShellIntegrationBootstrapTest` | 21 | 18 | 3 |

The failures assert correct focus reporting and host-neutral bootstrap behavior
and remain enabled. No tests were skipped and there were no fixture errors.
`spotlessApply` succeeded; the unrelated existing production line-wrap change
was restored. No production fix, changelog entry, native GUI focus test or new
full-root run accompanies this follow-up. E02/E03 are capability/contract
decisions; tests for unchosen APIs would not prove their correct design. E04's
later scope decision requires no new renderer API or behavioral tests; its
remaining work is public contract documentation.
