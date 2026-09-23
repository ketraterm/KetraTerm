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
- `TODO(host/profile)`: product host integration, profile, or settings behavior is missing.
- `TODO(policy)`: feature needs an explicit security or compatibility policy before implementation.

Combined labels name every owner needed to close one gap; `host/profile` means product-host integration rather than the parser-to-core adapter. A policy or test limitation alone does not imply missing parser behavior.

---

## Terminal Targets & Priority

These tiers rank terminal behavior across standalone and embedded hosts. Reproducible errors in output, grid, input, or security policy take precedence over new protocols and product conveniences. Product integration gaps remain documented below without determining these emulation tiers.

### Tier 1: Current correctness and security defects

- Fix [CSI parameter overflow](#csi-protocols) before excess fields can change the meaning of a command.
- Address [bracketed-paste end-marker injection](#input-module-gaps) in the default raw paste path.
- Fix [fractional wheel input](#input-module-gaps) lost by mouse-aware TUIs and alternate-screen fallback.
- Resolve the [nested-SSH origin-policy limitation](#session-transport-rendering-and-host-integration-gaps) for clipboard and title controls.

### Tier 2: Regression coverage and modern compatibility

- Close the [host byte-stream coverage gap for margin-aware SU/SD](#csi-protocols), which protects a supported grid operation against integration regressions.
- Resolve the [long-grapheme boundary](#text-and-unicode) and [legacy text-only key encoding](#input-module-gaps) before richer hosts rely on them.
- Add [DEC mode status reports](#csi-protocols) with truthful unsupported-mode responses and terminal-response policy.
- Complete the [xterm key-resource state and query path](#input-module-gaps) and [host metadata for richer Kitty keyboard flags](#deferred-kitty-keyboard-protocol-scope).

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
- `DONE(parser/host)`: parameterless ANSI/SCO `CSI s` / `CSI u` compatibility, including mode-aware DECSLRM disambiguation and shared DEC/SCO cursor and charset save/restore. The [feature map](terminal-feature-map.md#1-terminal-protocols--control-sequences) defines the supported forms; byte-stream tests cover mixed forms, chunk boundaries, margin changes, resets, screen-local cursor slots, resize, and malformed input.
- `TODO(parser)`: after the default 16-field CSI parameter capacity is reached, another separator fails to open a field but resets `currentParamStarted`; following digits overwrite the last retained parameter. A long SGR or mode request can therefore act on a different value than its first 16 fields specify. Define an explicit reject-or-ignore overflow policy and cover split input, subparameters, dispatch, and recovery with byte-stream tests.
- `TODO(parser/core/host/policy)`: ANSI and DEC private mode status requests/reports (`DECRQM` / `DECRPM`, `CSI Ps $ p` / `CSI ? Ps $ p` and corresponding `$ y` replies) are absent. Define an explicit supported-mode allowlist and the protocol's unsupported-mode response before enabling them; the existing terminal-response gate and outbound channel can carry the replies.
- `DONE(parser/core/host)`: rectangular erase (`DECERA`), selective erase (`DECSERA`), fill (`DECFRA`), copy (`DECCRA`), and checksum response (`DECRQCRA`) preserve active margin/origin coordinates; mutation operations preserve wide/cluster span integrity and `DECCRA` uses overlap-safe snapshot semantics. Copy/checksum intentionally support only the active single page (`0` omitted or `1`); checksum responses are terminal-response-policy gated and use the VT420 default 16-bit algorithm.
- `TODO(parser/core/policy)`: xterm `XTCHECKSUM` extensions (`CSI Ps # y`) lack dispatch, checksum modes, and a compatibility policy. The implemented DECRQCRA path uses only the base VT420 behavior: erased/spacer cells omitted, base glyph values masked to eight bits, and supported legacy video attributes included; no color, combining-sequence, or alternate xterm extension semantics are claimed.
- `DONE(parser/core/host)`: DECSACE, DECCARA, and DECRARA implement VT420's stream-versus-exact-rectangle extent, ordered visual SGR subset, blank materialization policy, and atomic wide/cluster attribute updates without changing glyph payloads, protection, hyperlinks, or the current pen.
- `DONE(parser/core/host)`: DECIC and DECDC insert/delete columns across the active vertical scroll region, honor horizontal margins, preserve cursor position and active-buffer isolation, and repair wide/cluster span boundaries before each row shift.
- Verification gap: core property tests cover SU/SD with active left/right margins, but host byte-stream tests do not combine `CSI S` or `CSI T` with `DECLRMM`/`DECSLRM`. SU/SD are already routed; this is missing integration coverage, not a parser feature gap.

### ESC Protocols
- `TODO(parser)`: broader ISO 2022 national replacement sets:
  - UK, Dutch, Finnish, French, German, Italian, Norwegian/Danish, Spanish, Swedish, Swiss, Portuguese. US ASCII designation is already implemented.

### OSC Protocols
- `DONE(parser/host/session/pty/workspace/ui/policy)`: OSC 52 clipboard write requests are bounded, parsed, denied by default, size-checked, origin-aware, surfaced as content-free host audit events, forwarded as decoded text to product-host clipboard callbacks only when policy allows the write, and forwarded to compact standalone/IntelliJ confirmation dialogs when policy requires a prompt. Product prompts use terminal profile names, character counts for text writes, and clear-clipboard wording for empty writes instead of exposing raw OSC 52 selection tokens.
- `TODO(host/ui/policy)`: OSC 52 allowlist management UI and non-clipboard selection targets remain absent. Standalone and IntelliJ settings intentionally hide the `allowlist` option until a product-owned allowlist can persist entries and set `TerminalClipboardPolicy.allowlisted`. The [read/query gap](#session-transport-rendering-and-host-integration-gaps) is tracked with the product UI behavior that exposes it.
- `DONE(parser/host)`: explicit OSC encoding and recovery rules distinguish replacement-decoded display text from strictly validated structured metadata and clipboard text. Rejected links clear active context; other rejected metadata retains prior state. The [OSC encoding contract](terminal-feature-map.md#osc-encoding-and-recovery-contract) defines family-specific effects, audit precedence, overflow, abort/EOF recovery, and scalar-safe host limits. Legacy encoding detection and fallback are intentionally absent.

### Graphics Protocols
- `TODO(parser/core/render/ui)`: Sixel (`DCS ... q`) inline graphics need protocol dispatch, image storage, render contracts, and Swing painting. Transfer and retained-image policy is tracked [below](#session-transport-rendering-and-host-integration-gaps).
- `TODO(parser/core/render/ui)`: Kitty graphics use APC (`ESC _ G ... ESC \`), not DCS. The parser currently consumes APC without dispatch; image storage and rendering are also absent. Transfer and retained-image policy is tracked [below](#session-transport-rendering-and-host-integration-gaps).

### Text and Unicode
- `DONE(parser)`: malformed UTF-8 recovery is exercised immediately before and inside ESC, CSI,
  OSC (BEL/ST/CAN/SUB), DCS ST, and end-of-input, with every split boundary proving that malformed
  bytes do not print or complete stale structural commands.
- `TODO(parser)`: valid grapheme clusters longer than the parser's 16-codepoint staging buffer are flushed as multiple clusters even when Unicode grapheme rules say they continue. This can shift cursor position, wrapping, and copied text; core storage already supports longer clusters. Preserve one-cell ownership or define and test an explicit bounded fallback.

---

## Core Gaps

### Grid Operations
- `DONE(core)`: deterministic randomized left/right-margin properties cover ICH/DCH, selective erase, IL/DL, and partial-region scroll up/down. They model guard-column movement, preserve rows outside the scroll region, and verify protected wide spans plus wide/cluster storage invariants.
- `DONE(core/host)`: alternate-screen byte-stream coverage verifies exact primary history retention and zero alternate history across every `47`/`1047`/`1049` entry/exit pairing, repeated commands and re-entry, screen-local `1048` saves, and ordered private-mode lists. `ED2`, repeated `ED3`, and repeated `DECSTR` tests verify active-buffer clearing or text preservation, including combining/wide text and saved-cursor behavior. `DECCOLM` tests cover both 80/132-column directions and current-width requests, preserving primary history while alternate is active and clearing primary history when primary is active.
- `DONE(core)`: resize/capacity tests verify exact retained rows with zero, bounded, and spare history capacity, oldest-row eviction during narrowing and height shrink, and repeated `47`/`1047`/`1049` resize cycles without alternate text leaking or evicted rows returning. Cursor and scrollback anchors account for reflow eviction, including empty rows, wide characters, and grapheme clusters; evicted viewport anchors clamp to the oldest retained row.
- `DONE(core/session/ui)`: soft-wrap text reconstruction preserves written and erased spaces for linear selection, command capture, clipboard copy/paste, and retained-output export. Core distinguishes artificial wide-character wrap padding from meaningful empty cells and preserves that distinction through resize/reflow. Selected hard line breaks survive empty selection endpoints; block selections retain physical row breaks.

### Unicode Width
- `DONE(core/host/ui)`: invalid/unassigned codepoint width policy is explicit in the [core contract](../ketraterm-core/docs/terminal-core-contract.md#unicode-scalar-and-width-policy). Typed scalar/cluster writes reject non-scalars atomically; string writes repair unpaired surrogates. Pinned Unicode tables preserve reserved wide ranges and ambiguous-width behavior, with byte-split integration coverage for replacement, wrapping, and cursor alignment, plus real-buffer selection extraction through resize/reflow.

### Query and Response Channel
- `DONE(core/host/policy)`: terminal-to-host response channel exists for DA, DSR/CPR, safe window reports, palette queries, `DECRQSS`, and allowlisted `XTGETTCAP`; host policy can deny terminal responses before they enqueue bytes.
- `DONE(core/host/policy)`: light/dark color-scheme query (`CSI ?996n`) returns `CSI ?997;1n` (dark) or `CSI ?997;2n` (light) from the active host theme palette under terminal-response policy. Standalone and IDE theme updates use the existing synchronized palette publication path; application color overrides do not affect the reply. Denied requests stay silent because this protocol has no failure response. The implemented slice is the one-shot query, without mode 2031 unsolicited notifications.

---

## Integration Gaps

- `DONE(host/policy)`: host-adapter allow/deny policy surface for title updates, OSC 8 hyperlinks, OSC 7 current-working-directory reports, desktop notifications, window manipulation requests, palette controls, terminal response channels, and OSC 52 clipboard request auditing.
- `DONE(host)`: DECCOLM requires policy permission and explicit host acceptance before changing core state. Session synchronizes accepted 80/132-column changes with the connector before following output. IntelliJ ignores requests; standalone uses its existing resize permission and rejects disruptive or unrepresentable layouts. Embedders default to rejection. Product behavior is described under [Column Toggles](terminal-feature-map.md#1-terminal-protocols--control-sequences).
- `DONE(core/host/session/pty/workspace)`: targeted metadata callbacks publish effective palette changes and OSC 8 registry registration, eviction, and clearing through the existing host/PTY/workspace boundaries. Existing notification callbacks preserve individual requests, including identical repetitions. Callbacks are synchronous, policy-filtered for application controls, and independent of render publication; they do not replay initial state. Workspace forwarding applies to attached tabs. Active OSC 8 writing-attribute observation and application-facing color-scheme notifications are outside this slice. See [Targeted Host Metadata Events](terminal-feature-map.md#targeted-host-metadata-events) for delivery and reset semantics.
- `TODO(host)`: host callbacks for mouse-report policy when product surfaces need UI or embedding feedback.

---

## Input Module Gaps

- `DONE(input/policy)`: paste encoding, bracketed-paste wrapping, and `TerminalInputPolicy` paste sanitization are implemented and tested. The generic encoder preserves payloads by default, can strip C0 controls except TAB/CR/LF, can canonicalize CRLF/CR/LF through an explicit host policy, and wraps with `CSI 200~` / `CSI 201~` when bracketed paste mode is active. Bracketed payloads preserve their original line endings; unbracketed local PTY input canonicalizes newline forms to CR.
- `TODO(input/policy)`: the default raw bracketed-paste path writes clipboard text verbatim between `ESC[200~` and `ESC[201~`. An embedded `ESC[201~` can close the receiver's paste early and leave following text or newlines to be interpreted as live input. Define delimiter handling that makes the default safe while keeping any intentional raw-passthrough choice explicit; add an embedded-delimiter regression. See [xterm's bracketed-paste discussion](https://invisible-island.net/xterm/xterm-paste64.html).
- `DONE(host/profile)`: standalone/workspace local PTY profiles persist `paste_sanitization` (`raw`, `strip-c0`, or `normalize-line-endings`) and apply it to newly opened tabs and splits through `TerminalWorkspaceOpenOptions` and `PtyOptions.inputPolicy`.
- `DONE(host/profile)`: IDE settings expose and persist raw paste, C0 stripping, and line-ending normalization. The IDE-wide choice applies to new local sessions through workspace open options and to existing panes through Swing settings reload. Embedders can supply defaults through `TerminalWorkspaceOpenOptions` or `SwingSettings`, and update a running session through `TerminalSession.setPasteSanitizationPolicy` without replacing transport-specific input policy.
- `TODO(input)`: broader modified-key encoding:
  - xterm modifyOtherKeys subparameter mask support such as `CSI > 4 : 1 m`; this factors modifiers out of the source keysym and therefore remains deferred with rich layout-aware input metadata.
- `TODO(parser/core/host/input)`: xterm key resources beyond implemented `modifyOtherKeys` and `formatOtherKeys` are incomplete. The parser accepts generic XTMODKEYS/XTFMTKEYS set/reset commands, but the host drops keyboard, cursor, function, keypad, modifier, and special-key resources; core has no per-resource state and input has no matching encodings. XTQMODKEYS replies only for `modifyOtherKeys`, while XTQFMTKEYS (`CSI ? Pp g`) recognition is missing. Query, reset, and explicit-disable behavior need matching mode state and response policy before broader compatibility can be claimed.
- `TODO(input)`: a valid text-only `TerminalKeyEvent.text(...)` passed through default legacy keyboard mode emits NUL from its Kitty sentinel codepoint `0`, rather than the associated text or an explicit unsupported result. Current Swing does not construct text-only events, but embedders and future rich-input hosts can hit this public API path.
- `TODO(input/policy)`: additional xterm-compatible key policies when a real ambiguity exists, such as Delete behavior and optional eight-bit Meta output.
- `DONE(protocol/core/host/input/ui)`: DECBKM mode 67, conventional Ctrl+2 through Ctrl+8 control bytes, xterm modified F3, legacy F13-F35 aliases, and lossless Shift/Ctrl fallback for base Enter/Escape/Backspace/keypad keys are implemented through allocation-free packed mode state and primitive lookup tables.
- `TODO(parser/core/input)`: xterm highlight mouse tracking (`?1001`) if full xterm mouse parity is required.
- `TODO(ui)`: fractional high-resolution wheel events are lost in two Swing paths: active mouse tracking consumes zero-integer-rotation events without accumulating reports, and alternate-screen fallback with tracking off rounds each precise delta to an arrow-key count without retaining a remainder. Repeated trackpad movement can therefore do nothing in TUIs. Primary-screen viewport scrolling already accumulates fractional deltas; SGR-Pixels changes coordinates, not wheel deltas.

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

- `TODO(transport)`: the shipped workspace factory creates local PTY sessions only. A generic `TerminalConnector` contract exists, but there is no SSH connector, remote-session lifecycle, or product SSH session surface. Launching `ssh` inside a local shell is not equivalent. An SSH implementation also needs product-owned origin policy and paste defaults. [IntelliJ IDEA still routes SSH sessions through its Classic engine](https://www.jetbrains.com/help/idea/terminal-emulator.html), so this is a replacement-readiness gap.
- `TODO(host/profile)`: IntelliJ KetraTerm tabs are tool-window content only; there is no editor-tab file-editor integration. The current [IntelliJ terminal can move to an editor tab](https://www.jetbrains.com/help/idea/terminal-emulator.html), making placement a concrete IDE parity gap.
- `TODO(host/profile)`: execution-environment-aware launches remain partial. WSL shell profiles exist, but WSL-specific directory, JDK, and startup-command mapping is excluded from the current local-host slice; Dev Container launch context is absent. IntelliJ's [predefined-session list groups shells by host, WSL, or Dev Container environment](https://www.jetbrains.com/help/idea/terminal-emulator.html). Preserve the environment boundary instead of treating a local WSL launcher as full remote integration.
- `DONE(session)`: startup commands execute once after supported shell readiness, with standalone configuration and project-local IntelliJ settings. The [feature map](terminal-feature-map.md#7-embedding--swing-ui) defines supported shells, input cancellation, and launch restrictions; WSL launchers and multiline command payloads are outside this slice.
- `DONE(session)`: automatic foreground-process tab-title fallbacks for local PTYs, with lifecycle-bound shared polling and live persisted settings in both products. Custom and application titles retain priority, and unavailable detection preserves directory/profile fallbacks. Unix uses the foreground process-group leader; Windows uses a newest-descendant heuristic that may select a background child. Applications inside SSH/WSL are outside this local-process detection slice; exact precedence, bounds, and lifecycle behavior are documented in the feature map.
- `DONE(host/profile)`: IntelliJ **Open in KetraTerm** opens a new tab from local Project View, editor, and editor-tab file contexts. The [feature map](terminal-feature-map.md#7-embedding--swing-ui) defines directory selection and launch behavior.
- `DONE(host/profile)`: IntelliJ project workspace persistence restores open tabs, custom names, local working directories, profile choice, order, and selection. The [feature map](terminal-feature-map.md#7-embedding--swing-ui) defines lazy startup, directory fallback, and the local-host boundary.
- `DONE(host/profile)`: IntelliJ project JDK environment injection and its default-on setting follow the reworked IntelliJ terminal's launch precedence. The [feature map](terminal-feature-map.md#7-embedding--swing-ui) defines the supported local SDK and shell boundaries.
- `TODO(host/profile)`: Restore suggestion settings when they are ready for product exposure. Both settings forms retain commented `SUGGESTION_SETTINGS` blocks for the master switch, automatic popups, Enter acceptance, and persistence. Uncomment each form's fields, layout, change tracking where applicable, and Apply/Reset bindings together; uncomment the matching IntelliJ message key and update the hidden-controls tests. Keep the master default off. Learning-reset buttons also require reconnecting the removed host callbacks before restoring their confirmation UI (the previous wiring is in the parent of commit `4ef4d3f4`).
- `TODO(policy)`: standalone and IntelliJ classify clipboard/title origin once from the initial profile executable, treating only a directly launched `ssh` binary as remote. Starting SSH inside a local shell therefore keeps `LOCAL` permissions for remote output; the current defaults prompt for local OSC 52 writes and allow local title changes while remote defaults deny both. The local/remote policy labels are not a provenance guarantee for nested sessions. Define a conservative product policy or trusted host-owned origin boundary; terminal output bytes alone cannot prove their source.
- `DONE(policy/host/ui)`: OSC 52 clipboard policy covers local vs remote origin, deny/prompt/allowlist/allow write decisions, deny-by-default read/query decisions, decoded payload limits, malformed payload rejection, content-free audit events, allowed writes through product-host clipboard callbacks, and prompt-mode write confirmation in standalone and IntelliJ hosts. Product writes act only when the selection is empty or includes host clipboard `c`; an audit decision of `ALLOWED_BY_POLICY` for another selection or for a read request does not imply execution.
- `TODO(host/ui/policy)`: OSC 52 read/query requests can receive `ALLOW` or `PROMPT` policy decisions, and both product settings UIs visibly offer those read modes, but the host provides no clipboard read callback, prompt, or protocol response. Until selection-aware clipboard reads, prompt execution, and a response/denial contract are implemented, the visible read controls must not suggest working read access. OSC 4/10/11/12 palette queries already respond through the existing outbound channel.
- `DONE(ui)`: differentiated OSC 8 versus detected-link underlines and wrapped hover spans are implemented in Swing.
- `TODO(policy)`: richer hyperlink validation and display policy beyond host resource limits, host allow/deny gating, and Swing's explicit-activation handler.
- `DONE(parser/policy)`: current OSC/DCS families have explicit collection ceilings and overflow/recovery semantics in the [payload resource contract](terminal-feature-map.md#oscdcs-payload-resource-contract), including bounded unknown-family discard, hyperlink context clearing on completed overflow, and parser-to-host boundary tests. Ordinary commands retain the 4 KiB ceiling.
- `DONE(parser/host/session/policy)`: [bounded OSC 52 writes](terminal-feature-map.md#bounded-osc-52-writes) derive a temporary encoded budget from the active host decoded-byte policy, enabling eligible writes up to the default 1 MiB decoded ceiling. Tests cover buffer release, chunking, precise bounds, malformed data, origin/permission combinations, policy changes during transfer, and shutdown; ordinary metadata and clipboard reads retain their existing behavior.
- `TODO(parser/policy)`: graphics require separate bounded storage/transfer designs, APC where applicable, decoded/decompressed image bounds, and retained-image budgets; current clipboard/family ceilings alone do not enable graphics.
- `DONE(host/policy)`: title/icon updates are host-gated through `HostPolicy.titlePolicy`, which models local vs remote session origin, local/remote allow decisions, and configurable oversized-title handling (`clamp` by default for standalone compatibility, or `reject` for stricter profiles).
- `DONE(policy)`: terminal capability identity policy is explicit in `TerminalCapabilityIdentity` and consumed by PTY launch defaults plus core terminal-to-host query responses.
