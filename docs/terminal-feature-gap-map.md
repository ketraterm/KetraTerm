# Terminal Feature Gap Map

This is the living backlog of gaps, TODOs, and policy constraints in the KetraTerm parser, core, host, input, and UI layers. 

For a complete directory of supported features, see the [Terminal Feature Map](terminal-feature-map.md).

The target is a modern, secure, xterm-compatible terminal pipeline for contemporary shells and TUIs. Obsolete or risky legacy protocols remain excluded unless they earn their place.

## Status Labels

- `TODO(parser)`: byte/protocol recognition or semantic dispatch is missing.
- `TODO(core)`: terminal state, grid physics, pen storage, or public API is missing.
- `TODO(host)`: parser and core both have enough shape, but the integration bridge is incomplete.
- `TODO(session)`: runtime synchronization, host-side state, or session-owned metadata is missing.
- `TODO(ui)`: reusable UI presentation, interaction, or rendering behavior is missing.
- `TODO(input)`: host-bound keyboard/mouse/paste encoding is missing.
- `TODO(host/profile)`: product host, profile, or settings defaults are not exposed even though the lower-level terminal mechanism exists.
- `TODO(policy)`: feature needs an explicit security or compatibility policy before implementation.

---

## Product Targets & Priority

### Tier 1: Required (Gaps in core modern features)
- *No outstanding CSI cursor, SGR color, alternate screen, or basic input gaps.*
- `DONE(core/host)`: default cursor-style resets restore the configured shape and enable blinking; alternate-screen exit restores the primary shape and blink flag. Coverage includes all screen-switch pairings, repeated commands, split reset sequences, resize/reset interactions, render publication, and a real PTY teardown fixture matching Neovim's steady-block exit sequence. See [Cursor Settings](terminal-feature-map.md#1-terminal-protocols--control-sequences) for the supported semantics.

### Tier 2: Useful (Under consideration / partial gaps)
- *The existing safe query-response baseline, DECRQSS/XTGETTCAP, push/pop title stack, and host-adapter allow/deny policy surface are implemented and verified. Additional query protocols are tracked below.*
- `FIXED(alpha-blocker)`: Kitty keyboard capability advertising is per-session and admits only progressive flags backed by complete active-host metadata. The portable Swing profile, including IntelliJ-hosted Swing, exposes `1` (disambiguate escape codes) and `8` (report all keys as escape codes); richer flags stay unadvertised.

### Tier 3: Optional (Graphics & advanced features)
- Sixel or modern graphics protocols (e.g. Kitty graphics protocol).

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

---

## Parser Gaps

