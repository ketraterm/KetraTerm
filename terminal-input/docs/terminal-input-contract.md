# Terminal Input Contract

This document defines the public behavioral contract of `:terminal-input`.

It is the host-bound input boundary for normalized keyboard, mouse, paste, and
focus events. If code outside the input module depends on behavior not described
here, that behavior is not yet guaranteed.

## Scope

Input owns:

- platform-neutral keyboard, mouse, paste, and focus event vocabulary
- event validation for input-only invariants
- host-bound byte encoding for keyboard, keypad, mouse, paste, and focus reports
- policy decisions for ambiguous or unsupported input encodings
- allocation-conscious scratch buffers for generated terminal input sequences
- input-facing reads from core's packed mode snapshot

Input does not own:

- terminal output parsing
- grid, cursor, scrollback, pen, or title state
- pixel-to-cell mouse coordinate conversion
- renderer state or UI toolkit event types
- parser/core response generation such as DSR, CPR, DA, OSC, or DCS replies
- raw terminal mode bit layout

## Public API Surfaces

The public input surface is:

- `TerminalInputEncoder`, the facade for encoding one normalized input event
- `TerminalKeyEvent`, `TerminalPasteEvent`, `TerminalFocusEvent`, and
  `TerminalMouseEvent`, the normalized event models
- `TerminalInputPolicy`, the compatibility and safety policy for ambiguous
  encodings
- `TerminalHostOutput`, the host-bound byte sink shared with terminal responses

External UI code should construct normalized events and call the encoder from
the serialized terminal event loop. It should not reach into specialized
implementation encoders.

## Event Contract

For every event:

- valid event plus supported encoding emits bytes
- valid event plus unsupported combination follows explicit policy
- invalid event fails fast at event construction or at the encode boundary

Keyboard events contain exactly one of:

- a `TerminalKey` for non-printable physical keys
- a printable Unicode scalar codepoint

Guaranteed behavior:

- invalid modifier bitmasks are rejected
- surrogate codepoints and values above `U+10FFFF` are rejected
- C0 control codepoints and DEL are rejected as printable input
- physical control-ish input such as Enter, Tab, Escape, and Backspace uses
  `TerminalKey`

Mouse events use zero-based cell coordinates. The encoder converts them to
one-based terminal protocol coordinates at the wire boundary.

Guaranteed behavior:

- negative mouse coordinates are rejected
- invalid modifier bitmasks are rejected
- press events require a concrete non-wheel button
- wheel buttons require a wheel event
- motion can use `NONE` for any-event tracking
- release can use `NONE`, though SGR encoding suppresses it because SGR
  releases preserve button identity

## Mode-State Contract

`DefaultTerminalInputEncoder` reads `TerminalInputState.getInputModeBits()` once
per event and passes that stable value to the specialized encoder.

Input may use only core/protocol helper APIs to interpret packed mode state,
including:

- application cursor keys
- application keypad
- newline mode
- bracketed paste
- focus reporting
- mouse tracking mode
- mouse encoding mode
- modify-other-keys mode

Input must not decode raw mode bit positions. If a new input-facing mode is
needed, add protocol vocabulary and a core `TerminalInputState` helper first.

## Ordering And Threading

`DefaultTerminalInputEncoder` is not thread-safe. Calls must be serialized by
the terminal event loop.

This is intentional:

- terminal-to-host byte ordering must be deterministic
- the encoder reuses one scratch buffer to avoid per-event allocation
- `writeBytes(bytes, offset, length)` may receive scratch-backed byte ranges
  that are reused immediately after the call returns

The intended host-bound ordering model is:

```text
UI adapter -> terminal actor -> terminal-input -> TerminalHostOutput -> PTY stdin
parser/core responses -> same terminal actor -> TerminalHostOutput -> PTY stdin
```

Concurrent calls from independent UI and parser/core threads are outside this
module's contract unless the host wraps them in an ordering layer before they
reach `TerminalHostOutput`.

## Keyboard Contract

Printable codepoints encode as UTF-8 when unmodified and supported by the active
keyboard mode.

Guaranteed behavior:

- Ctrl mappings are explicit and never silently drop unsupported modifiers
- Alt can prefix applicable legacy encodings with ESC under policy
- Meta handling for legacy printable/control encodings follows policy
- special keys with xterm CSI modifier encodings may encode Meta as modifier
  parameter 9
- Backspace byte selection follows `BackspacePolicy`
- Enter follows newline mode for unmodified or policy-accepted events
- application cursor and application keypad modes are read from the per-event
  mode snapshot
- PF1-PF4 are explicit terminal keys and are not permanently conflated with
  physical F1-F4 at the event vocabulary boundary

