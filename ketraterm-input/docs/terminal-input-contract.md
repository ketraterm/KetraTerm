# Terminal Input Contract

This document defines the public behavioral contract of `:ketraterm-input`.

It is the host-bound input boundary for normalized keyboard, mouse, paste,
text-replacement, and focus events. If code outside the input module depends on
behavior not described here, that behavior is not yet guaranteed.

## Scope

Input owns:

- platform-neutral keyboard, mouse, paste, text-replacement, and focus event vocabulary
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
- `TerminalKeyEvent`, `TerminalPasteEvent`, `TerminalTextReplacementEvent`,
  `TerminalFocusEvent`, and `TerminalMouseEvent`, the normalized event models
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
- text-only marker `0` with non-empty committed text when an IME or host text
  API has no physical-key identity (also Kitty's text-only key code)

Each event also carries a host-reported lifecycle phase: press, repeat, or
release. Hosts must not infer repeat or release semantics they cannot observe.

Printable events may additionally carry unshifted, shifted current-layout, and
standard PC-101 base-layout scalars that identify the physical text-producing
key, as well as associated host-owned text. Input sources must leave each value
unknown when they cannot provide it truthfully; each is distinct from produced
text and is used only by the relevant Kitty-compatible CSI-u progressive flag.

Guaranteed behavior:

- invalid modifier bitmasks are rejected
- surrogate codepoints and values above `U+10FFFF` are rejected
- C0 control codepoints and DEL are rejected as printable input
- text-only events require scalar-valid associated text and cannot carry
  physical-key scalar metadata
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

Text-replacement events describe one logical edit around the active cursor:
Delete actions for text after the cursor, Backspace actions for text before the
cursor, then optional replacement text through the normal paste policy. Counts
are editor actions, not UTF-16 units or Unicode code points, and must be
non-negative. The UI that owns the edited text is responsible for converting
its text model into deletion counts.

## Mode-State Contract

`DefaultTerminalInputEncoder` reads `TerminalInputState.getInputModeBits()` once
per event and passes that stable value to the specialized encoder. A complete
text replacement uses one mode snapshot for all deletion and paste phases.

Input may use only core/protocol helper APIs to interpret packed mode state,
including:

- application cursor keys
- application keypad
- newline mode
- bracketed paste
- focus reporting
- mouse tracking mode
- mouse encoding mode
- independent xterm key modifier and format resources

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
UI adapter -> terminal actor -> ketraterm-input -> TerminalHostOutput -> PTY stdin
parser/core responses -> same terminal actor -> TerminalHostOutput -> PTY stdin
```

Concurrent calls from independent UI and parser/core threads are outside this
module's contract unless the host wraps them in an ordering layer before they
reach `TerminalHostOutput`.

`TerminalSession` treats one text-replacement event as one serialized outbound
operation. Its Delete, Backspace, and paste phases cannot interleave with other
session input or parser/core responses. The default encoder coalesces repeated
deletion sequences through a bounded reusable buffer; replacement text still
uses the configured paste policy.

## Keyboard Contract

Printable codepoints encode as UTF-8 when unmodified and supported by the active
keyboard mode.

`TerminalKeyEvent.text(...)` represents committed text with no physical-key identity.
In legacy mode, including modifyOtherKeys/formatOtherKeys, and in Kitty modes without
report-all flag `8`, press and repeat emit the complete associated text as UTF-8.
Release emits nothing in these text modes, even if Kitty event-type flag `2` is set.
Modifiers are metadata already accounted for by the host's text production: they do
not trigger Ctrl mappings, ESC prefixes, or physical-key suppression policies.
The marker `0` is never emitted as NUL. Known-key events retain their existing modifier
and associated-text behavior.

When Kitty report-all flag `8` is set, text-only events require associated-text flag
`16` and use the existing CSI-u report with key code `0`. Without `16`, they are
explicitly suppressed: no physical identity can be reported, and the application
has disabled plain text. Host capability admission for these flags is unchanged.
See the [Kitty text and key reporting rules](https://sw.kovidgoyal.net/kitty/keyboard-protocol/#an-overview).

Text-only commits are not paste events and receive no bracketed-paste framing or
paste transformations. Construction rejects empty strings, C0/C1 controls, DEL,
and malformed UTF-16. Encoding walks validated scalars through the existing reusable
UTF-8 scratch buffer, so commits larger than its capacity are not truncated and do
not require a temporary byte array. Session serialization covers the entire commit.

Guaranteed behavior:

- repeat events use normal press encoding unless Kitty event-type reporting is
  active; release events are suppressed when the active protocol cannot
  represent releases
- Ctrl mappings are explicit and never silently drop unsupported modifiers
- Alt can prefix applicable legacy encodings with ESC under policy
- Meta handling for legacy printable/control encodings follows policy
- special keys with xterm CSI modifier encodings may encode Meta as modifier
  parameter 9
- Backspace byte selection follows `BackspacePolicy` until the host explicitly
  selects DECBKM; DECSET 67 sends BS and DECRST 67 sends DEL until reset
- DECRQM mode 67 and legacy Backspace share the same effective-selection helper;
  session input-policy changes publish the default after updating the encoder,
  without holding the parser mutation lock while awaiting outbound writes;
  queries read the last published default through a volatile field
- Enter follows newline mode for unmodified or policy-accepted events unless
  the active input policy forces CR-only Return for cooked PTY hosts
- application cursor and application keypad modes are read from the per-event
  mode snapshot
- PF1-PF4 are explicit terminal keys and are not permanently conflated with
  physical F1-F4 at the event vocabulary boundary. Function resource 2 does not
  alter their legacy modified CSI reports; keypad resource 3 selects their
  extended reports.

Supported modified-key protocol:

- legacy xterm CSI modifier encodings for cursor, navigation, function, and
  supported keypad/special keys
- original xterm modifyOtherKeys format,
  `CSI 27 ; modifier ; codepoint ~`, for mode 1, mode 2, and mode 3
- xterm `formatOtherKeys=1` / CSI-u format,
  `CSI codepoint ; modifier u`, when enabled by core input-mode state
- partial Kitty keyboard handling for progressive flags `1` and `8`. Flag `1`
  preserves legacy Enter, Tab, and Backspace bytes; Kitty CSI-u output uses a
  supplied unshifted key scalar when the input source provides one.
- Kitty modifier encoding uses all eight protocol-defined bits. Legacy xterm
  encodings retain their four-modifier representation; modifier-only key events
  are emitted in Kitty report-all-keys mode (`8`) or xterm modifier-key level 4.
- Kitty event-type formatting (`modifier:event-type`) is implemented in the
  encoder for rich host events, but flag `2` is not advertised until a host can
  truthfully provide the complete required lifecycle metadata.
- Kitty alternate-key formatting is implemented for host-supplied shifted and
  base-layout scalars, but flag `4` is not advertised until a host can provide
  those layout values truthfully.
- Kitty associated-text formatting is implemented for validated host-owned
  text and writes codepoints directly to the reusable CSI buffer. Flag `16`
  remains unadvertised pending complete rich-host text/IME support.
- The active host session admits a subset of encoder-supported Kitty flags.
  Portable Swing sessions admit only flags `1` and `8`; a richer host must
  explicitly declare the complete metadata it can provide before enabling
  flags `2`, `4`, or `16`. Native rich-input adapters are deferred, so this
  limitation also applies to IntelliJ-hosted Swing sessions.

Not guaranteed yet:

- complete Kitty Keyboard Protocol support, including event types, alternate
  key values, associated text, and complete host lifecycle/layout metadata

### Xterm key resources

Resource controls use decimal IDs with independent modifier and format state:

| ID | Modifier resource | Accepted values | Modifier default | Format default |
| --- | --- | --- | --- | --- |
| 0 | modifyKeyboard | 0..15 | 0 | 0 |
| 1 | modifyCursorKeys | 0..4 | 2 | 0 |
| 2 | modifyFunctionKeys | 0..4 | 2 | 0 |
| 3 | modifyKeypadKeys | 0..4 | 0 | 0 |
| 4 | modifyOtherKeys | 0..3 | 0 | 0 |
| 6 | modifyModifierKeys | 0..4 | 0 | 0 |
| 7 | modifySpecialKeys | 0..4 | 0 | 0 |

Every format accepts 0 (original xterm report) or 1 (CSI-u). Format selection
changes the layout of extended reports; it does not enable reporting.
Resource 0 stores its own value: it does not overwrite the individual resources.
Its modifier mask belongs to xterm's legacy/VT220 keyboard profiles and does not
change KetraTerm's PC keyboard encoding.

- `CSI > id ; value m` sets modifier state; final `f` sets format state.
- Omitting the value, including an empty second field, restores that resource's
  default. Omitting both parameters restores the entire corresponding family.
- `CSI > id n` explicitly disables one modifier resource. Omitting the ID
  selects function keys (2). This is distinct from setting level 0.
- `CSI ? id m` queries modifier state; `CSI ? id g` queries format state.
  Replies are `CSI > id ; value m` and `CSI > id ; value f`.
- Queries accept a list of IDs and reply in request order, including duplicates.
  Missing IDs, empty fields, colon subparameters, numeric overflow, and excess
  parser capacity reject the whole query before any reply. Reserved ID 5 and
  unknown IDs are outside the response allowlist and remain silent; these
  protocols define no failure reply. Denying terminal responses suppresses both
  query families without suppressing state changes.
- Set/reset accepts at most two fields and disable at most one. Invalid syntax
  or out-of-range values preserve state. Colon modifier masks are not interpreted
  as ordinary resource setters.
- Explicit disable is stored as -1 in typed state. The reply uses 65535, matching
  xterm's unsigned serialization of its signed-short sentinel. Restore this state
  with `CSI > id n`; 65535 is not an accepted set level.
- Hard and soft reset restore both resource families. Family reset preserves
  other input modes, including Kitty flags.

Cursor/function levels control modified key formatting: 0 retains the old
first-parameter/SS3 form, 1 forces CSI, 2 puts the modifier in the second
parameter, and 3 adds the private `>` prefix. Unmodified keys retain their
normal/application forms. Explicit cursor disable omits the modifier; explicit
function disable uses Shift/Ctrl to select higher function numbers (+12/+24).
Editing keys (Insert, Delete, Page Up/Down) retain their traditional numbered
CSI reports at levels -1..3.

Level 4 selects extended reports for cursor/editing, function, keypad, modifier,
and special-key families. Both modified and unmodified presses/repeats report;
release is suppressed. Format 0 writes `CSI 27 ; modifier ; code ~`; format 1
writes `CSI code ; modifier u`. Codes follow xterm's standard X11-to-Unicode
mapping, including control aliases and private-use values; Kitty's functional
key numbers must never be substituted. Other-key levels retain the existing
modifyOtherKeys rules.

A nonzero active Kitty mode takes precedence over xterm encoding. Committed
text-only events retain the literal UTF-8 contract above. Input reads all
resources from one primitive mode snapshot and writes into reusable byte
scratch; no map, snapshot object, or formatted string is created per key.

#### Compatibility evidence

The contract was checked against the [xterm control reference](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html),
[xterm resource manual](https://invisible-island.net/xterm/manpage/xterm.html),
and upstream [input handling](https://github.com/ThomasDickey/xterm-snapshots/blob/master/input.c),
[control handling](https://github.com/ThomasDickey/xterm-snapshots/blob/master/charproc.c),
and [key mapping](https://github.com/ThomasDickey/xterm-snapshots/blob/master/keysym2ucs.c)
on 2026-09-26. The documented reset-all semantics are used for all seven
resources; upstream's control loop currently resets only IDs 1..5.

Peer implementations are narrower:
[Ghostty's stream handler](https://github.com/ghostty-org/ghostty/blob/main/src/terminal/stream.zig)
handles a subset of modifier settings and documents approximate disable behavior;
[xterm.js's input handler](https://github.com/xtermjs/xterm.js/blob/master/src/common/InputHandler.ts)
does not register these xterm modifier/format query controls. Their subsets are
not a reliable definition of the full resource protocol.

Capability boundaries and deferred host metadata remain in the
[feature map](../../docs/terminal-feature-map.md#6-input-encoding--event-reporting) and
[gap map](../../docs/terminal-feature-gap-map.md#input-module-gaps).


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
- SGR-Pixels mouse (`?1016`) when the host supplies one-based pixel coordinates
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

- xterm highlight mouse tracking (`?1001`)
- UI pointer capture, drag threshold, or double/triple-click interpretation

## Paste And Focus Contract

Paste encoding reads one mode snapshot and one input policy per event. The
`pasteControlPolicy` selects `PasteControlPolicy.PRESERVE` (the default) or
`STRIP_C0_EXCEPT_TAB_CR_LF`. Filtering removes only C0 controls other than TAB,
CR, and LF; it does not remove DEL or the C1 range.

When bracketed paste is enabled, the encoder wraps the transformed payload in
`ESC[200~` and `ESC[201~`. Protection is mandatory for both control policies:

- remaining ESC (U+001B) becomes U+241B, the visible escape symbol;
- remaining ETX (U+0003) becomes U+2403, because some receivers treat it as an
  interrupt even inside bracketed paste;
- CSI (U+009B) becomes the six literal ASCII characters `\u009b`.

The protected payload contains no ESC, ETX, or U+009B characters. Transformations
cannot concatenate fragments into another delimiter. Classification operates on
Unicode code points, never arbitrary UTF-8 continuation bytes. Literal spellings
such as `\x1b` are ordinary text. Other controls retain their policy-defined
behavior; bracketed payloads preserve TAB and original line endings.

For unbracketed paste, the control policy applies without the framing replacements.
The independent `pasteLineEndingPolicy` preserves input or canonicalizes CRLF,
lone CR, and LF to LF, CR, or CRLF. Each CRLF pair is one line boundary. Local PTY
hosts select CR so each pasted line boundary has Enter-key semantics.

All paths replace unpaired UTF-16 surrogates with U+FFFD. Valid scalar values,
including supplementary characters and combining sequences, retain their UTF-8
encoding. Empty bracketed pastes still emit both markers.

Unchanged text uses the sink's direct UTF-8 path. Transformed text is emitted in
bounded chunks through the encoder's existing reusable output buffer, also used
for completion deletions. No clipboard-sized intermediate string or byte array is
created. Pending buffered bytes are discarded on write failure; already accepted
transport bytes cannot be rolled back. Session serialization keeps an entire
paste or text replacement ordered with other input and terminal responses.

### Paste API Migration

`PasteControlPolicy` replaces `PasteSanitizationPolicy`; callers use
`pasteControlPolicy` and `TerminalSession.setPasteControlPolicy`. Replace `RAW`
with `PRESERVE`. Replace `NORMALIZE_LINE_ENDINGS` with an explicit
`pasteLineEndingPolicy`: retain an existing canonicalizing policy, or select
`LINE_FEED` if the previous host policy was `PRESERVE`. Live content-policy updates
preserve host newline and keyboard choices.

Standalone TOML and IntelliJ XML retain their persisted setting keys. The accepted
canonical choices are `preserve` and `strip-c0`; legacy `raw` and
`normalize-line-endings` migrate to `preserve`. Both shipped products already use
the local PTY CR policy, so this migration preserves their newline behavior.

### Focus Reports

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
sink itself. The terminal actor or host host layer owns that ordering.

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

Allocation verification for `TerminalKeyResourceBenchmark` (2026-09-26):
JMH 1.37 on JBR 25.0.4.1, one fork, two 1-second warmups, three 1-second
measurements, and the GC profiler. Across IDs 1/2/3/6/7 in formats 0/1,
encoding measured at most 0.000195 B/op and paired modifier/format queries
at most 0.000230 B/op, with no collections. Events and output storage were
reused. These figures are consistent with no steady-state per-operation
allocation; they do not measure event construction, transport writes, or
whole-frame rendering. Rerun the benchmark with longer iterations for
throughput comparisons.

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
- exact and hostile-length text-replacement ordering
- real core packed mode host for input-facing modes
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
- local PTY hosts may configure Return to send CR even when LNM is active,
  avoiding an extra newline when the host line discipline already translates
  terminal carriage returns

## Pre-1.0 Change Surface

Likely to evolve before 1.0:

- broader modified-key protocol support
- optional Kitty keyboard protocol as a separate encoder path
- paste policy surface if host host needs stricter security defaults
- a future `:ketraterm-core-api` split for input-facing mode reads

The runtime semantics described in this document are the current intended
contract for integrating UI adapters, `:ketraterm-core`, and host-bound terminal
input.
