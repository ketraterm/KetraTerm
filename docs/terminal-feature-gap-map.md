# Terminal Feature Gap Map

This is the living backlog for turning the current terminal parser/core into a
professional terminal emulator pipeline.

The intent is to make gaps explicit. If a feature is missing, unsupported, or
only partially modeled, it should be listed here rather than hidden behind a
silent no-op.

The target is a modern, secure, xterm-compatible terminal pipeline for
contemporary shells and TUIs. When an xterm feature is accepted into scope, its
implemented subset should be explicit and tested rather than a loose "v1"
approximation. Obsolete or risky legacy protocols remain excluded unless they
earn their place.

Status labels:

- `TODO(parser)`: byte/protocol recognition or semantic dispatch is missing.
- `TODO(core)`: terminal state, grid physics, pen storage, or public API is missing.
- `TODO(integration)`: parser and core both have enough shape, but the bridge is incomplete.
- `TODO(input)`: host-bound keyboard/mouse/paste encoding is missing.
- `TODO(host)`: session, transport, render, UI, or embedding behavior is missing.
- `TODO(policy)`: feature needs an explicit security or compatibility policy before implementation.

## Product Target

### Tier 1: Required

These are required for a modern terminal pipeline that can run contemporary
shells and TUIs well.

- UTF-8 decoding, Unicode grapheme segmentation, and core-owned width policy.
- CSI cursor movement, editing, erasing, scrolling, and mode toggles.
- SGR 16-color, 256-color, RGB/truecolor, and common text attributes.
- OSC title updates and OSC 8 hyperlinks.
- Bracketed paste mode.
- SGR mouse protocol.
- Primary/alternate screen switching.
- Terminal input encoding for keyboard, keypad, mouse, focus, and paste.

### Tier 2: Useful

These unlock richer app compatibility and better shell integration, but should
remain policy-gated where they can produce terminal-to-host responses.

- DSR, CPR, DA, DA2, and DA3 with a safe response policy.
- OSC palette queries and updates.
- DECRQSS and XTGETTCAP with an allowlist.
- Generated Unicode grapheme and width tables.
- Shell integration markers, including OSC 133 and common notification markers.
- Xterm title stack push/pop.
- Window/grid size queries.

### Tier 3: Optional

These are valuable for certain apps, but are not required for the first modern
pipeline milestone.

- Sixel or a modern graphics protocol.
- Richer hyperlink, title, palette, and notification host callbacks.
- Synchronized output mode.

## Intentional Non-Goals

These are not badges of compatibility for this project. They expand attack
surface or maintenance cost without meaningful modern terminal value.

- Tektronix 4014 emulation.
- Media Copy / printer passthrough (`CSI i`).
- X11-specific font loading protocols.
- Blind OSC 52 clipboard writes.
- Unbounded or unaudited DCS/OSC responses.
- Literal "everything xterm ever accepted" parity.

## Parser Gaps

### CSI Protocols

- `DONE(parser/integration)`: `DECSLRM` left/right margins, usually
  `CSI Pl ; Pr s`, route through parser and integration to core margins.
- `DONE(parser/integration)`: selective erase dispatch routes to core:
    - `DECSEL`, selective erase in line.
    - `DECSED`, selective erase in display.
- `DONE(parser/integration)`: `DECSCA` selective-erase protection routes to
  core protected-cell pen state.
- `DONE(parser/integration)`: `RIS`, `ESC c`, full terminal reset routes to
  `TerminalBufferApi.reset`.
- `DONE(parser/integration)`: modern/common DEC private mode vocabulary is
  named, parsed, tested, and either routed to durable core state or explicitly
  ignored:
    - application cursor/keypad modes, cursor blink/visibility, reverse video,
      origin, auto-wrap, left/right margin mode, and alternate-screen variants
    - mouse tracking and encoding selectors, focus reporting, and bracketed paste
    - synchronized output mode `?2026` is recognized, routed to core, and implemented with deferred rendering and safety timeouts in the session layer
