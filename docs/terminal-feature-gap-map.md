# Terminal Feature Gap Map

This is the living backlog of gaps, TODOs, verification needs, and policy constraints in the KetraTerm terminal pipeline and its product hosts.

For a complete directory of supported features, see the [Terminal Feature Map](terminal-feature-map.md).

The target is a modern, secure, xterm-compatible terminal pipeline for contemporary shells and TUIs. Obsolete or risky legacy protocols remain excluded unless they earn their place.

## Status Labels

- `TODO(parser)`: byte/protocol recognition or semantic dispatch is missing.
- `TODO(core)`: terminal state, grid physics, pen storage, or public API is missing.
- `TODO(host)`: parser and core both have enough shape, but the integration bridge is incomplete.
- `TODO(session)`: runtime synchronization, host-side state, or session-owned metadata is missing.
- `TODO(transport)`: a connector implementation or transport integration is missing.
- `TODO(render)`: render contracts or copied render data are missing.
- `TODO(ui)`: reusable UI presentation, interaction, or rendering behavior is missing.
- `TODO(input)`: host-bound keyboard/mouse/paste encoding is missing.
- `TODO(completion)`: completion evaluation, ranking, learning, or source-work lifecycle is missing or incorrect.
- `TODO(host/profile)`: product host integration, profile, or settings behavior is missing.
- `TODO(policy)`: feature needs an explicit security or compatibility policy before implementation.

Combined labels name every owner needed to close one gap; `host/profile` means product-host integration rather than the parser-to-core adapter. A policy or test limitation alone does not imply missing parser behavior.

---

## Terminal Targets & Priority

These tiers rank terminal behavior across standalone and embedded hosts. Reproducible errors in output, grid, input, or security policy take precedence over new protocols and product conveniences. Product integration gaps remain documented below without determining these emulation tiers.

### Tier 1: Current correctness and security defects

