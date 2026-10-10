# Terminal Input Contract

This contract describes the public behavior of `ketraterm-input`. It covers
normalized events, mode-dependent encoding, policy, byte ownership, and failures.
[Protocol support and host capability limits](../../docs/terminal-feature-map.md#6-input-encoding--event-reporting)
and [deferred work](../../docs/terminal-feature-gap-map.md#input-module-gaps)
remain in the canonical maps.

## Public API Surfaces

`TerminalInputEncoders.create(inputState, output, policy)` returns a
`TerminalInputEncoder` with `encodeKey`, `encodePaste`, `encodeTextReplacement`,
`encodeFocus`, `encodeMouse`, and `setInputPolicy` methods. Encoding is synchronous
and returns `Unit`; a valid event can intentionally emit no bytes.

Events implement the sealed `TerminalInputEvent` vocabulary. Policy is an
immutable `TerminalInputPolicy` value. `TerminalClipboardReply` prepares owned
OSC 52 response payloads separately from ordinary input.

A session accepts a `TerminalInputEncoderFactory` for custom encoding. It calls
that factory twice, for independent admission and bulk encoders. Return fresh
instances with no shared mutable encoding state: their calls can run concurrently,
although calls on each instance are serialized. Construction must not read modes,
emit bytes, start jobs, or retain external I/O. Read the supplied mode source and
write only through the supplied sink during encoding calls. Policy updates must
apply before returning, or fail before changing state.

## Event Contract

Invalid events fail at construction with `IllegalArgumentException`. Valid events
follow the active protocol and explicit suppression/fallback policy.

| Event | Construction and meaning |
| --- | --- |
| `TerminalKeyEvent.key(key, modifiers, type)` | A non-printable key such as `UP`, `ENTER`, or `BACKSPACE`. |
| `TerminalKeyEvent.codepoint(codepoint, ...)` | A Unicode scalar produced by a known text-producing key. |
| `TerminalKeyEvent.text(associatedText, modifiers, type)` | Committed text with no physical-key identity. |
| `TerminalPasteEvent(text)` | Clipboard or other pasted text; empty text is accepted. |
| `TerminalTextReplacementEvent(deleteAfterCursorCount, deleteBeforeCursorCount, replacementText)` | Delete actions, then Backspace actions, then optional paste. Counts are non-negative editor actions, not UTF-16 units or code points. |
| `TerminalFocusEvent(focused)` | A focus transition; reporting depends on the current mode. |
| `TerminalMouseEvent(...)` | A press, release, motion, or wheel event with zero-based coordinates. |

Key events contain exactly one of `key` or `codepoint`. The scalar path rejects
surrogates, values outside `0..0x10FFFF`, C0 controls, and DEL. Special control
keys use `TerminalKey`. `NO_CODEPOINT` is `-1`; `TEXT_ONLY_CODEPOINT` is `0` and
requires non-empty associated text. Associated text rejects malformed UTF-16,
C0/C1 controls, and DEL.

Known printable-key events may include `unshiftedCodepoint`,
`shiftedCodepoint`, `baseLayoutCodepoint`, and `associatedText`. Scalars must be
valid; a shifted scalar requires the Shift modifier. These values describe
physical-key identity and produced text separately. Leave unknown values at
their defaults. Special-key and text-only events cannot carry physical-key
scalar metadata. Host adapters must report `PRESS`, `REPEAT`, and `RELEASE`
truthfully rather than inventing lifecycle information.

Modifier masks contain only `TerminalModifiers` bits. Combine them with bitwise
OR. They are not wire parameters: xterm modifiers map Shift/Alt/Ctrl and a merged
Super/Hyper/Meta bit to a one-based value; lock bits are omitted. Kitty parameters
retain all eight modifier bits and add one.

Mouse cell coordinates must be non-negative. Pixel coordinates accept `-1` for
missing data or a non-negative value. Press requires a concrete non-wheel button;
wheel events require a wheel direction. Motion and release may use `NONE`, but
SGR-family encoding suppresses a release without button identity.

## Mode-State Contract

The factory implementation reads `TerminalInputState.getInputModeBits()` once
per event. An entire text replacement uses that same snapshot for Delete,
Backspace, and paste phases. Custom state implementations must return one
coherent mode word. Interpret it with `TerminalInputState` helpers, not raw bit
positions.

This word includes cursor/keypad modes, newline mode, DECBKM, bracketed paste,
focus reporting, mouse tracking/encoding, xterm key resources, and Kitty flags.
A coherent mode read does not synchronize grid reads or terminal mutation.

## Ordering And Threading

The default encoder is not thread-safe. Serialize encoding and policy updates,
and order its output with other producers such as parser/core responses. The
encoder owns reusable scratch storage and no transport lifecycle.

`TerminalHostOutput` must consume or copy each supplied range before returning;
the encoder may immediately reuse it. Sink exceptions propagate. Already written
bytes cannot be rolled back, so an operation may be partially emitted. Buffered
paste and replacement bytes are cleared after either success or failure and do
not leak into the next event.

Use `TerminalSession` when session admission and outbound ordering are required.
Its `submitInput` methods admit semantic events or bounded compound input;
`submitBytes` admits copied exact bytes. The encoder interface itself provides
neither admission results nor transport completion. A session orders a whole
paste, text commit, or text replacement with other input and terminal responses.
See the [session admission contract](../../ketraterm-session/docs/session-concurrency-locks.md#admission-and-ordering).

## Keyboard Contract

Unmodified scalar input normally emits UTF-8. Legacy encoding provides explicit
Ctrl mappings, mode-dependent special/keypad sequences, and policy-controlled
Alt/Meta prefixes. Unsupported modifier combinations suppress by default;
`UnsupportedModifiedKeyPolicy.EMIT_UNMODIFIED` selects a fallback instead.

Legacy Backspace defaults to DEL. `BackspacePolicy.BACKSPACE` selects BS until
an explicit DECBKM override: DECSET 67 selects BS and DECRST 67 selects DEL,
until terminal reset. Enter normally sends CR; with LNM active it adds LF unless
`EnterNewLineModePolicy.SEND_CR` is selected. Extended keyboard reports can
represent these keys independently of their legacy byte choices.

PF1-PF4 are distinct from physical F1-F4. Application cursor mode affects
unmodified cursor keys; application keypad mode affects normalized keypad keys.
The [wire reference](keyboard-mouse-encoding.md) gives representative sequences.

`TerminalKeyEvent.text(...)` emits its entire associated text as UTF-8 for press
and repeat in legacy modes and Kitty modes without report-all flag `8`. Modifiers
are metadata already accounted for by the host's text production; they do not
trigger Ctrl mapping, ESC prefixes, or modifyOtherKeys transformation. Release
emits nothing in these text modes. This is committed text, so paste framing and
paste transformations do not apply. The marker `0` never becomes a NUL byte.

In Kitty report-all mode, text-only events require associated-text flag `16` and
use CSI-u key code `0`; without flag `16`, the event is suppressed. Nonzero Kitty
flags take precedence over xterm resource encoding for known keys. Event-type
flag `2` encodes lifecycle metadata, flag `4` uses supplied alternate-key values,
and flag `16` uses validated associated text. A host session must admit only the
flags for which its adapter supplies complete metadata; encoder capability alone
is not a host capability declaration. Without event-type reporting, repeat uses
press encoding and release is suppressed.

### Xterm key resources

Modifier and format state are independent for each decimal resource ID:

| ID | Modifier resource | Accepted modifier levels | Modifier default | Format default |
| --- | --- | --- | --- | --- |
| 0 | modifyKeyboard | 0..15 | 0 | 0 |
| 1 | modifyCursorKeys | 0..4 | 2 | 0 |
| 2 | modifyFunctionKeys | 0..4 | 2 | 0 |
| 3 | modifyKeypadKeys | 0..4 | 0 | 0 |
| 4 | modifyOtherKeys | 0..3 | 0 | 0 |
| 6 | modifyModifierKeys | 0..4 | 0 | 0 |
| 7 | modifySpecialKeys | 0..4 | 0 | 0 |

Every format accepts `0` (original xterm report) or `1` (CSI-u). Format selection
changes extended-report layout without enabling reporting. Resource 0 stores its
own legacy/VT220 keyboard mask; it does not overwrite other resources or change
KetraTerm's PC keyboard encoding.

These terminal controls are parsed and applied by parser/host/core; input reads
the resulting state:

- `CSI > id ; value m` sets modifier state; final `f` sets format state.
  An omitted or empty value restores that resource's default. Omitting both
  parameters restores the corresponding family.
- `CSI > id n` explicitly disables a modifier resource. An omitted ID selects
  function keys (2). Disable is distinct from level 0: typed state uses `-1`,
  and queries serialize that sentinel as `65535`. The set control does not
  accept `65535`.
- `CSI ? id m` queries modifiers; `CSI ? id g` queries formats. Replies use
  `CSI > id ; value m` and `CSI > id ; value f`, respectively. A valid ID list
  replies in request order, including duplicates.
- Malformed queries are rejected before any reply. Reserved ID 5 and unknown
  IDs are outside the reply allowlist and remain silent. These controls define
  no failure reply. Denying terminal responses suppresses queries' replies
  without suppressing state changes.
- Set/reset accepts at most two fields; disable accepts at most one. Invalid
  syntax, colon subparameters, overflow, and out-of-range levels preserve state.
  Hard/soft terminal reset restores both families. A family reset preserves
  unrelated input modes, including Kitty flags.

For modified cursor/function keys, level 0 retains the old first-parameter/SS3
form, level 1 forces CSI, level 2 uses a second modifier parameter, and level 3
adds `>`. Unmodified keys retain normal/application forms. Explicit cursor
disable omits the modifier. Explicit function disable uses Shift/Ctrl to select
higher function numbers (+12/+24). Editing keys retain numbered CSI reports at
levels `-1..3`. PF1-PF4 retain their modified CSI reports independently of the
function resource; keypad resource 3 selects their extended reports.

Level 4 selects extended reports for cursor/editing, function, keypad, modifier,
and special-key families. Both unmodified and modified presses/repeats report;
release is suppressed. Format 0 emits `CSI 27 ; modifier ; code ~`; format 1
emits `CSI code ; modifier u`. Codes use xterm identities, not Kitty functional
key numbers. `modifyOtherKeys` uses its separate levels `0..3`.

The [xterm control reference](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html)
and [resource manual](https://invisible-island.net/xterm/manpage/xterm.html)
provide protocol background. KetraTerm's reset-all controls apply to all seven
listed resources.

## Mouse Contract

Tracking and coordinate encoding are separate modes. Unknown tracking or
encoding values emit no mouse report.

| Tracking mode | Reported events |
| --- | --- |
| `NONE` | None. |
| `X10` | Press only. |
| `NORMAL` | Press, release, and wheel. |
| `BUTTON_EVENT` | Normal events and motion with a concrete button. |
| `ANY_EVENT` | All normalized mouse events, including no-button motion. |

Cell coordinates become one-based at the wire boundary. SGR-Pixels uses pixel
coordinates instead; each missing pixel coordinate independently falls back to
its corresponding cell coordinate. Decimal encodings accept the full
non-negative `Int` range and convert `Int.MAX_VALUE` to `2147483648` safely.

| Encoding | Coordinate contract |
| --- | --- |
| `DEFAULT` | Legacy bytes; one-based maximum 223. Suppress out-of-range events by default or clamp with `MouseCoordinateLimitPolicy.CLAMP_TO_MAX`. |
| `UTF8` | UTF-8 extended reports; suppress if either one-based coordinate exceeds 2015. |
| `SGR` | Decimal cell coordinates; preserve button identity on release with lowercase `m`. |
| `SGR_PIXELS` | SGR framing with decimal pixel coordinates and the fallback above. |
| `URXVT` | Decimal cell coordinates with legacy button packing. |

Legacy, UTF-8, and URXVT releases use button code 3. Mouse modifiers pack Shift,
Alt, and Ctrl; other modifier bits are ignored. An event represents one wheel
report, not a fractional wheel delta. The UI adapter owns gesture interpretation,
pixel-to-cell conversion, and any decision to handle a gesture locally.

## Paste And Focus Contract

`PasteControlPolicy.PRESERVE` is the default.
`STRIP_C0_EXCEPT_TAB_CR_LF` removes C0 controls except TAB, CR, and LF; it does
not strip DEL or C1. When bracketed paste is active, the transformed payload is
wrapped in `ESC[200~` and `ESC[201~`, including for an empty paste.

Both control policies protect bracketed framing. After optional filtering:

- ESC (`U+001B`) becomes `U+241B`.
- ETX (`U+0003`) becomes `U+2403`.
- CSI (`U+009B`) becomes the six literal ASCII characters `\u009b`.

The protected payload contains none of those three control characters. Protection
classifies Unicode scalars, not UTF-8 continuation bytes. Removed characters
cannot join fragments into another delimiter; literal text such as `\x1b`
remains ordinary text. Bracketed payloads preserve original line endings.

Unbracketed paste applies the chosen control policy without those framing
replacements. `PasteLineEndingPolicy` independently preserves line endings or
canonicalizes CRLF, lone CR, and LF to LF, CR, or CRLF. Each CRLF pair is one
boundary. Local PTY hosts select CR so pasted line boundaries have Enter-key
semantics. All paste paths replace unpaired UTF-16 surrogates with `U+FFFD`.
Valid supplementary scalars and combining sequences retain their UTF-8 bytes.

Unchanged text uses the sink's direct UTF-8 path. Transformed paste and repeated
deletion output use bounded reusable buffers rather than a clipboard-sized
intermediate string or byte array. Text replacement applies normal paste policy
to its insertion phase.

### Paste API Migration

See the [library changelog](../../CHANGELOG.md) for the `PasteControlPolicy` migration.

### Focus Reports

Focus events emit `CSI I` for focus gained and `CSI O` for focus lost only when
focus reporting is enabled in the captured mode snapshot.

## Host Output Contract

`TerminalHostOutput` is synchronous from the caller's perspective. `writeByte`
receives `0..255`; `writeBytes` receives an explicit offset/length range that must
be consumed or copied before return. `writeAscii` is for ASCII text; implementations
should reject non-ASCII input rather than change wire bytes. `writeUtf8` encodes
Unicode text. The sink itself does not guarantee ordering across independent
producers.

## Clipboard read replies

`TerminalClipboardReply.prepare(selection, text, maxDecodedBytes, maxWireBytes)`
validates selectors, scalar UTF-16, the raw UTF-8 limit, and the complete wire-byte
budget before emitting anything. The string-selector overload normalizes valid
selectors and returns `null` for unknown ones. Malformed text or exceeded bounds
also returns `null`; negative budgets throw `IllegalArgumentException`.

Preparation retains an owned Base64 byte array and clears the temporary UTF-8
array. `byteCount` includes the OSC introducer, selectors, payload, and ST.
`writeTo(output)` emits a complete OSC 52 reply with padded Base64 and seven-bit
ST, using payload ranges no larger than 16 KiB. Modes and paste transformations
do not apply.

The caller owns whole-frame ordering and must close the reply after writing or
discard. Close clears retained payload storage; subsequent writes throw
`IllegalStateException`. Closing must not race a writer. This cleanup cannot
erase JVM strings or platform clipboard data. Session owns authorization,
deadlines, and transport commitment; input does not read the clipboard.
