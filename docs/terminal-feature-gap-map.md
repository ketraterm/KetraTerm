# Terminal Feature Gap Map

This is the living backlog of gaps, TODOs, and policy constraints in the KetraTerm parser, core, host, input, and UI layers. 

For a complete directory of supported features, see the [Terminal Feature Map](file:///c:/Users/gagik/IdeaProjects/KetraTerm/docs/terminal-feature-map.md).

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

### Tier 2: Useful (Under consideration / partial gaps)
- *No outstanding SAFE query-response, DECRQSS/XTGETTCAP, push/pop title stack, or host-adapter allow/deny policy-surface gaps (all implemented and verified).*

### Tier 3: Optional (Graphics & advanced features)
- Sixel or modern graphics protocols (e.g. Kitty graphics protocol).
- Richer hyperlink, title, palette, and notification host callbacks.
- Synchronized output mode extensions.

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
- `TODO(parser/core)`: broader DEC-specific status reports beyond the safe DSR/CPR/DA baseline.
- `TODO(parser)`: character attribute/protection commands not covered by SGR:
  - `DECSACE`
- `TODO(parser)`: full tab-stop and margin variants beyond the current common set.
- `TODO(parser)`: rectangular area operations:
  - `DECCRA`
  - `DECERA`
  - `DECFRA`
  - `DECSERA`
  - `DECSACE`
- `TODO(parser)`: insert/delete/erase variants with selective protection and rectangular bounds.
- `TODO(parser)`: scroll variants and xterm extensions not yet routed:
  - left/right-margin-aware variants need broader host tests.

### ESC Protocols
- `TODO(parser)`: broader ISO 2022 national replacement sets:
  - UK, US, Dutch, Finnish, French, German, Italian, Norwegian/Danish, Spanish, Swedish, Swiss, Portuguese.
- `TODO(parser)`: 8-bit C1 equivalents for ESC-prefixed controls if raw C1 mode is supported later.
- `TODO(parser)`: save/restore state parity between DEC and SCO cursor save forms, if compatibility requires it.

### OSC Protocols
- `DONE(parser/host/session/pty/workspace/ui/policy)`: OSC 52 clipboard write requests are bounded, parsed, denied by default, size-checked, origin-aware, surfaced as content-free host audit events, forwarded as decoded text to product-host clipboard callbacks only when policy allows the write, and forwarded to compact standalone/IntelliJ confirmation dialogs when policy requires a prompt. Product prompts use terminal profile names, character counts for text writes, and clear-clipboard wording for empty writes instead of exposing raw OSC 52 selection tokens.
- `TODO(host/ui/policy)`: OSC 52 allowlist management UI, non-clipboard selection mapping, and any read/query response path remain unimplemented until product hosts explicitly opt in. Standalone and IntelliJ settings intentionally hide the `allowlist` option until a product-owned allowlist can persist entries and set `TerminalClipboardPolicy.allowlisted`.
- `TODO(parser)`: OSC 1337/iTerm2 extensions, if desired.
- `TODO(parser)`: OSC query responses. Requires terminal-to-host output.
- `TODO(parser)`: payload encoding policy for non-UTF-8 or invalid UTF-8 OSC data.

### DCS Protocols
- `TODO(parser)`: Sixel graphics, if the emulator will support inline graphics.
- `TODO(parser)`: Kitty graphics protocol, commonly sent as `ESC _ G ... ESC \`. This is more relevant to modern TUIs than legacy graphics.
- `TODO(parser)`: ReGIS / DEC vector graphics (likely out of scope).
- `TODO(policy)`: any DCS that can exfiltrate host capabilities needs a response policy and terminal-to-host channel.

### Text and Unicode
- `TODO(parser)`: malformed UTF-8 policy tests across all structural boundary bytes.
- `TODO(parser)`: configurable replacement policy if needed by host applications.
- `TODO(parser)`: broader ISO 2022 charset mapping.

---

## Core Gaps

### Reset and Mode Semantics
- `TODO(core/host)`: richer event API for hyperlink metadata, palette changes, terminal notifications, and any future host-observable state that should not be read from render frames.

### Grid Operations
- `TODO(core)`: rectangular area operations if parser support is added (copy, erase, fill, selective erase).
- `TODO(core)`: left/right margin interactions need continued property testing across all edit/erase/scroll operations.
- `TODO(core)`: full DEC protection behavior across all rectangular operations.
- `TODO(core)`: scrollback policy under alternate-screen and private-mode combinations beyond current tested cases.
- `TODO(core)`: soft-wrap metadata compatibility with copy/paste/export.

### Unicode Width
- `TODO(core)`: invalid/unassigned codepoint width policy.

### Query and Response Channel
- `TODO(core)`: terminal-to-host response/event API for `XTGETTCAP`, OSC queries, and future query/response protocols.
- `TODO(core/host)`: event API for hyperlinks, palette updates, and terminal notifications if these move out of host or render-frame metadata.

---

## Integration Gaps

- `DONE(host/policy)`: host-adapter allow/deny policy surface for title updates, OSC 8 hyperlinks, OSC 7 current-working-directory reports, desktop notifications, window manipulation requests, palette controls, terminal response channels, and OSC 52 clipboard request auditing.
- `TODO(host)`: richer host callbacks for palette updates, terminal notifications, mouse-report policy, and future clipboard decisions when those product surfaces need UI or embedding feedback.

---

## Input Module Gaps

- `DONE(input/policy)`: paste encoding, bracketed-paste wrapping, and `TerminalInputPolicy` paste sanitization are implemented and tested. The encoder preserves payloads by default, can strip C0 controls except TAB/CR/LF, can normalize CRLF/lone-CR line endings, and wraps with `CSI 200~` / `CSI 201~` when bracketed paste mode is active.
- `DONE(host/profile)`: standalone/workspace local PTY profiles persist `paste_sanitization` (`raw`, `strip-c0`, or `normalize-line-endings`) and apply it to newly opened tabs and splits through `TerminalWorkspaceOpenOptions` and `PtyOptions.inputPolicy`.
- `TODO(host/profile)`: expose paste policy defaults for SSH and IDE/workspace embedding profiles when those product surfaces are wired; input already provides the mechanism.
- `TODO(input)`: broader modified-key encoding:
  - xterm modifyOtherKeys subparameter mask support such as `CSI > 4 : 1 m`.
  - query/disable controls for xterm key modifier options.
- `TODO(parser/core/input)`: xterm modified-key policy surface for `modifyCursorKeys`, `modifyFunctionKeys`, and `modifyKeypadKeys`.
- `TODO(input/policy)`: additional xterm-compatible key policies when a real ambiguity exists, such as Delete behavior and optional eight-bit Meta output.
- `TODO(parser/core/input)`: xterm highlight mouse tracking (`?1001`) if full xterm mouse parity is required.

### Deferred Kitty Keyboard Protocol Scope
- `DONE(protocol/core/pty)`: terminal capability identity contract centralizes `$TERM`, `COLORTERM`, DA/DA2, XTGETTCAP terminal-name/color claims, and the implemented Kitty keyboard flag mask.
- `TODO(parser/core/policy)`: `CSI ? u` Kitty keyboard capability query response remains disabled until a response shape and fingerprinting policy are implemented.
- separate left/right modifier reporting if host event vocabulary grows it.
- key repeat/release reporting.
- alternate-key fields and associated text fields.
- modifier-only key events.
- complete functional-key numeric table beyond keys already represented by `TerminalKey`.

---

## Session, Transport, Rendering, and Host Integration Gaps

- `TODO(host)`: richer font fallback policy, bundled/host-provided font resolver host, script/run-level shaping, and fallback cache eviction.
- `TODO(host)`: richer font measurement policy for script/run-level shaping, fallback run metrics, and backend painter integrations beyond the current Java2D/Swing path.
- `TODO(host)`: custom line spacing/height metrics in the renderer.
- `TODO(host/ui)`: richer UI scrollback controls, selection behavior while scrolled, and auto-follow/offset-retention policy beyond the current fixed-grid Swing scrollbar and viewport mapping.
- `TODO(host)`: accessibility/export APIs.
- `TODO(host)`: performance benchmarks for large scrollback, resize, and dense SGR streams.

---

## Security and Policy Gaps

- `DONE(policy/host/ui)`: OSC 52 clipboard permission model covers local vs remote origin, deny/prompt/allowlist/allow write decisions, disabled read/query behavior, decoded payload limits, malformed payload rejection, content-free audit events, allowed write execution through product-host clipboard callbacks, and prompt-mode write confirmation in standalone plus IntelliJ hosts. Standalone and IntelliJ expose only currently actionable write/read modes in settings while retaining low-level allowlist enforcement for future product allowlists.
- `TODO(host/ui/policy)`: OSC 52 allowlist management UI, selection-specific clipboard targets beyond the host clipboard, and read/query responses remain intentionally absent.
- `TODO(policy)`: richer hyperlink validation and display policy beyond host resource limits, host allow/deny gating, and Swing's explicit-activation handler.
- `DONE(host/policy)`: host-owned metadata and response controls have explicit policy gates and per-feature host caps for titles, hyperlinks, OSC 7 current-working-directory reports, notifications, palette controls, window manipulation, and terminal response channels.
- `TODO(parser/policy)`: protocol-family-specific raw OSC/DCS parser payload ceilings beyond the parser's generic bound, especially before large graphics or clipboard protocols are enabled.
- `DONE(host/policy)`: title/icon updates are host-gated through `HostPolicy.titlePolicy`, which models local vs remote session origin, local/remote allow decisions, and configurable oversized-title handling (`clamp` by default for standalone compatibility, or `reject` for stricter profiles).
- `DONE(policy)`: terminal capability identity policy is explicit in `TerminalCapabilityIdentity` and consumed by PTY launch defaults plus core terminal-to-host query responses.
