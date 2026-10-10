# KetraTerm Input

`ketraterm-input` converts platform-neutral keyboard, mouse, paste, focus, and
text-replacement events into bytes for a terminal host. Encoding uses the current
core input modes and an explicit `TerminalInputPolicy`.

The module depends on `ketraterm-core` and `ketraterm-protocol`. It has no UI,
transport, or clipboard dependency. For an application with a connector, use
[TerminalSession](../ketraterm-session/README.md) to order input with terminal
responses and transport writes.

## Direct encoding

Use `TerminalInputEncoders.create` when you provide the serialization and output
sink yourself. The following function demonstrates mode-dependent encoding; the
caller supplies a `TerminalHostOutput` that consumes or copies bytes before each
write returns.

```kotlin
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.input.TerminalInputEncoders
import io.github.ketraterm.input.event.TerminalKey
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.input.policy.TerminalInputPolicy
import io.github.ketraterm.protocol.host.TerminalHostOutput

fun encodeExample(output: TerminalHostOutput) {
    val terminal = TerminalBuffers.create(width = 80, height = 24)
    val encoder = TerminalInputEncoders.create(
        inputState = terminal,
        output = output,
        policy = TerminalInputPolicy(),
    )

    encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.UP)) // ESC [ A
    terminal.setApplicationCursorKeys(true)
    encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.UP)) // ESC O A

    encoder.encodeKey(TerminalKeyEvent.codepoint('é'.code)) // UTF-8
    encoder.encodeKey(TerminalKeyEvent.text("e\u0301")) // Committed text
    terminal.setBracketedPasteEnabled(true)
    encoder.encodePaste(TerminalPasteEvent("hello")) // ESC [ 200 ~ hello ESC [ 201 ~
}
```

The encoder reads one coherent `TerminalInputState.getInputModeBits()` value per
event. A custom mode source must implement that method; decode captured values
with the `TerminalInputState` helpers rather than interpreting bit positions.
Calls and `setInputPolicy` updates on the default encoder must be serialized.
It reuses scratch arrays, so the sink must not retain borrowed byte ranges.
Sink failures propagate and may leave a partially written operation. The encoder
does not own or close the sink.

## Events and policy

- Use `TerminalKeyEvent.key` for special keys and `codepoint` for a printable
  Unicode scalar. `text` represents committed text without physical-key identity;
  it does not receive paste framing or modifier transformations in text modes.
- Mouse cell coordinates are zero-based. Optional pixel coordinates are used
  only by SGR-Pixels encoding. Coordinate conversion and gesture routing belong
  to the UI adapter.
- `TerminalTextReplacementEvent` emits Delete actions, Backspace actions, then
  replacement text through paste policy. Counts describe editor actions, not
  string indices.
- `TerminalInputPolicy()` defaults to DEL Backspace, ESC-prefixed Meta/Alt,
  suppression of unsupported modified keys and out-of-range legacy mouse
  coordinates, and preserved paste controls and line endings. Bracketed paste
  always protects its delimiters.

The key-event factories and encoder factory expose Java static methods with
default-argument overloads. For custom session encoding, supply a
`TerminalInputEncoderFactory` that creates independent instances bound to the
session's mode source and sink.

## Further reading

- [Input contract](docs/terminal-input-contract.md): validation, policies,
  threading, and clipboard-reply ownership.
- [Wire encoding reference](docs/keyboard-mouse-encoding.md): representative
  keyboard and mouse sequences.
- [Module structure](Module.md): package responsibilities and verification.
- [Feature map](../docs/features/terminal.md#keyboard-mouse-and-paste):
  supported protocols and host capability boundaries.