- `DONE(parser/core/integration)`: alternate-screen and cursor-save variants
  are distinguished:
    - `47`, switch alternate screen without clearing or cursor save/restore
    - `1047`, switch alternate screen and clear on entry without cursor save/restore
    - `1048`, save/restore cursor without switching buffers
    - `1049`, save/restore cursor around a clearing alternate-screen switch
- `DONE(parser/integration)`: xterm title stack:
    - `CSI 22 t` push window/icon title
    - `CSI 23 t` pop window/icon title
      Shells use this when temporarily changing titles for foreground commands.
- `DONE(parser/core/integration)`: input-facing durable DEC private mode state
  routes into core mode snapshots for application cursor keys, application
  keypad, cursor blink, focus reporting, bracketed paste, mouse tracking modes,
  and mouse UTF-8/SGR/URXVT encoding selectors.
- `DONE(parser/core/integration/host)`: `DECSCUSR` cursor style / shape and blinking support (`CSI Ps SP q`) maps to dynamic render cursor shapes (BLOCK, UNDERLINE, BAR).
- `DONE(parser/core/integration)`: safe xterm window/grid size reports:
    - `CSI 14 t`, report window size in pixels
    - `CSI 18 t`, report terminal size in characters
      Pixel reports are silent until the host supplies positive pixel dimensions.
- `TODO(policy)`: xterm window manipulation:
    - `CSI 3 t`, move window
    - `CSI 8 t`, resize window
    - minimize/maximize/raise/lower variants
      Many modern terminals ignore or gate these to prevent hostile scripts from
      controlling the user's window.
- `DONE(parser/core/integration)`: terminal-to-host response channel and safe
  baseline responses for:
    - `DSR 5`, operating status, responding `CSI 0 n`
    - `CPR` / `DSR 6`, cursor position reports
    - primary `DA`, using a conservative VT100-compatible identity
    - secondary `DA2`, using a generic versionless identity
    - `DA3` is parsed but intentionally silent to avoid exposing a stable unit id
      without policy.
- `TODO(parser/core)`: broader DEC-specific status reports beyond the safe
  DSR/CPR/DA baseline.
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
    - `DECSTBM` is present
    - `SU` / `SD` are present
    - left/right-margin-aware variants need broader integration tests
- `DONE(parser/core/integration)`: erase saved lines / scrollback clear,
  `CSI 3 J`, is currently routed through ED mode 3 to core scrollback clearing.
  Keep this covered with regression tests because shells and clear-screen
  shortcuts rely on it.

### ESC Protocols

- `DONE(parser/integration)`: full reset `ESC c`.
- `DONE(parser/core/integration)`: DEC alignment test `DECALN`, `ESC # 8`.
- `DONE(parser)`: common ISO 2022 charset plumbing for G0-G3:
    - ASCII and DEC Special Graphics designation through `ESC (`, `ESC )`,
      `ESC *`, and `ESC +`
    - SO/SI locking shifts and SS2/SS3 single shifts
    - VT line drawing aliases used by common terminal descriptions
- `TODO(parser)`: broader ISO 2022 national replacement sets:
    - UK, US, Dutch, Finnish, French, German, Italian, Norwegian/Danish, Spanish,
      Swedish, Swiss, Portuguese
- `TODO(parser)`: 8-bit C1 equivalents for ESC-prefixed controls if raw C1 mode
  is supported later.
- `TODO(parser)`: save/restore state parity between DEC and SCO cursor save forms,
  if compatibility requires it.

### OSC Protocols

Implemented low-risk baseline:

- OSC 0, 1, 2 title variants
- OSC 8 hyperlinks
- OSC 52 is intentionally ignored

Missing:

- `TODO(policy)`: OSC 52 clipboard support. This needs an explicit permission
  and security policy before implementation.
- `TODO(parser)`: OSC 4 / 10 / 11 / 12 color palette queries and updates.
- `TODO(parser)`: OSC 7 current directory.
- `TODO(parser)`: OSC 9 / 9;9 desktop notifications, if desired.
- `TODO(parser)`: OSC 777 desktop notifications. Common in shell integrations
  and long-running command completion hooks.