### CSI Protocols
- `DONE(parser/host)`: parameterless ANSI/SCO `CSI s` / `CSI u` compatibility, including mode-aware DECSLRM disambiguation and shared DEC/SCO cursor and charset save/restore. The [feature map](terminal-feature-map.md#1-terminal-protocols--control-sequences) defines the supported forms; byte-stream tests cover mixed forms, chunk boundaries, margin changes, resets, screen-local cursor slots, resize, and malformed input.
- `TODO(parser/core)`: broader DEC-specific status reports beyond the safe DSR/CPR/DA baseline.
- `TODO(parser)`: full tab-stop and margin variants beyond the current common set.
- `DONE(parser/core/host)`: rectangular erase (`DECERA`), selective erase (`DECSERA`), fill (`DECFRA`), copy (`DECCRA`), and checksum response (`DECRQCRA`) preserve active margin/origin coordinates; mutation operations preserve wide/cluster span integrity and `DECCRA` uses overlap-safe snapshot semantics. Copy/checksum intentionally support only the active single page (`0` omitted or `1`); checksum responses are terminal-response-policy gated and use the VT420 default 16-bit algorithm.
- `TODO(policy)`: xterm `XTCHECKSUM` extensions (`CSI Ps # y`) are not implemented. The stable DECRQCRA path deliberately uses the base VT420 behavior: erased/spacer cells omitted, base glyph values masked to eight bits, and supported legacy video attributes included; no color, combining-sequence, or alternate xterm extension semantics are claimed.
- `DONE(parser/core/host)`: DECSACE, DECCARA, and DECRARA implement VT420's stream-versus-exact-rectangle extent, ordered visual SGR subset, blank materialization policy, and atomic wide/cluster attribute updates without changing glyph payloads, protection, hyperlinks, or the current pen.
- `DONE(parser/core/host)`: DECIC and DECDC insert/delete columns across the active vertical scroll region, honor horizontal margins, preserve cursor position and active-buffer isolation, and repair wide/cluster span boundaries before each row shift.
- `TODO(parser)`: insert/delete/erase variants with selective protection and rectangular bounds.
- `TODO(parser)`: scroll variants and xterm extensions not yet routed:
  - left/right-margin-aware variants need broader host tests.

### ESC Protocols
- `TODO(parser)`: broader ISO 2022 national replacement sets:
  - UK, US, Dutch, Finnish, French, German, Italian, Norwegian/Danish, Spanish, Swedish, Swiss, Portuguese.
- `TODO(parser)`: 8-bit C1 equivalents for ESC-prefixed controls if raw C1 mode is supported later.

### OSC Protocols
- `DONE(parser/host/session/pty/workspace/ui/policy)`: OSC 52 clipboard write requests are bounded, parsed, denied by default, size-checked, origin-aware, surfaced as content-free host audit events, forwarded as decoded text to product-host clipboard callbacks only when policy allows the write, and forwarded to compact standalone/IntelliJ confirmation dialogs when policy requires a prompt. Product prompts use terminal profile names, character counts for text writes, and clear-clipboard wording for empty writes instead of exposing raw OSC 52 selection tokens.
- `TODO(host/ui/policy)`: OSC 52 allowlist management UI, non-clipboard selection mapping, and any read/query response path remain unimplemented until product hosts explicitly opt in. Standalone and IntelliJ settings intentionally hide the `allowlist` option until a product-owned allowlist can persist entries and set `TerminalClipboardPolicy.allowlisted`.
- `TODO(parser)`: OSC 1337/iTerm2 extensions, if desired.
- `TODO(parser)`: OSC query responses. Requires terminal-to-host output.
- `DONE(parser/host)`: explicit OSC encoding and recovery rules distinguish replacement-decoded display text from strictly validated structured metadata and clipboard text. Rejected links clear active context; other rejected metadata retains prior state. The [OSC encoding contract](terminal-feature-map.md#osc-encoding-and-recovery-contract) defines family-specific effects, audit precedence, overflow, abort/EOF recovery, and scalar-safe host limits. Legacy encoding detection and fallback are intentionally absent.

### DCS Protocols
- `TODO(parser)`: Sixel graphics, if the emulator will support inline graphics.
- `TODO(parser)`: Kitty graphics protocol, commonly sent as `ESC _ G ... ESC \`. This is more relevant to modern TUIs than legacy graphics.
- `TODO(parser)`: ReGIS / DEC vector graphics (likely out of scope).
- `TODO(policy)`: any DCS that can exfiltrate host capabilities needs a response policy and terminal-to-host channel.

### Text and Unicode
- `DONE(parser)`: malformed UTF-8 recovery is exercised immediately before and inside ESC, CSI,
  OSC (BEL/ST/CAN/SUB), DCS ST, and end-of-input, with every split boundary proving that malformed
  bytes do not print or complete stale structural commands.
- `TODO(parser)`: configurable replacement policy if needed by host applications.
- `TODO(parser)`: broader ISO 2022 charset mapping.

---

## Core Gaps

### Grid Operations
- DONE(core): deterministic randomized left/right-margin properties cover ICH/DCH, selective erase, IL/DL, and partial-region scroll up/down. They model guard-column movement, preserve rows outside the scroll region, and verify protected wide spans plus wide/cluster storage invariants.
- `DONE(core/host)`: alternate-screen byte-stream coverage verifies exact primary history retention and zero alternate history across every `47`/`1047`/`1049` entry/exit pairing, repeated commands and re-entry, screen-local `1048` saves, and ordered private-mode lists. `ED2`, repeated `ED3`, and repeated `DECSTR` tests verify active-buffer clearing or text preservation, including combining/wide text and saved-cursor behavior. `DECCOLM` tests cover both 80/132-column directions and current-width requests, preserving primary history while alternate is active and clearing primary history when primary is active.
- `DONE(core)`: resize/capacity tests verify exact retained rows with zero, bounded, and spare history capacity, oldest-row eviction during narrowing and height shrink, and repeated `47`/`1047`/`1049` resize cycles without alternate text leaking or evicted rows returning. Cursor and scrollback anchors account for reflow eviction, including empty rows, wide characters, and grapheme clusters; evicted viewport anchors clamp to the oldest retained row.
- `DONE(core/session/ui)`: soft-wrap text reconstruction preserves written and erased spaces for linear selection, command capture, clipboard copy/paste, and retained-output export. Core distinguishes artificial wide-character wrap padding from meaningful empty cells and preserves that distinction through resize/reflow. Selected hard line breaks survive empty selection endpoints; block selections retain physical row breaks.

### Unicode Width
- `DONE(core/host/ui)`: invalid/unassigned codepoint width policy is explicit in the [core contract](../ketraterm-core/docs/terminal-core-contract.md#unicode-scalar-and-width-policy). Typed scalar/cluster writes reject non-scalars atomically; string writes repair unpaired surrogates. Pinned Unicode tables preserve reserved wide ranges and ambiguous-width behavior, with byte-split integration coverage for replacement, wrapping, and cursor alignment, plus real-buffer selection extraction through resize/reflow.

### Query and Response Channel
- `DONE(core/host/policy)`: terminal-to-host response channel exists for DA, DSR/CPR, safe window reports, palette queries, `DECRQSS`, and allowlisted `XTGETTCAP`; host policy can deny terminal responses before they enqueue bytes.
- `DONE(core/host/policy)`: light/dark color-scheme query (`CSI ?996n`) returns `CSI ?997;1n` (dark) or `CSI ?997;2n` (light) from the active host theme palette under terminal-response policy. Standalone and IDE theme updates use the existing synchronized palette publication path; application color overrides do not affect the reply. Denied requests stay silent because this protocol has no failure response. The implemented slice is the one-shot query, without mode 2031 unsolicited notifications.
- `TODO(core/parser/host/policy)`: OSC query responses and future query/response protocols need explicit response shape, allowlist, and host policy before implementation.

---

## Integration Gaps

- `DONE(host/policy)`: host-adapter allow/deny policy surface for title updates, OSC 8 hyperlinks, OSC 7 current-working-directory reports, desktop notifications, window manipulation requests, palette controls, terminal response channels, and OSC 52 clipboard request auditing.
- `DONE(host)`: DECCOLM requires policy permission and explicit host acceptance before changing core state. Session synchronizes accepted 80/132-column changes with the connector before following output. IntelliJ ignores requests; standalone uses its existing resize permission and rejects disruptive or unrepresentable layouts. Embedders default to rejection. Product behavior is described under [Column Toggles](terminal-feature-map.md#1-terminal-protocols--control-sequences).
- `DONE(core/host/session/pty/workspace)`: targeted metadata callbacks publish effective palette changes and OSC 8 registry registration, eviction, and clearing through the existing host/PTY/workspace boundaries. Existing notification callbacks preserve individual requests, including identical repetitions. Callbacks are synchronous, policy-filtered for application controls, and independent of render publication; they do not replay initial state. Workspace forwarding applies to attached tabs. Active OSC 8 writing-attribute observation and application-facing color-scheme notifications are outside this slice. See [Targeted Host Metadata Events](terminal-feature-map.md#targeted-host-metadata-events) for delivery and reset semantics.
- `TODO(host)`: host callbacks for mouse-report policy when product surfaces need UI or embedding feedback.

---

## Input Module Gaps

- `DONE(input/policy)`: paste encoding, bracketed-paste wrapping, and `TerminalInputPolicy` paste sanitization are implemented and tested. The generic encoder preserves payloads by default, can strip C0 controls except TAB/CR/LF, can canonicalize CRLF/CR/LF through an explicit host policy, and wraps with `CSI 200~` / `CSI 201~` when bracketed paste mode is active. Bracketed payloads preserve their original line endings; unbracketed local PTY input canonicalizes newline forms to CR.
- `DONE(host/profile)`: standalone/workspace local PTY profiles persist `paste_sanitization` (`raw`, `strip-c0`, or `normalize-line-endings`) and apply it to newly opened tabs and splits through `TerminalWorkspaceOpenOptions` and `PtyOptions.inputPolicy`.
- `DONE(host/profile)`: IDE settings expose and persist raw paste, C0 stripping, and line-ending normalization. The IDE-wide choice applies to new local sessions through workspace open options and to existing panes through Swing settings reload. Embedders can supply defaults through `TerminalWorkspaceOpenOptions` or `SwingSettings`, and update a running session through `TerminalSession.setPasteSanitizationPolicy` without replacing transport-specific input policy.
- `TODO(host/profile)`: expose paste policy defaults for SSH profiles when that product surface is wired; input already provides the mechanism.
- `TODO(input)`: broader modified-key encoding:
  - xterm modifyOtherKeys subparameter mask support such as `CSI > 4 : 1 m`; this factors modifiers out of the source keysym and therefore remains deferred with rich layout-aware input metadata.
- `TODO(parser/core/input)`: xterm modified-key policy surface for `modifyCursorKeys`, `modifyFunctionKeys`, and `modifyKeypadKeys`.
- `TODO(input/policy)`: additional xterm-compatible key policies when a real ambiguity exists, such as Delete behavior and optional eight-bit Meta output.
- `DONE(protocol/core/host/input/ui)`: DECBKM mode 67, conventional Ctrl+2 through Ctrl+8 control bytes, xterm modified F3, legacy F13-F35 aliases, and lossless Shift/Ctrl fallback for base Enter/Escape/Backspace/keypad keys are implemented through allocation-free packed mode state and primitive lookup tables.
- `TODO(parser/core/input)`: xterm highlight mouse tracking (`?1001`) if full xterm mouse parity is required.

### Deferred Kitty Keyboard Protocol Scope
- `DONE(protocol/core/pty)`: terminal capability identity contract centralizes `$TERM`, `COLORTERM`, DA/DA2, XTGETTCAP terminal-name/color claims, and the implemented Kitty keyboard flag mask.
- `DONE(protocol/core/host/session/input)`: Kitty capability handling distinguishes the full encoder mask from the conservative portable-host mask. The parser-to-core adapter applies a per-session host mask to replace/set/clear/push operations, so query responses reflect only capabilities the active host has explicitly declared.
- `DONE(input)`: `TerminalKeyEvent` can carry an optional unshifted printable-key scalar, and Kitty CSI-u encoding uses it instead of produced text when present.
- `DONE(parser/core/host/policy)`: parameterless `CSI ? u` reports only active Kitty keyboard progressive flags admitted by the session's host capability mask through the existing terminal-response policy.
- `TODO(host, deferred)`: native rich-input adapters for layout-aware physical-key identity, IME text, and complete lifecycle metadata. This requires host-specific native integration; no portable Swing or IntelliJ-hosted Swing session may advertise flags `2`, `4`, or `16` until that work is implemented and verified.
- `DONE(input)`: normalized keyboard events carry an explicit press/repeat/release lifecycle phase without host-toolkit dependencies or encoder hot-path allocation.
- `DONE(input)`: the Kitty encoder formats the lifecycle phase in the `modifier:event-type` subfield; without flag `2`, releases are suppressed while repeats retain normal press encoding.
- `DONE(ui)`: Swing preserves press/repeat/release for AWT-visible non-text physical keys through a fixed preallocated pressed-key table; unmatched releases are not invented or emitted.
- `DONE(protocol/input/ui)`: the complete Kitty functional-key table is represented by normalized input vocabulary and allocation-free PUA lookup tables; Swing maps the subset exposed by AWT, while richer hosts can supply the remaining keys.
- `DONE(input)`: normalized modifiers preserve Kitty's independent Super, Hyper, Meta, Caps Lock, and Num Lock bits while legacy CSI encodings retain their compatible four-modifier representation. Modifier-only key reports are gated by flag `8`.
- `DONE(input)`: normalized printable events carry validated Kitty shifted and base-layout alternate key scalars; the encoder formats both fields, including the required empty shifted field when only a base-layout scalar exists.
- `DONE(input)`: host-owned associated text is scalar-validated and encoded as Kitty colon-separated text codepoints without encoder-side allocation.

---

## Session, Transport, Rendering, and Host Integration Gaps

- `DONE(session)`: startup commands execute once after supported shell readiness, with standalone configuration and project-local IntelliJ settings. The [feature map](terminal-feature-map.md#7-embedding--swing-ui) defines supported shells, input cancellation, and launch restrictions; WSL launchers and multiline command payloads are outside this slice.
- `DONE(session)`: automatic foreground-process tab-title fallbacks for local PTYs, with lifecycle-bound shared polling and live persisted settings in both products. Custom and application titles retain priority, and unavailable detection preserves directory/profile fallbacks. Unix uses the foreground process-group leader; Windows uses a newest-descendant heuristic that may select a background child. Applications inside SSH/WSL are outside this local-process detection slice; exact precedence, bounds, and lifecycle behavior are documented in the feature map.
- `DONE(host/profile)`: IntelliJ **Open in KetraTerm** opens a new tab from local Project View, editor, and editor-tab file contexts. The [feature map](terminal-feature-map.md#7-embedding--swing-ui) defines directory selection and launch behavior.
- `DONE(host/profile)`: IntelliJ project workspace persistence restores open tabs, custom names, local working directories, profile choice, order, and selection. The [feature map](terminal-feature-map.md#7-embedding--swing-ui) defines lazy startup, directory fallback, and the local-host boundary.
- `DONE(host/profile)`: IntelliJ project JDK environment injection and its default-on setting follow the reworked IntelliJ terminal's launch precedence. The [feature map](terminal-feature-map.md#7-embedding--swing-ui) defines the supported local SDK and shell boundaries.
- `TODO(host/profile)`: Restore suggestion settings when they are ready for product exposure. Both settings forms retain commented `SUGGESTION_SETTINGS` blocks for the master switch, automatic popups, Enter acceptance, and persistence. Uncomment each form's fields, layout, change tracking where applicable, and Apply/Reset bindings together; uncomment the matching IntelliJ message key and update the hidden-controls tests. Keep the master default off. Learning-reset buttons also require reconnecting the removed host callbacks before restoring their confirmation UI (the previous wiring is in the parent of commit `4ef4d3f4`).
- `DONE(policy/host/ui)`: OSC 52 clipboard permission model covers local vs remote origin, deny/prompt/allowlist/allow write decisions, disabled read/query behavior, decoded payload limits, malformed payload rejection, content-free audit events, allowed write execution through product-host clipboard callbacks, and prompt-mode write confirmation in standalone plus IntelliJ hosts. Standalone and IntelliJ expose only currently actionable write/read modes in settings while retaining low-level allowlist enforcement for future product allowlists.
- `TODO(host/ui/policy)`: OSC 52 allowlist management UI, selection-specific clipboard targets beyond the host clipboard, and read/query responses remain intentionally absent.
- `DONE(ui)`: differentiated OSC 8 versus detected-link underlines and wrapped hover spans are implemented in Swing.
- `TODO(policy)`: richer hyperlink validation and display policy beyond host resource limits, host allow/deny gating, and Swing's explicit-activation handler.
- `DONE(host/policy)`: host-owned metadata and response controls have explicit policy gates and per-feature host caps for titles, hyperlinks, OSC 7 current-working-directory reports, notifications, palette controls, window manipulation, and terminal response channels.
- `TODO(parser/policy)`: protocol-family-specific raw OSC/DCS parser payload ceilings beyond the parser's generic bound, especially before large graphics or clipboard protocols are enabled.
- `DONE(host/policy)`: title/icon updates are host-gated through `HostPolicy.titlePolicy`, which models local vs remote session origin, local/remote allow decisions, and configurable oversized-title handling (`clamp` by default for standalone compatibility, or `reject` for stricter profiles).
- `DONE(policy)`: terminal capability identity policy is explicit in `TerminalCapabilityIdentity` and consumed by PTY launch defaults plus core terminal-to-host query responses.
