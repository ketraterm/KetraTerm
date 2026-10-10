# ANSI Finite-State Machine & CSI Dispatch Specification

This document describes parser routing and collection contracts. Protocol support
is tracked in the [feature map](../../docs/terminal-feature-map.md); the classes
described here are internal implementation details.

## 1. The State Machine Architecture

`TerminalParser` coordinates two routes: printable UTF-8 decoding and the ANSI
state machine. A pending printable UTF-8 scalar takes priority. Once resolved,
normal byte routing resumes, including bounded replay of a non-continuation byte.

The ANSI route separates three responsibilities:

1. [ByteClass](../src/main/kotlin/io/github/ketraterm/parser/ansi/ByteClass.kt)
   maps `0x00..0x7f` into 15 control/ASCII classes and `0x80..0xff` into the
   `UTF8_PAYLOAD` routing class. Raw C1 bytes are not 8-bit control introducers.
2. [AnsiStateMachine](../src/main/kotlin/io/github/ketraterm/parser/ansi/AnsiStateMachine.kt)
   performs a flat `IntArray` lookup for the current state and routing class.
   Each entry packs an action in the low eight bits and the next state above it.
   There are 16 states and 32 reserved columns per state, with 16 active routing
   classes. Lookup uses `(state shl 5) or byteClass`.
3. [ActionEngine](../src/main/kotlin/io/github/ketraterm/parser/ansi/ActionEngine.kt)
   updates reusable sequence state and delegates semantic operations to the
   dispatchers or printable processor.

Non-ASCII bytes are printable ingress only in `GROUND`. OSC/DCS bodies collect
them as opaque bytes; ignored strings discard them. In active ESC/CSI grammar,
they terminate the incomplete sequence without dispatching a stale final byte.

The matrix lookup does fixed work per byte. That does not make an entire parser
call allocation-free: completed metadata commands may decode strings, and
explicitly permitted larger OSC bodies may grow collection storage.

## 2. CSI Command Signatures

CSI dispatch distinguishes the final byte, private marker, and ordered
intermediates. [CsiSignature](../src/main/kotlin/io/github/ketraterm/parser/ansi/CsiSignature.kt)
packs them into a primitive `Long`:

| Bits | Meaning |
|---|---|
| `0..7` | Final byte |
| `8..15` | Private marker, or zero |
| `16..47` | Up to four intermediate bytes, low to high |
| `48..51` | Intermediate count |
| `52..63` | Zero |

Parameters are separate from this key. A reusable `IntArray` holds up to 32
fields; `-1` represents an omitted field. A bit mask records fields opened by a
colon. Opening a 33rd field rejects the entire CSI before dispatch. Decimal
overflow saturates the retained value to `Int.MAX_VALUE` and records saturation
for dispatchers that require exact values. The intermediate collector retains
the first four bytes; additional intermediate bytes are dropped.

<a id="3-high-speed-dispatch-table"></a>

## 3. Dispatch table

[GeneratedCsiDispatchTable](../src/main/kotlin/io/github/ketraterm/parser/ansi/GeneratedCsiDispatchTable.kt)
stores sorted structural signatures in a `LongArray` and corresponding command
IDs in an `IntArray`. Binary search selects the command; the dispatcher then
interprets its parameters and emits semantic sink operations. An unmatched key
does not fall back to a final-byte-only command.

Structural actions flush pending printable text before command dispatch. This
preserves callback order and prevents later text from extending a grapheme across
a structural operation.

## 4. Parser Recovery and Security Safeguards

OSC ends with BEL or `ESC \`; DCS ends with `ESC \`. CAN/SUB cancel collected
strings without dispatch. Ordinary C0 controls are ignored in OSC; in a DCS
body they are retained as payload rather than executed. DEL is ignored. String
escape states handle a split `ESC \` without confusing it with a standalone ESC
command.

[ControlStringPolicy](../src/main/kotlin/io/github/ketraterm/parser/ansi/ControlStringPolicy.kt)
sets collection limits before decoding. Limits count stored bytes, including
family headers and separators, excluding introducers, terminators, and controls
ignored by that string's rules:

| Collection | Limit |
|---|---|
| Ordinary OSC envelopes and DCS `+q` | 4096 bytes |
| OSC dynamic colors `10`, `11`, `12` | 256 bytes |
| DCS `$q` | 64 bytes |
| Custom OSC | 4096 bytes by default, independently configurable |
| Eligible OSC 52 writes | Ordinary bound or a larger checked limit from the host's decoded-byte budget |

Unsupported families stop collecting their bodies after identification. Overflow
stops further storage and rejects the completed command; OSC 8 also ends an
existing hyperlink to avoid retaining stale link state. Eligible larger OSC
collection grows on demand. Temporary storage is released on completion,
cancellation, overflow, reset, EOF, and custom callback failure.

Collection limits do not authorize clipboard changes or terminal replies. The
sink's owning layers validate complete requests and apply current permissions.
`endOfInput` discards unfinished OSC/DCS commands. `reset` discards parser-owned
state without emitting sink commands.

## Validation

`AnsiStateMachineTest` checks routing, and `ActionEngineTest` checks collection and
mutation invariants. `ControlStringPayloadPolicyTest`, `CustomOscHandlerTest`,
`CustomOscLimitTest`, and `TerminalParserTest` cover exact bounds, chunking,
cancellation, ordering, and recovery. Run `./gradlew :ketraterm-parser:test` from
the repository root; semantic changes also require the affected host tests.