- `TODO(parser)`: OSC 133 shell integration markers.
- `TODO(parser)`: OSC 1337/iTerm2 extensions, if desired.
- `TODO(parser)`: OSC query responses. Requires terminal-to-host output.
- `TODO(parser)`: payload encoding policy for non-UTF-8 or invalid UTF-8 OSC data.

### DCS Protocols

Current state:

- DCS payload is bounded and terminated correctly.
- Milestone behavior discards payload.

Missing:

- `TODO(parser)`: DCS dispatch policy and command router.
- `TODO(parser)`: XTGETTCAP / XTSETTCAP terminal capability query/response.
- `TODO(parser)`: DECRQSS request status string.
- `TODO(parser)`: Sixel graphics, if the emulator will support inline graphics.
- `TODO(parser)`: Kitty graphics protocol, commonly sent as `ESC _ G ... ESC \`.
  This is more relevant to modern TUIs than many legacy graphics protocols.
- `TODO(parser)`: ReGIS / DEC vector graphics, likely out of scope unless a DEC
  compatibility mode is a goal.
- `TODO(policy)`: any DCS that can exfiltrate host capabilities needs a response
  policy and terminal-to-host channel.

### Text and Unicode

- `DONE(parser)`: generated Unicode 17.0.0 grapheme tables from UAX #29
  `GraphemeBreakProperty.txt`.
- `DONE(parser)`: full Grapheme_Cluster_Break table coverage.
- `DONE(parser)`: full Extended_Pictographic table coverage from
  `emoji-data.txt`.
- `DONE(parser)`: versioned Unicode table generation and focused regression
  tests.
- `TODO(parser)`: malformed UTF-8 policy tests across all structural boundary
  bytes, not only the current representative hostile cases.
- `TODO(parser)`: configurable replacement policy if needed by host applications.
- `TODO(parser)`: broader ISO 2022 charset mapping.
- `DONE(parser/core)`: Thai and Lao combining marks are classified as
  grapheme extenders in parser text segmentation and zero-width marks in core
  width calculation through generated Unicode tables.
- `DONE(parser/core/integration)`: live host-output chunks publish complete
  printable prefixes immediately while retaining grapheme context, so a later
  combining mark, variation selector, or ZWJ continuation can extend the
  already written core cell without moving the cursor.

## Core Gaps

### Pen and Attributes

Current core attributes store default/indexed/RGB foreground/background,
default/indexed/RGB underline color, bold, faint, italic, underline style,
strikethrough, overline, blink, inverse/reverse-video, conceal, OSC 8
hyperlink id, and selective-erase protection.

Missing:

- `DONE(core)`: 256-color indexed foreground/background storage.
- `DONE(core)`: RGB/truecolor foreground/background storage.
- `DONE(core)`: inverse/reverse-video cell attribute.
- `DONE(core/integration)`: faint/dim intensity.
- `DONE(core/integration)`: blink attribute.
- `DONE(core/integration)`: conceal/hidden attribute.
- `DONE(core/integration)`: strikethrough attribute.
- `DONE(core/integration)`: underline style beyond boolean underline:
    - none
    - single
    - double
    - curly
    - dotted
    - dashed
- `DONE(core/integration)`: SGR underline color via SGR 58/59.
- `DONE(core/integration)`: SGR overline.
- `DONE(core/integration)`: cell-level OSC 8 hyperlink id storage. Core stores
  the numeric id; integration owns the URL/id pool for now.
- `DONE(render-api/host)`: immutable renderer-facing palette model for default
  foreground/background, selection colors, cursor colors, ANSI 16, indexed
  256-color, RGB passthrough, and bold-as-bright policy.
- `DONE(render-api/host)`: renderer-facing effective color calculation for
  default/indexed/RGB foreground and background, reverse video, conceal, and
  bold-as-bright policy.
- `TODO(parser/integration/host)`: OSC palette query/update protocols and host
  policy for mutating or reporting palette state.

### Reset and Mode Semantics

- `DONE(core)`: durable mode flags used by input/render decisions are stored in
  a single atomic packed word. Core exposes both a typed mode snapshot and an
  opaque packed snapshot without making grid, cursor, or history state
  multi-reader.
- `DONE(core/integration)`: DECSTR soft reset API. `CSI ! p` now routes to a
  core soft reset that preserves visible content, scrollback, tab stops,
  dimensions, and active screen selection while resetting soft mode/write state,
  margins, pen attributes, selective-erase write protection, pending wrap, and
  saved-cursor restore targets.
- `DONE(core/integration)`: alternate-screen variants distinguish switch-only,
  clearing switch-only, cursor save/restore-only, and 1049-style combined
  behavior.
- `DONE(core/host/ui)`: cursor visibility and blink state are exposed through
  durable core modes, render-frame cursor snapshots, and Swing repaint/cursor
  painting logic.
- `DONE(parser/core/integration/host)`: cursor style protocol support (`DECSCUSR`) parsed, routed, and maps dynamically to render shapes (BLOCK, UNDERLINE, BAR).
- `DONE(core/session)`: synchronized output mode state (`?2026`) defers frame publishing to UI with a safety timeout of 100ms.
- `DONE(core/integration/host)`: title and icon title state are stored in core,
  advanced through integration metadata, published through render generation,
  and surfaced to hosts through `TerminalHostEventSink`.
- `DONE(integration/host)`: BEL is surfaced through `TerminalHostEventSink`.
- `TODO(core/host)`: richer event API for hyperlink metadata, palette changes,
  terminal notifications, and any future host-observable state that should not
  be read from render frames.

### Grid Operations

- `TODO(core)`: rectangular area operations if parser support is added:
    - copy rectangle
    - erase rectangle
    - fill rectangle
    - selective erase rectangle
- `TODO(core)`: left/right margin interactions need continued property testing
  across all edit/erase/scroll operations.
- `TODO(core)`: full DEC protection behavior across all rectangular operations.
- `TODO(core)`: scrollback policy under alternate-screen and private-mode
  combinations beyond current tested cases.
- `TODO(core)`: soft-wrap metadata compatibility with copy/paste/export.

### Unicode Width

- `DONE(core)`: generated Unicode 17.0.0 width tables from current Unicode data:
    - EastAsianWidth
    - emoji presentation
    - zero-width and combining ranges
    - ambiguous-width policy
- `DONE(core)`: width policy for emoji presentation selectors is explicit and
  backed by generated emoji property and variation-sequence tables. Emoji ZWJ
  cluster width continues to derive from the first scalar, matching the current
  parser/core contract.
- `DONE(core)`: configurable ambiguous-width policy is present and backed by
  generated East Asian Width ambiguous ranges.
- `DONE(core)`: terminal cell graphics such as box drawing, block elements,
  Braille patterns, and Symbols for Legacy Computing stay single-cell even when
  the host enables East Asian Ambiguous wide mode.
- `TODO(core)`: invalid/unassigned codepoint width policy.

### Query and Response Channel

- `DONE(core)`: terminal-to-host response queue and safe response generation for:
    - DSR/CPR
    - primary DA and secondary DA2
    - safe xterm grid/pixel size reports
- `DONE(input/session)`: host-bound interactive reports and delimiters for
  mouse events, focus reports, and bracketed paste boundaries are generated by
  `:terminal-input` and serialized through `TerminalSession` with core/parser
  responses.
- `TODO(policy)`: DA3 terminal unit id behavior.
- `TODO(core)`: terminal-to-host response/event API for:
    - XTGETTCAP
    - OSC queries
    - future query/response protocols that need core state
- `TODO(core/host)`: event API for hyperlinks, palette updates, and terminal
  notifications if these move out of integration or render-frame metadata.

## Integration Gaps

- `DONE(integration)`: parser SGR inverse, 256-color indexed, and RGB/truecolor
  attributes are mapped to core pen attributes without clamping. SGR 38:2/48:2
  support includes robust subparameter chain length logic to handle explicit
  and omitted color space IDs in colon-separated sequences.
- `DONE(integration)`: map faint, blink, conceal, strikethrough, overline,
  underline style, and underline color SGR attributes to core pen storage.
- `DONE(integration)`: parser DECSTR maps to core `softReset`.
- `DONE(integration)`: map alternate-screen `47`, `1047`, `1048`, and `1049`
  to distinct core semantics.
- `DONE(integration)`: parser RIS maps to core `reset`.
- `DONE(integration)`: parser DECSLRM maps to core left/right margins.
- `DONE(integration)`: parser DECSEL/DECSED/DECSCA map to core selective erase
  and protection commands.
- `DONE(integration)`: OSC 8 active hyperlink metadata maps to core cell
  hyperlink ids using an integration-owned URL/id pool.
- `DONE(integration/policy)`: OSC 8 hyperlink id pool is bounded by
  `TerminalHostPolicy`, rejects overlong URI/id payloads, and evicts
  least-recently-used entries instead of growing without limit.
- `DONE(integration/session/ui)`: OSC 8 hyperlink ids can be resolved through
  session metadata and activated by Swing only after explicit Ctrl-left-click
  through a host-replaceable hyperlink handler.
- `DONE(core/integration/host)`: OSC title state is stored in core, mirrored as
  integration metadata for title stack behavior, and reported to hosts through
  `TerminalHostEventSink`.
- `DONE(integration/host)`: host callback/event sink exists for BEL and OSC
  icon/window title changes. Device responses use the core response queue, and
  UI input reports use `TerminalSession` host output serialization.
- `TODO(integration/host/policy)`: host callbacks or policy surfaces for
  palette updates, terminal notifications, mouse-report policy, and clipboard
  policy.

## Input Module Gaps

The `:terminal-input` module owns host-bound keyboard, mouse, paste, and focus
encoding. It depends on `:terminal-protocol` vocabulary and core input-readable
mode snapshots, not parser internals or core storage details. The default
encoder reads packed mode bits once per event and serializes output through a
host-bound byte sink.

The module behavioral contract lives in
`terminal-input/docs/terminal-input-contract.md`.

Missing:

- `DONE(input/docs)`: terminal input has a module contract covering event
  validation, mode reads, host-output ordering, thread-safety, encoding
  guarantees, policy surfaces, allocation rules, and testing obligations.
- `DONE(input)`: keyboard encoding for printable Unicode scalars,
  Ctrl/Alt combinations, normal and application cursor-key modes, navigation
  keys, function keys, and numeric/application keypad modes.
- `DONE(input)`: bracketed paste wrapping reads core mode bits once per event
  and emits `CSI 200~` / `CSI 201~` wrappers when enabled.
- `DONE(input)`: focus in/out reports read core mode bits once per event and
  emit `CSI I` / `CSI O` only when focus reporting is enabled.
- `DONE(input)`: mouse report encoding:
    - X10, normal, button-event, and any-event tracking suppression rules
    - SGR mouse encoding, including button-preserving lowercase-`m` releases
    - bounded legacy `ESC [ M` encoding with explicit coordinate-limit policy
    - UTF-8 extended mouse encoding (`?1005`) up to xterm's coordinate limit
    - URXVT mouse encoding (`?1015`)
- `DONE(parser/core/integration/input)`: xterm modifyOtherKeys and
  formatOtherKeys support for ordinary-key input:
    - modifyOtherKeys mode 1/2/3 consumption from core packed mode bits
    - original `CSI 27 ; modifier ; codepoint ~` format
    - `formatOtherKeys=1` / CSI-u `CSI codepoint ; modifier u` format
    - parser/integration routing for `CSI > 4 ; Pv m` and `CSI > 4 ; Pv f`
    - regression coverage for modified printable input and control-equivalent
      Tab/Enter/Backspace/Escape cases
- `TODO(input)`: broader modified-key encoding:
    - xterm modifyOtherKeys subparameter mask support such as `CSI > 4 : 1 m`
    - query/disable controls for xterm key modifier options if host responses or
      exact disabled-resource semantics are needed
- `TODO(parser/core/input)`: xterm modified-key policy surface for
  `modifyCursorKeys`, `modifyFunctionKeys`, and `modifyKeypadKeys` if those are
  added. These need protocol/core mode or policy state before input grows more
  branches.
- `DONE(input)`: keypad fidelity for xterm's stable cell-keyboard vocabulary:
    - keypad space, tab, equals, comma, separator, and begin/keypad-5 when UI
      adapters expose them
    - explicit PF1-PF4 terminal keys rather than permanently overloading physical
      F1-F4 when a platform can distinguish keypad PF keys
- `TODO(input/policy)`: additional xterm-compatible key policies when a real
  ambiguity exists, such as Delete behavior and optional eight-bit Meta output.
- `TODO(input)`: SGR-Pixels mouse mode (`?1016`) if renderer/UI integration
  provides pixel-coordinate mouse events.
- `TODO(parser/core/input)`: xterm highlight mouse tracking (`?1001`) if full
  xterm mouse parity is required; it needs a distinct interaction contract
  rather than simple cell-coordinate event forwarding.
- `DONE(input/policy)`: paste sanitization policy hook:
    - raw paste
    - strip C0 controls except TAB/CR/LF
    - line-ending normalization
- `DONE(integration/host)`: terminal-to-host output ordering contract. UI input
  events and parser/core responses such as DSR/CPR/DA/OSC/DCS replies should be
  serialized through the same terminal actor and `TerminalHostOutput`.
- `DONE(input)`: xterm input profile matrix tests covering application
  cursor/keypad, bracketed paste, focus, mouse tracking/encoding combinations,
  and modifyOtherKeys off/mode1/mode2.
- `DONE(protocol/core/parser/integration)`: Kitty Keyboard Protocol foundation:
    - protocol constants for the five progressive-enhancement flags and flag
      application modes
    - packed core input-mode bits and snapshot helpers for active Kitty
      keyboard flags
    - public core setter and typed mode snapshot field for active Kitty
      keyboard flags
    - core tests for defaults, snapshots, reset/soft-reset behavior, masking,
      controller routing, and input-mode bit decoding
    - parser recognition and semantic sink dispatch for `CSI = flags ; mode u`,
      `CSI > flags u`, and `CSI < count u`
    - parser recording-sink and full byte-stream tests for default parameters,
      malformed parameters, and structural dispatch
    - integration routing for `CSI = flags ; mode u` replace/set/clear
      semantics into core's active Kitty keyboard flag state
- `DONE(core/input)`: Kitty Keyboard Protocol behavior (expanded key coverage for printables, control keys, arrows, navigation keys, function keys, and numeric keypad keys). Implemented as a clean, separate protocol path.

Planned Kitty Keyboard Protocol scope:

- Reference: kitty's "Comprehensive keyboard handling in terminals" protocol,
  especially progressive enhancement, key-code, modifier, event-type, and
  associated-text rules.
- Ownership:
    - `terminal-protocol`: primitive constants for progressive-enhancement flags,
      flag-application modes, event types, and functional-key numeric codes used
      by host-bound encoding.
    - `terminal-parser`: recognize Kitty keyboard mode controls only. It emits
      semantic sink calls for `CSI = flags ; mode u`, `CSI > flags u`, and
      `CSI < number u`; later `CSI ? u` support needs explicit response
      policy. It must not encode keyboard input.
    - `terminal-core`: store input-readable Kitty keyboard flags and, once
      push/pop controls are parsed, a bounded mode stack. The stack must
      eventually respect kitty's main-screen and alternate-screen separation
      rule; do not fake this in integration.
    - `terminal-integration`: maps active flag application controls to core
      APIs, with explicit no-op/TODO behavior for stack controls until core
      owns stack state and for unsupported query responses until policy exists.
    - `terminal-input`: add a dedicated Kitty encoder branch selected from core
      mode bits. Do not mix Kitty progressive-enhancement flags into the xterm
      legacy/modifyOtherKeys branch.
- First implementation milestone:
    - support the five progressive-enhancement flags:
        - disambiguate escape codes, `1`
        - report event types, `2`
        - report alternate keys, `4`
        - report all keys as escape codes, `8`
        - report associated text, `16`
    - support flag application modes for `CSI = flags ; mode u`:
        - mode `1`: replace active flags
        - mode `2`: set only the supplied bits
        - mode `3`: clear only the supplied bits
    - support push/pop controls with a small bounded stack:
        - `CSI > flags u`: push current flags, then apply supplied flags
        - `CSI < number u`: pop one or more stack entries, defaulting to one
      - `TODO(core)`: add bounded stack state when parser/integration starts
        routing push/pop controls.
    - encode only key press events at first; report repeat/release events only
      after UI event vocabulary exposes them distinctly.
    - implement the high-value input slice first:
        - printable Unicode scalar keys
        - Enter, Tab, Backspace, Escape
        - modifiers already represented by `TerminalModifiers`
        - no alternate-key or associated-text payload until normalized UI events
          expose enough layout/text data to do it honestly
- Deferred Kitty scope:
    - `CSI ? u` query response and terminal capability identity policy
    - separate left/right modifier reporting if host event vocabulary grows it
    - key repeat/release reporting
    - alternate-key fields and associated text fields
    - modifier-only key events
    - complete functional-key numeric table beyond keys already represented by
      `TerminalKey`

## Session, Transport, Rendering, and Host Integration Gaps

Rendering is intentionally outside the current core/parser modules, but a
professional emulator needs explicit contracts for it.

- `DONE(host)`: transport/session split:
    - `:terminal-transport-api` defines `TerminalConnector` and
      `TerminalConnectorListener`
    - `:terminal-session` owns parser/core synchronization, core response
      draining, input encoding, outbound write ordering, local/remote close state,
      and idempotent parser cleanup
    - `:terminal-testkit` provides `MockConnector` for logic tests
- `DONE(host)`: local PTY process lifecycle:
    - `:terminal-pty` starts PTY4J-backed processes behind `PtyConnector`
    - PTY connectors own reader/watcher threads, raw host bytes, stdin writes,
      resize, local close, read errors, and process exit notification
    - `TerminalPtySessions` and `TerminalSessions.localPty` return the shared
      `TerminalSession` rather than a PTY-specific session class
- `DONE(host/integration)`: PTY convenience factory wires integration host event
  callbacks for BEL and OSC icon/window title changes.
- `DONE(host)`: opt-in native PTY integration tests verify real process output,
  resize, local close, process exit codes, and large output flow through PTY4J,
  connector, session, parser, integration, and core. They are gated by
  `-Dterminal.pty.integration=true` to keep default tests deterministic.
- `DONE(core/host)`: primitive renderer frame API and core adapter for visible
  rows, stable public cell/attribute encodings, cursor visibility/blink state,
  reverse-video translation, hyperlinks ids, clusters, wrap flags, and
  generation-based row invalidation.
- `DONE(host)`: dependency-free primitive `TerminalRenderCache` consumer model
  that copies frame rows, clusters, attrs, hyperlinks, wrap flags, cursor state,
  and line-generation metadata from `TerminalRenderFrameReader`.
- `DONE(host/ui)`: reusable `:terminal-ui-swing` module exists with a public
  Swing terminal component, immutable settings/metrics snapshots, packed ARGB
  palette resolution, and a basic Java2D painter backed by
  `TerminalRenderPublisher`.
- `DONE(host/ui)`: standalone `:terminal-ui-swing-demo` application wires the
  reusable Swing component to a local PTY-backed session for manual testing.
- `DONE(host/ui)`: Swing complex-text fallback path exists for Unicode scalar
  cells, including astral-plane scalars, and grapheme clusters. It uses
  configured fallback fonts, optional installed system font scanning, cached
  style variants, per-text fallback resolution caching, and Java2D `TextLayout`
  shaping without affecting the ASCII fast path.
- `DONE(host/ui)`: Swing fallback font policy prefers installed native color
  emoji families before logical and symbol fonts, and keeps emoji fallback
  fonts plain so styled cells do not force synthetic monochrome variants.
- `DONE(host/ui)`: Swing text rendering quality hints are host-configurable via
  immutable settings, including text antialiasing and fractional-metrics policy.
- `TODO(host)`: richer font fallback policy, bundled/host-provided font
  resolver integration, script/run-level shaping, and fallback cache eviction.
- `DONE(host/ui)`: native platform emoji presentation has a Windows
  `Segoe UI Emoji` COLR/CPAL rasterizer for platform color glyphs, with a
  Java2D fallback when native color glyph data is unavailable.
- `DONE(host/session/ui)`: East Asian Ambiguous width policy is host
  configurable through Swing settings, applied through the session mutation
  lock, and can be supplied to PTY startup before host output is parsed.
- `DONE(host/ui)`: Swing text, decoration, cursor, repaint planning, and emoji
  painting paths honor core/render wide-cell spans, including wide scalar cells,
  wide grapheme clusters, and cursor positions over wide trailing spacers.
- `DONE(host/ui)`: immutable Swing settings and frozen metrics snapshots cover
  terminal fonts, cell dimensions, decoration positions, cursor stroke width,
  palette/default-color policy, text antialiasing, and fractional metrics.
- `TODO(host)`: richer font measurement policy for script/run-level shaping,
  fallback run metrics, and backend painter integrations beyond the current
  Java2D/Swing path.
- `DONE(host/ui)`: baseline and advanced text selection and clipboard integration:
    - mouse drag selection over visible render-cache rows
    - drag autoscroll at the top/bottom viewport edges with distance-proportional velocity acceleration
    - double-click smart auto-detecting word, path, and URL selection with full Unicode/CLUSTER support
    - triple-click line selection
    - selection retention policy while scrolled or when new frames are published
    - rectangular block selection (via Alt+Drag)
    - mouse tracking protocol interception (with Shift bypass support)
    - configurable Swing selection overlay color with a soft white default
    - platform clipboard shortcuts copy selected visible text through the Swing
      clipboard abstraction
    - platform paste shortcuts read clipboard text and send a terminal paste event
- `DONE(host/session/ui)`: Swing hyperlink activation hit-tests primitive
  render-cache hyperlink ids outside the paint loop, resolves metadata through
  `TerminalSession`, changes the hover cursor over resolvable links, and opens
  links only through the configured `TerminalHyperlinkHandler`.
- `DONE(render-api/core/cache/session)`: caller-owned scrollback viewport
  offsets can be requested per render-frame read, clamped by core, copied by
  the primitive render cache, and forwarded through session synchronization.
- `DONE(swing)`: mouse-wheel scrollback updates are owned by the Swing
  component and request offset-specific render-cache publications through the
  session render worker.
- `TODO(host)`: UI scrollback controls, scrollbar policy, selection behavior
  while scrolled, and auto-follow/offset-retention policy.
- `TODO(host)`: accessibility/export APIs.
- `TODO(host)`: performance benchmarks for large scrollback, resize, and dense
  SGR streams.

## Security and Policy Gaps

- `TODO(policy)`: OSC 52 clipboard permission model.
- `TODO(policy)`: DCS/OSC query response allowlist.
- `TODO(policy)`: richer hyperlink validation and display policy beyond
  integration resource limits and Swing's explicit-activation handler.
- `DONE(parser)`: OSC/DCS string payload accumulation is bounded by parser
  state and overflowed payloads are discarded safely.
- `TODO(policy)`: protocol-family-specific payload limits and host-configurable
  caps beyond the parser's generic bound.
- `TODO(policy)`: whether title/icon updates are always accepted or host-gated.
- `TODO(policy)`: paste sanitization and bracketed paste defaults.
- `TODO(policy)`: terminal capability identity. Claiming xterm compatibility
  requires implementing enough behavior to make that claim true.
- `TODO(policy)`: window manipulation allow/deny behavior for xterm window ops.
- `TODO(policy)`: desktop notification allow/deny behavior for OSC 777 and
  related notification protocols.
