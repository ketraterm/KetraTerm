# Module ketraterm-input

## KetraTerm Input (`:ketraterm-input`)

The `ketraterm-input` module is the platform-agnostic, host-bound input encoding engine for **KetraTerm Terminal**. It converts UI-level, platform-neutral events—such as keyboard presses, text paste, window focus transitions, and mouse pointer actions—into standardized ANSI/DEC/xterm byte sequences written to the terminal host's input stream.

The module is engineered to be highly performant, allocation-minimal, and robust, with explicit support for modern shell and TUI protocols (e.g., bracketed paste, focus reporting, SGR mouse tracking, and xterm `modifyOtherKeys`).

---

## Upstream Dependencies
- **`:ketraterm-protocol`** (for shared mouse, keyboard, and mode constants).
- **`:ketraterm-core`** (for reading terminal input state and modes).

---

## Architectural Scope & Flow

To preserve a strict separation of concerns, `ketraterm-input` operates under clear design constraints:

```text
UI Adapter  --->  Terminal Event Loop Actor  --->  TerminalInputEncoder  --->  TerminalHostOutput  --->  PTY stdin
                                                                                       ^
Parser/Core Replies  ----------------------------------------------------------------+
```

### What the Module Owns
- **Platform-Neutral Models**: Normalized representation of [TerminalKeyEvent](src/main/kotlin/io/github/ketraterm/input/event/TerminalKeyEvent.kt), [TerminalPasteEvent](src/main/kotlin/io/github/ketraterm/input/event/TerminalPasteEvent.kt), [TerminalFocusEvent](src/main/kotlin/io/github/ketraterm/input/event/TerminalFocusEvent.kt), and [TerminalMouseEvent](src/main/kotlin/io/github/ketraterm/input/event/TerminalMouseEvent.kt).
- **ANSI/DEC Encoding**: Translating events into byte sequences based on the terminal's active modes.
- **Input Policy**: Decision-making policies ([TerminalInputPolicy](src/main/kotlin/io/github/ketraterm/input/policy/TerminalInputPolicy.kt)) for handling backspace bytes, meta keys, and paste sanitization.

### What the Module Does NOT Own
- **Terminal Output Parsing**: The input module never parses byte streams or terminal replies.
- **State Mutation**: It never directly mutates core screen buffers, cursors, or style attributes.

---

## Sub-Documentation

For specifications on the input API contract and wire formats:
* [terminal-input-contract.md](docs/terminal-input-contract.md) - Input event definitions, validation criteria, modifiers, and thread-safety limits.
* [keyboard-mouse-encoding.md](docs/keyboard-mouse-encoding.md) - Escape sequence specifications for cursor modes, modifyOtherKeys, CSI-u, and mouse coordinates (SGR/legacy/URXVT).

---

## How to Use

The following example shows how to instantiate the `DefaultTerminalInputEncoder` and encode a keyboard key press event:

```kotlin
import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.core.api.TerminalInputState
import io.github.ketraterm.input.impl.DefaultTerminalInputEncoder
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalKey
import io.github.ketraterm.input.event.TerminalModifiers
import io.github.ketraterm.input.policy.TerminalInputPolicy
import io.github.ketraterm.protocol.host.TerminalHostOutput

fun main() {
    // 1. Create a read-only view of the terminal modes/state
    val inputState = object : TerminalInputState {
        override val applicationCursorKeys: Boolean get() = false
        override val applicationKeypad: Boolean get() = false
        override val bracketedPaste: Boolean get() = true
        override val focusReporting: Boolean get() = false
        override val mouseTrackingMode: Int get() = 0 // NONE
        override val mouseEncodingMode: Int get() = 2 // SGR
        override val modifyOtherKeysMode: Int get() = 0
        override val formatOtherKeysMode: Int get() = 0
    }

    // 2. Define the output sink (where encoded bytes are sent)
    val outputSink = object : TerminalHostOutput {
        override fun writeByte(byte: Int) {
            print(byte.toChar())
        }
        override fun writeBytes(bytes: ByteArray, offset: Int, length: Int) {
            print(String(bytes, offset, length, Charsets.US_ASCII))
        }
        override fun writeAscii(text: String) {
            print(text)
        }
        override fun writeUtf8(text: String) {
            print(text)
        }
    }

    // 3. Create the encoder
    val encoder: TerminalInputEncoder = DefaultTerminalInputEncoder(
        state = inputState,
        output = outputSink,
        policy = TerminalInputPolicy.DEFAULT
    )

    // 4. Encode a non-printable key press (e.g. Arrow Up)
    val upEvent = TerminalKeyEvent(
        key = TerminalKey.ARROW_UP,
        codepoint = 0,
        modifiers = TerminalModifiers.NONE
    )
    encoder.key(upEvent) // Sends "CSI A" to the output sink
}
```