Supported modified-key protocol:

- legacy xterm CSI modifier encodings for cursor, navigation, function, and
  supported keypad/special keys
- original xterm modifyOtherKeys format,
  `CSI 27 ; modifier ; codepoint ~`, for mode 1, mode 2, and mode 3
- xterm `formatOtherKeys=1` / CSI-u format,
  `CSI codepoint ; modifier u`, when enabled by core input-mode state

Not guaranteed yet:

- modifyCursorKeys, modifyFunctionKeys, or modifyKeypadKeys resource variants
- Kitty Keyboard Protocol

## Mouse Contract

Mouse encoding consumes normalized cell-coordinate events and the current mouse
tracking/encoding modes from the per-event mode snapshot.

Tracking suppression is guaranteed:

- `NONE` suppresses all mouse events
- `X10` emits press only
- `NORMAL` emits press, release, and wheel, and suppresses motion
- `BUTTON_EVENT` emits press, release, wheel, and button motion
- `ANY_EVENT` emits press, release, wheel, button motion, and no-button motion
- unknown tracking modes suppress all mouse events

Supported mouse encodings:

- SGR mouse
- default legacy `ESC [ M`
- UTF-8 extended mouse (`?1005`)
- URXVT mouse (`?1015`)

Guaranteed behavior:

- SGR release preserves the original button code and uses lowercase `m`
- legacy release uses button code 3
- SGR coordinates are decimal and are not limited by the legacy byte range
- legacy default coordinates are bounded to one-based coordinate 223, with
  out-of-range behavior controlled by policy
- Meta is ignored for mouse v1 modifier packing

Not guaranteed yet:

- SGR-Pixels mouse mode (`?1016`)
- xterm highlight mouse tracking (`?1001`)
- UI pointer capture, drag threshold, or double/triple-click interpretation

## Paste And Focus Contract

Paste encoding preserves payload bytes by default after UTF-8 conversion from
the provided Kotlin string. Bracketed paste mode wraps the payload with
`CSI 200~` and `CSI 201~` when enabled in the per-event mode snapshot.

Paste policy may:

- preserve text exactly as provided
- strip C0 controls except TAB, CR, and LF
- normalize CRLF and lone CR line endings to LF

Focus encoding emits `CSI I` and `CSI O` only when focus reporting is enabled
in the per-event mode snapshot.

## Host Output Contract

`TerminalHostOutput` is synchronous from the caller's perspective. When a write
method returns, the implementation must have consumed or copied the provided
data.

Guaranteed caller behavior:

- byte values are in the unsigned byte range `0..255`
- `writeBytes` receives an explicit offset and length
- scratch-backed arrays may be reused immediately after `writeBytes` returns

Ordering across independent terminal-to-host producers is not guaranteed by the
sink itself. The terminal actor or host integration layer owns that ordering.

## Allocation Contract

The input hot path should remain allocation-minimal.

Guaranteed implementation direction:

- static byte arrays are used for common unmodified sequences
- reusable scratch buffers are used for dynamic CSI, mouse, and UTF-8 scalar
  encodings
- generated byte ranges are written with offset/length

Avoid in encoder hot paths:

- `sliceArray`
- regex
- `StringBuilder` for generated terminal sequences
- ad hoc string assembly for CSI/SS3 bytes

## Testing Contract

Input behavior should be tested as exact terminal bytes, not implementation
accidents.

Required coverage for behavior changes:

- event validation failures
- exact keyboard bytes for printable, control, special, keypad, and modified
  keys
- exact mouse bytes for each supported tracking and encoding mode
- paste policy and bracketed paste behavior
- focus reporting suppression and emission
- one-read-per-event mode behavior through `DefaultTerminalInputEncoder`
- real core packed mode integration for input-facing modes
- xterm input profile matrix coverage for supported mode combinations

The feature gap map must be updated when the public input contract grows,
shrinks, or intentionally defers a terminal behavior.

## Intentional Deviations

These are intentional boundary choices, not accidental gaps:

- input does not parse terminal output
- input does not mutate core state
- input does not know raw mode bit positions
- input does not convert pointer pixels to cells
- input does not provide a thread-safe facade over host-bound byte ordering

## Pre-1.0 Change Surface

Likely to evolve before 1.0:

- broader modified-key protocol support
- optional Kitty keyboard protocol as a separate encoder path
- SGR-Pixels mouse event vocabulary if renderer/UI integration supplies pixel
  coordinates
- paste policy surface if host integration needs stricter security defaults
- a future `:terminal-core-api` split for input-facing mode reads

The runtime semantics described in this document are the current intended
contract for integrating UI adapters, `:terminal-core`, and host-bound terminal
input.
