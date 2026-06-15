# Terminal Feature Gap Map

This is the living backlog of gaps, TODOs, and policy constraints in the JVTerm parser, core, host, input, and UI layers. 

For a complete directory of supported features, see the [Terminal Feature Map](file:///c:/Users/gagik/IdeaProjects/JvTerm/docs/terminal-feature-map.md).

The target is a modern, secure, xterm-compatible terminal pipeline for contemporary shells and TUIs. Obsolete or risky legacy protocols remain excluded unless they earn their place.

## Status Labels

- `TODO(parser)`: byte/protocol recognition or semantic dispatch is missing.
- `TODO(core)`: terminal state, grid physics, pen storage, or public API is missing.
- `TODO(host)`: parser and core both have enough shape, but the integration bridge is incomplete.
- `TODO(input)`: host-bound keyboard/mouse/paste encoding is missing.
- `TODO(policy)`: feature needs an explicit security or compatibility policy before implementation.

---

## Product Targets & Priority

### Tier 1: Required (Gaps in core modern features)
- *No outstanding CSI cursor, SGR color, alternate screen, or basic input gaps.*

### Tier 2: Useful (Under consideration / partial gaps)
- *No outstanding SAFE query-response, DECRQSS/XTGETTCAP, or push/pop title stack gaps (all implemented and verified).*

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
- Blind OSC 52 clipboard writes.
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
- `TODO(policy)`: OSC 52 clipboard support. This needs an explicit permission and security policy before implementation.
- `TODO(parser)`: OSC 7 current directory.
- `TODO(parser)`: OSC 133 shell host markers.
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

- `TODO(host/host/policy)`: host callbacks or policy surfaces for palette updates, terminal notifications, mouse-report policy, and clipboard policy.

---

## Input Module Gaps

- `TODO(input)`: broader modified-key encoding:
  - xterm modifyOtherKeys subparameter mask support such as `CSI > 4 : 1 m`.
  - query/disable controls for xterm key modifier options.
- `TODO(parser/core/input)`: xterm modified-key policy surface for `modifyCursorKeys`, `modifyFunctionKeys`, and `modifyKeypadKeys`.
- `TODO(input/policy)`: additional xterm-compatible key policies when a real ambiguity exists, such as Delete behavior and optional eight-bit Meta output.
- `TODO(parser/core/input)`: xterm highlight mouse tracking (`?1001`) if full xterm mouse parity is required.

### Deferred Kitty Keyboard Protocol Scope
- `CSI ? u` query response and terminal capability identity policy.
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
- `TODO(host)`: UI scrollback controls, scrollbar policy, selection behavior while scrolled, and auto-follow/offset-retention policy.
- `TODO(host)`: accessibility/export APIs.
- `TODO(host)`: performance benchmarks for large scrollback, resize, and dense SGR streams.

---

## Security and Policy Gaps

- `TODO(policy)`: OSC 52 clipboard permission model.
- `TODO(policy)`: richer hyperlink validation and display policy beyond host resource limits and Swing's explicit-activation handler.
- `TODO(policy)`: protocol-family-specific payload limits and host-configurable caps beyond the parser's generic bound.
- `TODO(policy)`: whether title/icon updates are always accepted or host-gated.
- `TODO(policy)`: paste sanitization and bracketed paste defaults.
- `TODO(policy)`: terminal capability identity policies.