The [2026-09-27 terminal quality audit](reviews/terminal-quality-audit-2026-09-27.md)
reproduced unsafe DECRQSS replies, cursor-count overflow, output-loss and cleanup
failures, saved-state corruption, and logical-text defects. Bounded tab work is
also required. The owner entries below track these corrections; they take
precedence over optional protocol extensions. R06 now has an explicit
[streaming placement disposition](#r06-streaming-placement-policy), including
narrowly tracked known failures of the stronger chunk-equivalence requirement.

Correct-behavior regressions and their validation limits are indexed in the
audit's [regression coverage](reviews/terminal-quality-audit-2026-09-27.md#regression-coverage).
Tests may fail until their owning defects are fixed; adding coverage does not
close these entries.

The [2026-10-03 maintainability review](reviews/terminal-maintainability-review-2026-10-03.md)
records the corrected worker-termination, bounded-work and extension-lifecycle
findings under its [owner entries](#maintainability-review). The subsequent
[API adoption and evolution review](reviews/terminal-api-evolution-review-2026-10-03.md)
records the corrected startup, ownership and construction contracts and tracks
remaining host-adoption work below;
G02/G03 remain deferred by the current work scope.

- `Done(ui)`: visually verify agy help-transition animation after removing frame-triggered resizing. Default alternate padding now redistributes the primary horizontal inset and preserves vertical insets; regressions cover physical resizing while alternate-screen content is active. The resize/clear defect is corrected, but animation parity is not established.

- `DONE(session)`: host editing-context observation emits an initial revision even when context is unavailable. Deterministic subscription/restart regressions reproduce the missed-null cancellation race. The cursor/wrap campaign model also now applies the documented region-height bound to counted scrolling, with the minimized oversized-scroll regression retained.

### Tier 2: Regression coverage and modern compatibility

- Complete [host metadata for richer Kitty keyboard flags](#deferred-kitty-keyboard-protocol-scope).

### Tier 3: Optional protocol extensions

- [Sixel and Kitty graphics](#graphics-protocols) require bounded image storage and rendering as well as protocol recognition.
- [XTCHECKSUM](#csi-protocols), broader [national replacement charsets](#esc-protocols), and other policy-gated extensions need a demonstrated compatibility need before promotion.

---

## Intentional Non-Goals

These are not badges of compatibility for this project. They expand attack surface or maintenance cost without meaningful modern terminal value.

- Tektronix 4014 emulation.
- Media Copy / printer passthrough (`CSI i`).
- X11-specific font loading protocols.
- Blind OSC 52 clipboard writes. Clipboard writes are supported only after bounded parsing, size checks, configured policy allowance, and product-host clipboard callback routing.
- Unbounded or unaudited DCS/OSC responses.
- Literal "everything xterm ever accepted" parity.
- Tertiary Device Attributes (`DA3` / `CSI = c`) query response (excluded to prevent unique hardware serial number leak/user fingerprinting).
- Window position reporting (`CSI 13 t`) query response (excluded to prevent screen pixel coordinate leak/clickjacking).
- ReGIS / DEC vector graphics without a demonstrated product need.
- Raw 8-bit C1 control mode without a demonstrated contemporary compatibility need; OSC payload bytes `0x80..0xFF` remain data.
- `XTSETTCAP` (`DCS + p`) termcap/terminfo mutation, which would change the capability data used for keyboard configuration and replies; KetraTerm keeps its advertised capability identity fixed.

---

## Parser Gaps

### CSI Protocols
- `DONE(parser/core)`: public parser and response-reader slices reject overflowing ranges before processing or copying. Tests verify invalid ranges, empty end slices, preserved pending CSI/UTF-8 input, and unchanged queued/destination bytes. Resolves audit [R17](reviews/terminal-quality-audit-2026-09-27.md#r17--p3-parser-slice-validation-accepts-an-overflowing-range).
- `DONE(parser/host)`: saved charset designations and locking shifts use two parser-owned slots selected from the sink�s actual active screen. Effective `1047`/`1049` entry clears the alternate slot; `1049` saves/restores primary only on an actual transition. DEC/SCO/1048, mixed/repeated switches, denied transitions, defaults and resets are covered. Resolves audit R12; historical evidence is unchanged.
- `DONE(parser/host)`: parameterless ANSI/SCO `CSI s` / `CSI u` compatibility, including mode-aware DECSLRM disambiguation and shared DEC/SCO cursor and charset save/restore. The [feature map](terminal-feature-map.md#1-terminal-protocols--control-sequences) defines the supported forms; byte-stream tests cover mixed forms, chunk boundaries, margin changes, resets, screen-local cursor slots, resize, and malformed input.
- `DONE(parser/host)`: bounded CSI collection rejects the entire command on field overflow before excess input can overwrite retained parameters or dispatch a truncated request. The [CSI parameter resource contract](terminal-feature-map.md#csi-parameter-resource-contract) defines the 32-field limit, shared subparameter budget, and recovery policy. Parser and host byte-stream tests cover exact capacity, empty fields, colon boundaries, chunking, cancellation, and suppression of pen, mode, cursor, and query effects.
- `DONE(parser/core/host/input/session/host/profile/policy)`: ANSI and DEC private mode status requests/reports (`DECRQM` / `DECRPM`) use an explicit core allowlist, effective mode state, and conservative host capability declarations. Unsupported status queries return `0` when replies are allowed; terminal-response denial is silent. The [mode status contract](terminal-feature-map.md#mode-status-reports) defines the supported modes, host-dependent cases, and malformed-input rules. Tests cover byte splits, reset/resize behavior, live Backarrow defaults, synchronized-output timeout, query purity, outbound ordering, and ingress progress during a blocked paste with a concurrent input-policy update.
- `DONE(parser/core/host)`: rectangular erase (`DECERA`), selective erase (`DECSERA`), fill (`DECFRA`), copy (`DECCRA`), and checksum response (`DECRQCRA`) preserve active margin/origin coordinates; mutation operations preserve wide/cluster span integrity and `DECCRA` uses overlap-safe snapshot semantics. Copy/checksum intentionally support only the active single page (`0` omitted or `1`); checksum responses are terminal-response-policy gated and use the VT420 default 16-bit algorithm.
- `TODO(parser/core/policy)`: xterm `XTCHECKSUM` extensions (`CSI Ps # y`) lack dispatch, checksum modes, and a compatibility policy. The implemented DECRQCRA path uses only the base VT420 behavior: erased/spacer cells omitted, base glyph values masked to eight bits, and supported legacy video attributes included; no color, combining-sequence, or alternate xterm extension semantics are claimed.
- `DONE(parser/core/host)`: DECSACE, DECCARA, and DECRARA implement VT420's stream-versus-exact-rectangle extent, ordered visual SGR subset, blank materialization policy, and atomic wide/cluster attribute updates without changing glyph payloads, protection, hyperlinks, or the current pen.
- `DONE(parser/core/host)`: DECIC and DECDC insert/delete columns across the active vertical scroll region, honor horizontal margins, preserve cursor position and active-buffer isolation, and repair wide/cluster span boundaries before each row shift.
- `DONE(core/host)`: broader left/right-margin scrolling coverage verifies `SU`, `SD`, `IL`, `DL`, LF/VT/FF, `IND`, `NEL`, `RI`, and automatic wrapping through the parser-to-core path. Fixed whole-row scrolling outside horizontal margins and partial-row admission to history. Count defaults and bounds, byte splits, origin mode, and alternate-screen isolation are covered; this does not claim additional xterm scrolling extensions.

### ESC Protocols
- `TODO(parser)`: broader ISO 2022 national replacement sets:
  - UK, Dutch, Finnish, French, German, Italian, Norwegian/Danish, Spanish, Swedish, Swiss, Portuguese. US ASCII designation is already implemented.

### OSC Protocols
- `DONE(parser/host/session/pty/workspace/ui/policy)`: [OSC 52 clipboard writes](terminal-feature-map.md#bounded-osc-52-writes) support bounded UTF-8 text and Deny / Ask / Allow permissions in standalone and IntelliJ.
- `TODO(host/ui/policy)`: OSC 52 writes to primary/secondary selections and cut buffers, and reads from secondary selections/cut buffers, remain unsupported.
- `DONE(parser/host)`: explicit OSC encoding and recovery rules distinguish replacement-decoded display text from strictly validated structured metadata and clipboard text. Rejected links clear active context; other rejected metadata retains prior state. The [OSC encoding contract](terminal-feature-map.md#osc-encoding-and-recovery-contract) defines family-specific effects, audit precedence, overflow, abort/EOF recovery, and scalar-safe host limits. Legacy encoding detection and fallback are intentionally absent.

### Graphics Protocols
- `TODO(parser/core/render/ui)`: Sixel (`DCS ... q`) inline graphics need protocol dispatch, image storage, render contracts, and Swing painting. Transfer and retained-image policy is tracked [below](#session-transport-rendering-and-host-integration-gaps).
- `TODO(parser/core/render/ui)`: Kitty graphics use APC (`ESC _ G ... ESC \`), not DCS. The parser currently consumes APC without dispatch; image storage and rendering are also absent. Transfer and retained-image policy is tracked [below](#session-transport-rendering-and-host-integration-gaps).

### Text and Unicode
- `DONE(core/policy)`: audit [R06](reviews/terminal-quality-audit-2026-09-27.md#r06--p1-chunk-boundaries-change-emoji-placement-and-can-delete-text) is resolved within the [streaming placement policy](terminal-feature-map.md#streaming-grapheme-placement). In-row cursor/wrap correction prevents following-text loss; rejected writes clear the continuation target. Full chunk-equivalent layout remains an accepted limitation, not an implemented capability. No rollback machinery is required. Historical audit evidence and the original regression assertions are preserved; their current disposition is below.
- `DONE(parser)`: malformed UTF-8 recovery is exercised immediately before and inside ESC, CSI,
  OSC (BEL/ST/CAN/SUB), DCS ST, and end-of-input, with every split boundary proving that malformed
  bytes do not print or complete stale structural commands.
- `DONE(parser/core)`: long-grapheme retention has an explicit bounded fallback: retain the first 32 codepoints, discard excess continuations while advancing segmentation context, and resume storage at the next actual boundary. Overflow introduces no additional cell writes, and discarded selectors do not affect width. Parser publishes full retained sequences through `updatePreviousCluster`; core no longer reconstructs continuations in scratch storage. Read-boundary updates are batched and consumed synchronously with original cell attributes preserved. Parser/host regressions cover read boundaries, overflow recovery, and retained content; selection tests cover copying through wrapping and reflow. Exact text beyond the retained prefix is intentionally unavailable; direct core cluster writes keep their existing contract. See the [grapheme retention contract](../ketraterm-parser/docs/grapheme-segmentation.md#bounded-retention).

#### R06 streaming placement policy

`HostGraphemePolicyTest` enforces prompt publication, in-place width changes,
committed wraps/scrolls/overwrites/insert shifts, right-margin clipping, tiny grids,
and rejected-prefix recovery. Ordinary parser/core/host tests still enforce
segmentation, retained content, attributes, cursor correction, malformed UTF-8,
structural controls and the 32-codepoint bound.

Four original `HostGraphemeTest` oracles ask for the stronger, unsupported
whole-input/split-input equivalence. They execute during normal host tests:

| Retained oracle | Known-failure invocations | Recorded difference |
|---|---:|---|
| `narrowing restores text overwritten by the provisional wide prefix` | 1 | An overwritten neighbor is not restored. |
| `provisional width changes are independent of chunks with occupied cells and scrolling` | 8 | A published narrow prefix remains at the margin when later widened; an initially wide write with autowrap disabled is rejected. |
| `tiny grids and already pending wraps retain chunk equivalence` | 1 | Late narrowing does not undo an eviction on a one-cell grid. |
| `variation selectors preserve right margin placement across every byte split` | 4 shrinking cases | Late narrowing does not move a wrapped cluster back. The four widening cases remain ordinary passing tests. |

`knownR06Failure` accepts only the recorded assertion type and exact messages,
including every failure from the byte-split aggregate. Matching outcomes are
reported as **skipped/aborted known failures**, with the original assertion as
cause. A changed/additional failure or unexpected pass fails the test and requires
review; remove an expectation when its oracle passes. No test is disabled, and
there is no global ignore-failures setting. The older nested-loop oracles still
stop at their first failing assertion; their skip is not evidence that later
iterations passed. The passing policy tests provide separate boundary coverage.

Run `./gradlew :ketraterm-host:test --tests '*HostGrapheme*' --tests '*KnownR06FailureTest'`
to verify this disposition. Closing R06 means accepting the stated streaming
semantics, not claiming arbitrary chunk-independent placement.

---

## Core Gaps

### Grid Operations
- `DONE(core/host)`: cursor movement and origin translation clamp before addition; tab traversal stops at the margin, and counted scrolling is capped by region height in core. Saturated-count regressions verify bounded work, byte splits, margins, exact history retention, primary/alternate isolation, and wide/cluster cells. Resolves audit R02.
- `DONE(core)`: deterministic randomized left/right-margin properties cover ICH/DCH, selective erase, IL/DL, and partial-region scroll up/down. They preserve guard columns and rows outside the scroll rectangle and verify protected wide spans plus wide/cluster storage invariants.
- `DONE(core/host)`: alternate-screen byte-stream coverage verifies exact primary history retention and zero alternate history across every `47`/`1047`/`1049` entry/exit pairing, repeated commands and re-entry, screen-local `1048` saves, and ordered private-mode lists. `ED2`, repeated `ED3`, and repeated `DECSTR` tests verify active-buffer clearing or text preservation, including combining/wide text and saved-cursor behavior. `DECCOLM` tests cover both 80/132-column directions and current-width requests, preserving primary history while alternate is active and clearing primary history when primary is active.
- `DONE(core)`: resize/capacity tests verify exact retained rows with zero, bounded, and spare history capacity, oldest-row eviction during narrowing and height shrink, and repeated `47`/`1047`/`1049` resize cycles without alternate text leaking or evicted rows returning. Cursor and scrollback anchors account for reflow eviction, including empty rows, wide characters, and grapheme clusters; evicted viewport anchors clamp to the oldest retained row.
- `DONE(core/session/ui)`: soft-wrap text reconstruction preserves written and erased spaces for linear selection, command capture, clipboard copy/paste, and retained-output export. Core distinguishes artificial wide-character wrap padding from meaningful empty cells and preserves that distinction through resize/reflow. Selected hard line breaks survive empty selection endpoints; block selections retain physical row breaks.

### Unicode Width
- `DONE(core/host/ui)`: invalid/unassigned codepoint width policy is explicit in the [core contract](../ketraterm-core/docs/terminal-core-contract.md#unicode-scalar-and-width-policy). Typed scalar/cluster writes reject non-scalars atomically; string writes repair unpaired surrogates. Pinned Unicode tables preserve reserved wide ranges and ambiguous-width behavior, with byte-split integration coverage for replacement, wrapping, and cursor alignment, plus real-buffer selection extraction through resize/reflow.

### Query and Response Channel
- `DONE(core/policy)`: unsupported DECRQSS selectors emit only `DCS 0 $r ST`; no request text reaches the reply encoder. Core and byte-stream regressions cover controls, Unicode low-byte aliases, malformed UTF-8, every two-chunk split, bytewise input, payload bounds, recovery, supported replies, and complete response suppression when denied. Resolves [audit R01](reviews/terminal-quality-audit-2026-09-27.md#r01--p1-unsupported-decrqss-reflects-control-bytes-into-application-input).
- `DONE(core)`: DECRQSS cursor style admits only `SP q`, reports the effective shape/blink code `1`..`6`, and rejects bare `q` through the empty failure path. Core and byte-stream tests verify all six styles, configured defaults, exact selector matching, chunk splits, alternate-screen restoration, and response denial. Resolves [audit R11](reviews/terminal-quality-audit-2026-09-27.md#r11--p2-the-advertised-decrqss-cursor-style-selector-is-incorrect).
- `DONE(core/host)`: normal/private CPR use one-based coordinates relative to the top margin under DECOM and the left margin under DECOM plus DECLRMM. Core and byte-stream tests cover all mode combinations, reset behavior, restored positions before the origin, chunking, and response-policy denial. Resolves audit R10.
- `DONE(core/host/policy)`: terminal-to-host response channel exists for DA, DSR/CPR, safe window reports, palette queries, `DECRQSS`, and allowlisted `XTGETTCAP`; host policy can deny terminal responses before they enqueue bytes.
- `DONE(core/host/policy)`: light/dark color-scheme query (`CSI ?996n`) returns `CSI ?997;1n` (dark) or `CSI ?997;2n` (light) from the active host theme palette under terminal-response policy. Standalone and IDE theme updates use the existing synchronized palette publication path; application color overrides do not affect the reply. Denied requests stay silent because this protocol has no failure response. The implemented slice is the one-shot query, without mode 2031 unsolicited notifications.

---

## Integration Gaps

- `DONE(host/core)`: partial SGR updates mutate only supplied fields in core�s current pen through `updatePenColors`; the adapter has no SGR mirror. DEC/SCO/1048/1049 restores, defaults, RGB/indexed colors, individual styles, protection and hyperlink retention are covered. Resolves audit R07; historical evidence is unchanged.
- `DONE(host/policy)`: host-adapter allow/deny policy surface for title updates, OSC 8 hyperlinks, OSC 7 current-working-directory reports, desktop notifications, window manipulation requests, palette controls, terminal response channels, and OSC 52 clipboard request auditing.
- `DONE(host/session/ui)`: DECCOLM switches logical columns independently of window-resize permission, with ordered connector synchronization and optional host window resizing. Standalone fits requested window bounds within the monitor work area, moving only as needed. Fixed-pane behavior is described under [Column Toggles](terminal-feature-map.md#1-terminal-protocols--control-sequences).
- `DONE(core/host/session/pty/workspace)`: targeted metadata callbacks publish effective palette changes and OSC 8 registry registration, eviction, and clearing through the existing host/PTY/workspace boundaries. Existing notification callbacks preserve individual requests, including identical repetitions. Callbacks are synchronous, policy-filtered for application controls, and independent of render publication; they do not replay initial state. Workspace forwarding applies to attached tabs. Active OSC 8 writing-attribute observation and application-facing color-scheme notifications are outside this slice. See [Targeted Host Metadata Events](terminal-feature-map.md#targeted-host-metadata-events) for delivery and reset semantics.
- `TODO(host)`: host callbacks for mouse-report policy when product surfaces need UI or embedding feedback.

---

## Input Module Gaps

- `DONE(input/policy)`: bracketed-paste framing protection is automatic for both content policies. ESC/ETX become visible control pictures and Unicode CSI becomes printable text after optional C0 stripping. Embedded/reconstructed delimiters cannot terminate the protected payload; valid UTF-8 and bracketed line endings are preserved, and unpaired surrogates become U+FFFD. Newline canonicalization belongs solely to the independent host policy. Transformed pastes use bounded reusable output storage with failure cleanup. Exact-byte tests cover policies, Unicode, output boundaries, recovery, and host-mode-driven paste/completion replacement; JMH measures the production session path.
- `DONE(host/profile)`: standalone and IntelliJ now expose **Preserve text** and **Remove control characters**. Existing persisted keys remain stable; legacy `raw` and `normalize-line-endings` values migrate to `preserve`, preserving the shipped PTY newline behavior. Settings apply to new sessions and live Swing panes. Embedders use `pasteControlPolicy` and `TerminalSession.setPasteControlPolicy`; updates retain host-specific keyboard and newline choices. Unbracketed paste is not shell-quoted, and an unprotected bracketed-paste bypass is not exposed.
- `TODO(input)`: broader modified-key encoding:
  - xterm modifyOtherKeys subparameter mask support such as `CSI > 4 : 1 m`; this factors modifiers out of the source keysym and therefore remains deferred with rich layout-aware input metadata.
- `DONE(parser/core/host/input/session)`: xterm key-resource state and query path is complete for IDs 0, 1, 2, 3, 4, 6, and 7: independent packed modifier/format state, set/reset-one/reset-all, explicit disable, XTQMODKEYS/XTQFMTKEYS, allowlisted policy-gated replies, and matching normalized-key encoding. Exact-byte, malformed/overflow, byte-split, reset, policy, and real session tests cover the path; JMH covers extended encoding and query generation. The PC profile stores resource 0 without applying legacy/VT220 keyboard admission rules. Standard mapped keys use xterm identities; media keys remain Kitty-only and no additional host metadata is inferred. Layout-aware modifier masks remain deferred above; literal historical keyboard-profile parity remains outside the target. See the [resource contract](../ketraterm-input/docs/terminal-input-contract.md#xterm-key-resources).
- `DONE(input)`: text-only `TerminalKeyEvent.text(...)` emits complete committed UTF-8 for press/repeat in legacy and Kitty text modes; release is suppressed and the key-code marker never becomes NUL. Physical-key modifier transformations and modifyOtherKeys do not reinterpret committed text. Kitty report-all mode still requires associated-text reporting, otherwise the event is explicitly suppressed. Exact-byte, long-text, validation, scratch-reuse, mode-snapshot, and real session mode-negotiation regressions cover this public API path. Portable Swing still does not construct text-only events; rich-host capability admission is unchanged. See the [keyboard contract](../ketraterm-input/docs/terminal-input-contract.md#keyboard-contract).
- `TODO(input/policy)`: additional xterm-compatible key policies when a real ambiguity exists, such as Delete behavior and optional eight-bit Meta output.
- `DONE(protocol/core/host/input/ui)`: DECBKM mode 67, conventional Ctrl+2 through Ctrl+8 control bytes, xterm modified F3, legacy F13-F35 aliases, and lossless Shift/Ctrl fallback for base Enter/Escape/Backspace/keypad keys are implemented through allocation-free packed mode state and primitive lookup tables.
- `TODO(parser/core/input)`: xterm highlight mouse tracking (`?1001`) if full xterm mouse parity is required.
- `DONE(ui)`: alternate-screen wheel-to-arrow fallback with tracking off reuses the viewport's allocation-free accumulator, retaining precise fractional row movement until a whole key step is due. It resets partial input on route or session changes and bounds emitted keys per event. Active mouse tracking retains AWT's integer wheel-click reporting; [Java accumulates high-resolution partial clicks](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/java/awt/event/MouseWheelEvent.html) before reporting a whole click. Primary-screen viewport scrolling keeps its existing row accumulation; SGR-Pixels remains a coordinate encoding rather than a wheel-delta protocol.

### Deferred Kitty Keyboard Protocol Scope
- `DONE(protocol/core/pty)`: terminal capability identity contract centralizes `$TERM`, `COLORTERM`, DA/DA2, XTGETTCAP terminal-name/color claims, and the implemented Kitty keyboard flag mask.
- `DONE(protocol/core/host/session/input)`: Kitty capability handling distinguishes the full encoder mask from the conservative portable-host mask. The parser-to-core adapter applies a per-session host mask to replace/set/clear/push operations, so query responses reflect only capabilities the active host has explicitly declared.
- `DONE(input)`: `TerminalKeyEvent` can carry an optional unshifted printable-key scalar, and Kitty CSI-u encoding uses it instead of produced text when present.
- `DONE(parser/core/host/policy)`: parameterless `CSI ? u` reports only active Kitty keyboard progressive flags admitted by the session's host capability mask through the existing terminal-response policy.
- `TODO(host/profile)`: deferred native rich-input adapters for layout-aware physical-key identity, IME text, and complete lifecycle metadata. This requires host-specific native integration; no portable Swing or IntelliJ-hosted Swing session may advertise flags `2`, `4`, or `16` until that work is implemented and verified.
- `DONE(input)`: normalized keyboard events carry an explicit press/repeat/release lifecycle phase without host-toolkit dependencies or encoder hot-path allocation.
- `DONE(input)`: the Kitty encoder formats the lifecycle phase in the `modifier:event-type` subfield; without flag `2`, releases are suppressed while repeats retain normal press encoding.
- `DONE(ui)`: Swing preserves press/repeat/release for AWT-visible non-text physical keys through a fixed preallocated pressed-key table while focus and matching releases remain available; unmatched releases are not invented or emitted. Pressed-key state is not cleared on focus loss, so a missed release can make the next press appear as a repeat. Portable Swing does not advertise Kitty event-type flag `2`.
- `DONE(protocol/input/ui)`: the complete Kitty functional-key table is represented by normalized input vocabulary and allocation-free PUA lookup tables; Swing maps the subset exposed by AWT, while richer hosts can supply the remaining keys.
- `DONE(input)`: normalized modifiers preserve Kitty's independent Super, Hyper, Meta, Caps Lock, and Num Lock bits while legacy CSI encodings retain their compatible four-modifier representation. Modifier-only key reports are gated by flag `8`.
- `DONE(input)`: normalized printable events carry validated Kitty shifted and base-layout alternate key scalars; the encoder formats both fields, including the required empty shifted field when only a base-layout scalar exists.
- `DONE(input)`: host-owned associated text is scalar-validated and encoded as Kitty colon-separated text codepoints without encoder-side allocation.

---

## Session, Transport, Rendering, and Host Integration Gaps

- `DONE(ui)`: Swing focus gain/loss reaches the current session's mode-gated encoder. Deterministic tests cover default/enabled/disabled and live-changing modes, temporary transitions, outbound ordering, closed sessions, unbinding, disposal and rebinding. Resolves embedding audit [E01](reviews/intellij-embedding-audit-2026-09-27.md#e01--p2-swing-focus-reporting-is-disconnected); historical evidence is unchanged.
- `DONE(transport/session)`: normal PTY closure waits for stdout delivery through EOF; fatal read failure and all session termination paths dispose the transport once while preserving the first cause. Deterministic reader/watcher ordering, startup/write failure, recursive close, blocked-read cancellation, and native Windows ConPTY final-burst tests verify R03/R04. Local close cancels rather than drains output.
- `DONE(session)`: shutdown flushes parser EOF and publishes the final requested viewport before emitting Closed, including synchronized output. Tests cover pending UTF-8, early/late observers, active cache leases, in-flight publication, and cleanup failures. Resolves R05 without a separate UI read path.
- `DONE(host/profile)`: workspace shutdown attempts every tab, session, and notification, cancels its scope, and retains later cleanup failures as suppressed exceptions. Closed workspaces reject new tabs. Standalone and IntelliJ disposal continue through failing resources; workspace fault-injection and IntelliJ persistence-listener regressions verify R15.
- `DONE(ui)`: search and hyperlink logical text omit `WRAP_PADDING`, preserve real spaces and cell mappings, and include padding in hyperlink cache identity. Scalar/cluster, reflow, highlight, detector-span and invalidation regressions verify R13.
- `DONE(ui)`: component and font/settings-driven grid resizes clear physical selection and stop selection dragging; unchanged dimensions preserve selection. Bound Swing tests cover linear/block selection, narrowing/widening, height changes, eviction and font changes. Resolves R14 using the explicit clear-selection policy.
- `DONE(ui)`: R09 search uses one cancellable background worker and a bounded row-copy buffer instead of a growing full-history cell cache. Completed match buffers transfer to the EDT; query/session changes reject obsolete publication. Tests cover allocation growth, copying bounds, cancellation, concurrent output, eviction, old-row edits, wrapped text, active match location and host status updates. Counts describe completed passes, not an atomic history snapshot; whole-frame zero allocation is not claimed.
- `DONE(ui)`: reuse one immutable selection projection while its clipped coordinates and block mode remain unchanged. Tests cover direction, clipping, clear, cache replacement, wide/bidi cells and retained snapshots. R16 is resolved for unchanged projections; changed bounds may allocate.
- `DONE(session)`: ordinary input, startup commands, and core-response batches use bounded atomic admission and one background writer outside parser/input locks. Paste and text replacement use bounded source retention and background encoding with native-write backpressure, preserving admission-time modes/policy and whole-operation ordering. Overflow and transport errors fail the session; local close can unblock native writes. Acceptance, byte/source-work/operation budgets, and shutdown semantics are defined in the [feature map](terminal-feature-map.md#7-embedding--swing-ui).

- `TODO(transport)`: the shipped workspace factory creates local PTY sessions only. A generic `TerminalConnector` contract exists, but there is no SSH connector, remote-session lifecycle, or product SSH session surface. Launching `ssh` inside a local shell is not equivalent. An SSH implementation also needs explicit session permissions and transport-appropriate paste defaults. [IntelliJ IDEA still routes SSH sessions through its Classic engine](https://www.jetbrains.com/help/idea/terminal-emulator.html), so this is a replacement-readiness gap.
- `TODO(host/profile)`: IntelliJ KetraTerm tabs are tool-window content only; there is no editor-tab file-editor integration. The current [IntelliJ terminal can move to an editor tab](https://www.jetbrains.com/help/idea/terminal-emulator.html), making placement a concrete IDE parity gap.
- `TODO(host/profile)`: execution-environment-aware launches remain partial. WSL shell profiles exist, but WSL-specific directory, JDK, and startup-command mapping is excluded from the current local-host slice; Dev Container launch context is absent. IntelliJ's [predefined-session list groups shells by host, WSL, or Dev Container environment](https://www.jetbrains.com/help/idea/terminal-emulator.html). Preserve the environment boundary instead of treating a local WSL launcher as full remote integration.
- `DONE(session)`: startup commands execute once after supported shell readiness, with standalone configuration and project-local IntelliJ settings. The [feature map](terminal-feature-map.md#7-embedding--swing-ui) defines supported shells, input cancellation, and launch restrictions; WSL launchers and multiline command payloads are outside this slice.
- `DONE(session)`: automatic foreground-process tab-title fallbacks for local PTYs, with lifecycle-bound shared polling and live persisted settings in both products. Custom and application titles retain priority, and unavailable detection preserves directory/profile fallbacks. Unix uses the foreground process-group leader; Windows uses a newest-descendant heuristic that may select a background child. Applications inside SSH/WSL are outside this local-process detection slice; exact precedence, bounds, and lifecycle behavior are documented in the feature map.
- `DONE(host/profile)`: IntelliJ **Open in KetraTerm** opens a new tab from local Project View, editor, and editor-tab file contexts. The [feature map](terminal-feature-map.md#7-embedding--swing-ui) defines directory selection and launch behavior.
- `DONE(host/profile)`: IntelliJ project workspace persistence restores open tabs, custom names, local working directories, profile choice, order, and selection. The [feature map](terminal-feature-map.md#7-embedding--swing-ui) defines lazy startup, directory fallback, and the local-host boundary.
- `DONE(host/profile)`: IntelliJ project JDK environment injection and its default-on setting follow the reworked IntelliJ terminal's launch precedence. The [feature map](terminal-feature-map.md#7-embedding--swing-ui) defines the supported local SDK and shell boundaries.
- `TODO(host/profile)`: Restore suggestion settings when they are ready for product exposure. Both settings forms retain commented `SUGGESTION_SETTINGS` blocks for the master switch, automatic popups, Enter acceptance, and persistence. Uncomment each form's fields, layout, change tracking where applicable, and Apply/Reset bindings together; uncomment the matching IntelliJ message key and update the hidden-controls tests. Keep the master default off. Learning-reset buttons also require reconnecting the removed host callbacks before restoring their confirmation UI (the previous wiring is in the parent of commit `4ef4d3f4`).
- `DONE(host/session/input/ui/policy)`: [OSC 52 clipboard reads](terminal-feature-map.md#4-query-response-channels) support clipboard and available native primary selection in both products, with consent, deadlines, cancellation, and terminal/client isolation.
- `DONE(ui)`: unfocused block cursors become thin hollow outlines; bars and underlines retain their shape without blinking. Shared wide-cell and bidi geometry, application visibility, viewport clipping, and SGR text blinking are preserved. Deterministic pixel, fractional-DPI with varying pane origins and stroke widths, focus-transition, and repaint tests cover presentation without changing protocol state. See the [cursor presentation contract](terminal-feature-map.md#7-embedding--swing-ui).
- `DONE(ui/host/profile)`: hyperlink retention, discovery, grouping, lifecycle recovery and native interaction are complete, with final verification confirmed by the user. Supported behavior and configuration are defined in the [feature map](terminal-feature-map.md#7-embedding--swing-ui).
- `DONE(parser/policy)`: current OSC/DCS families have explicit collection ceilings and overflow/recovery semantics in the [payload resource contract](terminal-feature-map.md#oscdcs-payload-resource-contract), including bounded unknown-family discard, hyperlink context clearing on completed overflow, and parser-to-host boundary tests. Ordinary commands retain the 4 KiB ceiling.
- `TODO(parser/policy)`: graphics require separate bounded storage/transfer designs, APC where applicable, decoded/decompressed image bounds, and retained-image budgets; current clipboard/family ceilings alone do not enable graphics.
- `DONE(host/policy)`: title/icon updates are host-gated through `HostPolicy.titlePolicy`, which models session-wide allow/deny decisions and configurable oversized-title handling (`clamp` by default for standalone compatibility, or `reject` for stricter profiles).
- `DONE(policy)`: terminal capability identity policy is explicit in `TerminalCapabilityIdentity` and consumed by PTY launch defaults plus core terminal-to-host query responses.

### Embedding Contracts

The [IntelliJ embedding audit](reviews/intellij-embedding-audit-2026-09-27.md) distinguishes supported host customization from these composition decisions. The optional OSC producer is separated from the neutral session contract; its dependency is selected by the host.

**Scope decision (E04):** Reusing KetraTerm's Swing renderer without `TerminalSession` is not required and is outside the planned scope. Keep the renderer-to-session dependency; no independent painter API or renderer extraction is planned. Hosts using their own renderer can consume the existing core/render contracts with or without the session. Host-owned shell integration and suggestions remain separate integration concerns.

- `DONE(host/profile)`: workspace shell hooks preserve caller-owned configuration, metadata and executable lookup; standalone wiring owns the native companion CLI and active configuration path. Shell-family neutrality, standalone launch policy, helper installation and executable CLI regressions pass. See [shell launch ownership](terminal-feature-map.md#7-embedding--swing-ui) for scope and audit E05 for historical evidence.
- `DONE(session/ui)`: E02 supports an entirely host-owned shell model through `TerminalShellIntegrationFactory.host`: prompt/command boundaries, history metadata, directory, editing, and readiness share one selected producer. Session, PTY, and Swing have no dependency on the optional `ketraterm-shell-integration` OSC implementation; workspace selects it explicitly. Host models never fall back to OSC. Synchronous directory/completion callbacks preserve final metadata before immediate transport closure; views use conflated revisions for repaint invalidation. Startup uses selected readiness with session ordering and cancellation; host model/source lifetime remains external. `TerminalHostShellIntegrationTest`, `TerminalShellCommandLineSourceTest`, optional-module OSC/startup/observation suites, `PtySessionTest`, `TerminalWorkspaceShellIntegrationTest`, and `SwingTerminalCommandNavigationTest` cover isolation, metadata projection, source lifetime, startup ordering, product notifications, and view observation. See the [integration contract](terminal-feature-map.md#7-embedding--swing-ui).
- `DONE(ui)`: E03 supplies explicit host-context requests through `SwingShellSuggestionTrigger.EXPLICIT`, independent of automatic popups but subject to the master switch. Session-backed requests validate current context and session lifetime; directly supplied context remains host-invalidated. `SwingTerminalSuggestionContextTest` verifies trigger eligibility, stale results and acceptance, dismissal, provider-failure cleanup, and rebinding. Dismissal cancels provider work, and ineligible automatic requests preserve explicit requests.
- `DONE(ui/session)`: E04 KDoc and module docs define binding's session requirement, settings/resize authority, EDT dispatch, and host-owned lifetime, backed by `SwingTerminalThreadingTest`. Each session publishes one viewport; independently scrolling views of one process remain unsupported. Independent Swing renderer reuse remains outside scope.

## API and Product Verification

- `DONE(policy)`: R08 public dependency exports are corrected in parser, host and completion; isolated API-variant and published Kotlin/Java consumer coverage also exercises Swing host services, suspending hyperlink detection and EDT binding/disposal. Each published consumer declares one library dependency and runs in Gradle-metadata and POM-only modes. The test CI matrix runs `:ketraterm-testkit:publishedConsumerTest`; release signing and remote delivery are outside this check.
- `DONE(policy)`: G01 reviews public pipeline and embedding contracts, ownership, coordinates, configuration/default-call shapes and encoded/inline behavior. One supported-module set selects 15 Maven publications, strict explicit API mode, unfiltered ABI snapshots and root public Dokka. Workspace and completion persistence are product-only modules bundled in both products; their external support is intentionally removed before stable release. Eleven retained Kotlin/Java clients define 44 upgrades across both metadata modes and stdlib 2.4.0/2.4.20, with two deliberate linkage-failure controls. Source consumers check both Kotlin compilers. Concrete external implementations, optional host combinations and concurrent old/new render leases remain in the suite. The host, Swing and PTY clients were deliberately replaced for incompatible development construction changes; D04/D05 additionally replaces Swing/render-cache readers for narrowed authority and lease ABI. Historical reviews retain their earlier 17-publication/13-client counts. The [compatibility contract](library-compatibility.md), [feature map](terminal-feature-map.md#7-embedding--swing-ui) and [consumer fixtures](../ketraterm-testkit/src/consumerTest/README.md) define the selected boundary and representative coverage. This is a development baseline, not a guarantee for earlier 0.x releases.

### Final API Design

The [2026-10-02 API design review](reviews/terminal-api-design-review-2026-10-02.md)
and [2026-10-03 final review](reviews/terminal-api-final-review-2026-10-03.md)
findings A01–A15 are resolved by the construction, presentation, extension and
lifecycle contracts, with verification recorded under G01. Native coordination
now observes session termination; callback failures and cancellation propagate.
All six original final-review regressions pass, with closure-boundary, detachment
and recovery coverage. All five teardown regressions from the follow-up at
`5facdcbd` also pass: owned cleanup completes before propagating callback failures
or cancellation, preserving the first failure and suppressing later failures.
Additional coverage verifies reused exceptions, view release, peer reattachment
and rebinding after failed unbinding. Popup hiding completes despite a view update
failure, and viewport metrics and eligibility are committed before host notification.
Reentrant state changes or disposal supersede obsolete eligibility notifications.
Disposal cancels the component scope; peer removal retains it. Sessions and native
target resources remain host-owned. The final review records regression evidence
and the completed follow-up to `104db318`.
Both reviews retain their historical evidence. E04's independent renderer proposal
remains outside scope.

### Maintainability Review

The [full review](reviews/terminal-maintainability-review-2026-10-03.md) records
source evidence, regression identities, validation limits and correction groups.
The original M01–M13 baseline recorded 43 correct-behavior regressions: 33 failures
and ten passing serialization controls. Source-only findings and API decisions below
do not claim executed fault injection or measured performance. Existing A01–A15
closures remain historical evidence for their tested paths.

- **M01 — `DONE(core)`**: Kitty pops have bounded work and reset flags on stack exhaustion. Regressions pass.
- **M02 — `DONE(session)`**: unexpected writer cancellation closes the session, retaining the cause without retrying output. Regressions pass.
- **M03 — `DONE(transport)`**: PTY byte-listener exceptions report the original failure and dispose the process and streams once. Regressions pass.
- **M04 — `DONE(completion)`**: unexpected source errors are reported and terminate collection with sibling cancellation; independently cancelled sources complete accounting. Regressions pass.
- **M05 — `DONE(ui)`**: completion replacement and close detach observation before popup callbacks; replacement keeps provider and feedback ownership coherent.
- **M06 — `DONE(ui)`**: popup state is committed before view callbacks, and reentrant transitions supersede unfinished shows.
- **M07 — `DONE(host/profile)`**: optional workspace observers are supervised independently of shell and session-close observation.
- **M08 — `DONE(host/profile)`**: reentrant tab selection or closure supersedes pending selection notifications.
- **M09 — `DONE(core/render)`**: core rejects nested frame reads before changing the enclosing lease; reentry and callback-failure contracts are documented and tested.
- **M10 — `DONE(host)`**: adapter title getters and stacks read authoritative core titles; host retains stacks and notifications.
- **M11 — `DONE(input)`**: widened cell/pixel conversion preserves accepted ranges; six exact-byte regressions verify decimal output and bounded clamping/suppression. Pixel contracts use zero-based events.
- **M12 — `DONE(host/profile)`**: one TOML string encoder preserves accepted configuration values, including line breaks, controls, quotes, and backslashes.
- **M13 — `DONE(host)`**: accepted OSC 8 opens reconcile lowered limits under the serialized registry owner. Byte-stream tests verify `4 → 1`, explicit-key reuse, URI retirement and ordered removals; callback-failure tests verify coherent recovery.
- **M14 — `DONE(host/profile)`**: standalone Ctrl+Tab uses tab-bar order; PTY-backed product tests verify both directions, wraparound, deletion, and empty/single-tab behavior.
- **M15 — `DONE(host/profile)`**: IntelliJ pane creation rolls back acquired UI resources and service listeners; IDE fixtures verify failure, cancellation, and suppressed cleanup errors.
- **M16 — `DONE(render)`**: external frames share the immutable fallback palette. JMH covers unchanged/changing 80×24 frames against an allocating control; the 2,208 B/update palette allocation is removed.
- **M17 — `DONE(host/profile)`**: standalone export uses window-owned I/O work. Gated tests verify EDT availability, original failure delivery, cancellation and suppression of callbacks after disposal.
- **M18 — `DONE(transport/policy)`**: connector startup is explicitly start-once; repeats and startup after close reject without replacing the listener. PTY, testkit and external-consumer coverage follow that contract.
- **M19 — `DONE(session/policy)`**: closure freezes terminal state after admitted work/EOF; late setters/input are ignored and resize rejects. Local, remote and pre-start closure tests verify retained reads and unchanged collaborators.
- **M20 — `DONE(host/profile/completion)`**: workspace examples compile; completion documentation reflects Flow debounce and JList presentation.
- **M21 — `DONE(parser)`**: removed unused decoder fields and their implementation-only tests; real byte-stream reset, malformed-input, chunking and EOF coverage remains.

### API Adoption and Evolution

The [2026-10-03 review](reviews/terminal-api-evolution-review-2026-10-03.md)
examines the corrected API at `f632fbe3`, with follow-up verification at
`57623d28`. D identifiers track design/adoption work and reproduced defects;
they do not reopen resolved A/M findings.
The development compatibility baseline passes but is not a stable API freeze.
Group configuration work and publication work to avoid repeated migrations;
the review records the execution order and acceptance criteria.

- **D01 — `DONE(session)`**: session readiness and input admission follow successful connector startup.
- **D02 — `DONE(ui/host/profile)`**: core/host own launch policies; products own preferences and persistence. Ineffective Swing settings were removed.
- **D03 — `DONE(ui/transport/host/profile/policy)`**: configuration uses immutable snapshots and named Kotlin/Java construction and updates. See [configuration contracts](library-configuration.md).
- **D04 — `DONE(session/render)`**: session consumers receive read-only shell state and scoped frame access; producers retain mutation authority. See [ownership](render-reader-ownership.md).
- **D05 — `DONE(render/policy)`**: render leases hide publisher storage and retain allocation behavior. See [verification](render-reader-ownership.md#allocation-measurement).
- **D06 — `TODO(session/ui/host/profile)`**: the [IDEA Community experiment](reviews/intellij-integration-experiment-2026-10-04.md) reports a runnable host using published artifacts, IntelliJ-owned shell metadata and KetraTerm Swing rendering. Adoption remains partial: retain and review the adapter revision, resolved artifact identities, tests and final validation results; verify byte splits, anchors, cancellation and disposal without workspace or optional OSC dependencies. Reconcile host/native history retention after width changes and wide characters. Track remaining host work here: transfer/reconnection, first-token and inline completion, custom console filters and regex search; determine which require library changes before adding APIs. Library follow-ups are I01–I09 below. Retain minimal default and headless examples; this internal IntelliJ integration does not establish external-plugin API availability.
- **D07 — `TODO(ui/host/profile/policy)`**: labels now belong to product composition and search colors are host-configurable. The D06 experiment reports native completion/search popups but incomplete completion paths. Verify popup capacity, trigger, anchoring and lifecycle needs against that adapter before adding overrides; input acceptance is tracked by I01/I02 and host interaction settings by I07.
- **D08 — `DONE(core/policy)`**: mode contracts define finite published subsets with stable numeric meanings.
- **D09 — `DONE(ui)`**: published-consumer checks compile and exercise the actual Swing README example, including EDT and lifetime ownership.
- **D10 — `DONE(host)`**: line-feed and Kitty flag inspection use primitive mode reads.
- **D11 — `DONE(host/profile)`**: workspace snapshots reject undefined mode-capability bits through `create`, `copy` and `build`; regressions cover invalid bits, valid subsets and snapshot isolation.

### IntelliJ Integration Experiment Follow-ups

The [2026-10-04 report](reviews/intellij-integration-experiment-2026-10-04.md)
is user-supplied evidence from an IDEA Community adapter using the published
snapshot. Its implementation, tests and final measurement report have not been
independently reviewed in this repository. The report's proposed API shapes are
not accepted designs. Reproduce each issue before implementation and close it
only with regression coverage and verification in the consuming adapter.

I01–I04 are complete for the library. External adapter verification remains separate. Continue with I05–I07, I08 and I09. Each identifier
is an independently verifiable outcome. Keep G02/G03 open; this experiment does
not freeze the public API. ASCII parsing optimization is explicitly outside this
follow-up scope at the user's request.

I01/I02 library validation: root `test`, `checkKotlinAbi`, the published Java/Kotlin
consumer matrix and independent IntelliJ plugin `test` passed. Formatting and
Graphify update completed; Graphify reports partial Kotlin extraction warnings.

- **I01 — P1 — `DONE(session/input)` — Ordered host input and admission feedback**: library implementation complete: `submitBytes` copies validated exact-byte slices through the bounded session writer; `submitInput` returns admission for single semantic events or bounded, non-interleaving compound lists with captured modes/policy. Existing encoder signatures remain available. Regressions cover the literal PowerShell trigger, mutation after return, invalid/empty/oversized ranges, shared budget exhaustion, concurrent producers, chunked compounds, startup, shutdown, resize independence and transport failure; Swing host completion cancels pending requests on writer failure. Admission is not completion; I02 adds conditional validation on the same queue. The session baseline passed; the missing-API reproduction failed with `NoSuchMethodException` before implementation. See the [implemented contract](../ketraterm-session/docs/session-concurrency-locks.md#admission-and-ordering); historical report evidence is unchanged.

- **I02 — P1 — `DONE(session/input/ui)` — Conditional command-edit admission**: library implementation complete: request-time edit contexts atomically validate the selected producer revision and intervening session input/output/resize through compound queue reservation. The synchronized `TerminalShellCommandLineState` detects equal assignments and ABA changes; StateFlow-only sources remain readable but cannot admit conditional edits. OSC and default Swing acceptance use this contract; unsupported grapheme spans, stale/cancelled requests and closure send no prefix or accepted feedback. Deterministic regressions cover concurrent metadata/input/output, queued input, stale context, cancellation, closure, source compatibility and feedback. The pre-fix Swing reproduction sent five Backspaces and replacement text after intervening `x`; it now sends only `x`. An idle writer is not shell acknowledgement. See the [implemented contract](../ketraterm-session/docs/session-concurrency-locks.md#conditional-command-edits); historical evidence is unchanged.

- **I03 — P1 — `DONE(session/ui)` — Closed-session presentation**: library implementation complete. Swing projects the retained final grid without terminal reflow or transport work. Width clips; height changes expose a bottom-anchored window with local scrolling. Font changes preserve selection. `tryResizeViewport` explicitly declines closure; strict resize APIs still reject it. Baseline session/Swing tests passed. New binding-after-exit and cleanup-race regressions first failed with “session is closed”; they now pass. Coverage includes core-reflow closure races, alternate/restored buffers, mouse selection, command navigation, retained text, font changes, rebinding, disposal, coordinate bounds and reader recovery. Command navigation first chose offset 1 instead of 2. Root tests, ABI checks, published consumers, plugin tests and all 14 native PTY tests passed. See the [presentation contract](../ketraterm-session/docs/session-concurrency-locks.md#closed-session-presentation). The external IDEA adapter still needs verification; historical evidence is unchanged.

- **I04 — P1 — `DONE(parser/session)` — Ordered host-specific OSC extension**: library implementation complete. `TerminalCustomOscHandler` receives unsupported numeric OSC commands synchronously through the normal session parser factory. Built-in commands retain their policy gates. The host owns payload copying, decoding, protocol semantics, and handler cleanup. Reentry is rejected; callback failures retain existing connector failure routing. Hosts can configure the full envelope limit; the default is 4 KiB. Command headers retain a separate 4 KiB ceiling. Temporary growth is released on completion, overflow, cancellation, EOF, reset, or callback failure. Baseline parser/host/session suites passed. The initial ordering reproduction omitted the custom event; the larger-payload reproduction discarded a 6,005-byte envelope. Both now pass. Regressions cover chunking, BEL/ST, malformed headers, limits, storage cleanup, metadata anchors, live policy, and closure races. Root tests, ABI checks, published Java/Kotlin consumers, and independent plugin tests pass. Formatting and Graphify updates completed. External IDEA adapter verification remains open. See the [callback contract](../ketraterm-session/docs/session-concurrency-locks.md#ordered-custom-osc-handling); historical evidence is unchanged.

- **I05 — P2 — `TODO(ui)` — Programmatic selection**: the host needs range assignment, clear and change observation beyond reading current selection, Select All and command selection. Define stable line identity, coordinate/range conventions and EDT/lifecycle behavior before adding APIs. Verify wide/clustered cells, block/linear selection, eviction, reflow, buffer changes and observer disposal; preserve the existing explicit selection-clearing policy on grid resize.

- **I06 — P2 — `TODO(core/session/ui)` — Local buffer clear**: host clear actions lack a session-owned local operation. Specify visible buffer, history, cursor, alternate-screen and shell-anchor effects separately from sending Ctrl+L to the application. Serialize the operation with incoming output and frame publication; verify selection/search invalidation, mode preservation, closure and subsequent output. Reuse existing core capabilities where appropriate rather than emulating a local action by injecting terminal escape sequences.

- **I07 — P2 — `TODO(ui/host/profile/policy)` — Host interaction and spacing settings**: the report identifies missing host choices for mouse reporting, copy-on-selection, middle-button paste and column spacing. Inspect existing input policy and action hooks first; keep host actions in host composition where possible and add only controls that the reusable view must own. Coordinate mouse-policy feedback with the existing Integration Gaps entry. Verify defaults, live updates, application mouse modes, selection modifiers, clipboard policy, pixel/cell mapping, DPI and resize. Do not automatically turn every host preference into a new `SwingSettings` field.

- **I08 — P2 — `TODO(render/ui)` — Observable frame application and painting**: `renderGeneration` identifies publication, not the frame applied or painted by Swing. Determine the host's required endpoint and expose generation-aware observation with precise EDT, coalescing, disposal and callback semantics. Distinguish cache application, completed software paint and actual display presentation; do not promise one callback for every published frame. Verify skipped/coalesced generations, resize, rebinding and closure, and measure steady-state overhead. Use the endpoint to separate native renderer progress from IntelliJ compatibility-document projection.

- **I09 — P2 — `TODO(core)` — History startup allocation**: the report attributes 21,048,000 bytes of initial row arrays at 80 columns to eager buffer allocation. Reproduce with the exact history configuration and isolate startup from recurring workloads before choosing lazy row creation/reuse. Preserve bounded retention, eviction, resize/reflow, wide/clustered cells, clearing and alternate-buffer behavior. Measure first use, warmed startup, history growth, ring reuse and retained heap; report compatibility-document and benchmark overhead separately. The reported capacity is not evidence of recurring allocation pressure; G03 remains the owner of broader performance budgets.

### Release Verification

- `DONE(host/profile)`: Maven library publication has dependency-only headless and Swing entry points, one constraints-only BOM, a verified build-local repository, and formatting/ABI/test/consumer gates on Central upload. Snapshot destination and signing follow the publisher configuration. Authenticated upload and Central namespace snapshot enablement require account configuration; broader G02 product delivery remains open. See [library dependency setup](../README.md#using-the-libraries).

- `TODO(host/profile)`: gate binary and plugin delivery on verification of the exact release revision, including native PTY coverage, package checks, Plugin Verifier for supported IDEs, and installed-product smoke tests. Current binary publishing is independent of the test workflow; ordinary CI omits native PTY opt-in and plugin package checks. Include the IDE 2026.3 bundled-JNA module visibility change identified in the embedding review; the plugin currently relies on IDE-native dependencies while testing only 2026.2. See audit G02.
- `TODO(policy)`: define measured allocation/latency budgets for warm terminal paths, changing content, history growth, and platform painting; keep examples executable and performance claims scoped to evidence. The audit's short Windows paint smoke is not a release baseline. See audit G03.
