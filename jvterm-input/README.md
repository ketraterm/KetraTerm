# JvTerm Input (`:jvterm-input`)

The `jvterm-input` module is the platform-agnostic, host-bound input encoding engine for JvTerm Terminal. It converts UI-level, platform-neutral events—such as keyboard presses, text paste, window focus transitions, and mouse pointer actions—into standardized ANSI/DEC/xterm byte sequences written to the terminal host's input stream.

The module is engineered to be highly performant, allocation-minimal, and robust, with explicit support for modern shell and TUI protocols (e.g., bracketed paste, focus reporting, SGR mouse tracking, and xterm `modifyOtherKeys`).

---

## Upstream Dependencies
- **`:jvterm-protocol`** (for shared mouse, keyboard, and mode constants).
- **`:jvterm-core`** (for reading terminal input state and modes).

---

## Architectural Scope & Flow

To preserve a strict separation of concerns, `jvterm-input` operates under clear design constraints:

### What the Module Owns
- **Platform-Neutral Models**: Normalized representation of [`TerminalKeyEvent`](./src/main/kotlin/input/event/TerminalKeyEvent.kt), [`TerminalPasteEvent`](./src/main/kotlin/input/event/TerminalPasteEvent.kt), [`TerminalFocusEvent`](./src/main/kotlin/input/event/TerminalFocusEvent.kt), and [`TerminalMouseEvent`](./src/main/kotlin/input/event/TerminalMouseEvent.kt).
- **ANSI/DEC Encoding**: Translating events into byte sequences based on the terminal's active modes.
- **Scratch Buffers**: Reusable, allocation-conscious scratch buffers ([`InputScratchBuffer`](./src/main/kotlin/input/impl/InputScratchBuffer.kt)) for generated terminal input sequences.
- **Input Policy**: Decision-making policies ([`TerminalInputPolicy`](./src/main/kotlin/input/policy/TerminalInputPolicy.kt)) for handling backspace bytes, meta keys, and paste sanitization.

### What the Module Does NOT Own
- **Terminal Output Parsing**: The input module never parses byte streams or terminal replies.
- **State Mutation**: It never directly mutates core screen buffers, cursors, or style attributes.
- **UI Event Conversions**: It is not responsible for platform-specific keyboard/mouse listeners or converting mouse pixel coordinates into cell coordinates.

```text
UI Adapter  ───►  Terminal Event Loop Actor  ───►  TerminalInputEncoder  ───►  TerminalHostOutput  ───►  PTY stdin
                                                                                      ▲
Parser/Core Replies  ────────────────────────────────────────────────────────────────┘
```

---

## 🔗 How to Use

The following example shows how to instantiate the `DefaultTerminalInputEncoder` and encode a keyboard key press event:

```kotlin
import io.github.jvterm.input.api.TerminalInputEncoder
import io.github.jvterm.input.api.TerminalInputState
import io.github.jvterm.input.impl.DefaultTerminalInputEncoder
import io.github.jvterm.input.event.TerminalKeyEvent
import io.github.jvterm.input.event.TerminalKey
import io.github.jvterm.input.event.TerminalModifiers
import io.github.jvterm.input.policy.TerminalInputPolicy
import io.github.jvterm.protocol.host.TerminalHostOutput
import io.github.jvterm.protocol.mouse.MouseEncodingMode
import io.github.jvterm.protocol.mouse.MouseTrackingMode

fun main() {
    // 1. Create a read-only view of the terminal modes/state
    val inputState = object : TerminalInputState {
        override val applicationCursorKeys: Boolean get() = false
        override val applicationKeypad: Boolean get() = false
        override val bracketedPaste: Boolean get() = true
        override val focusReporting: Boolean get() = false
        override val mouseTrackingMode: Int get() = MouseTrackingMode.NONE
        override val mouseEncodingMode: Int get() = MouseEncodingMode.SGR
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

---

## 🔗 How to Extend: Custom Input Policies

To customize backspace behavior, meta key behavior, or paste sanitization rules, construct a custom `TerminalInputPolicy`:

```kotlin
import io.github.jvterm.input.policy.TerminalInputPolicy
import io.github.jvterm.input.policy.BackspacePolicy
import io.github.jvterm.input.policy.MetaKeyPolicy
import io.github.jvterm.input.policy.PasteSanitizationPolicy

val myPolicy = TerminalInputPolicy(
    backspace = BackspacePolicy.SEND_BS, // Sends Ctrl+H (0x08) instead of DEL (0x7F)
    metaKey = MetaKeyPolicy.ESC_PREFIX,   // Prefixes Meta+key combinations with ESC (0x1B)
    pasteSanitization = PasteSanitizationPolicy.STRIP_C0 // Remove dangerous control characters on paste
)
```

---

## High-Performance and Low-Allocation Design

The hot path for terminal input must not trigger unnecessary JVM garbage collection. `jvterm-input` enforces strict performance rules:
* **Pre-Encoded Constants**: Common fixed sequences (e.g., arrow keys, default function keys, bracketed paste tokens) are pre-allocated as byte arrays and written directly.
* **[`InputScratchBuffer`](./src/main/kotlin/input/impl/InputScratchBuffer.kt)**: Sub-encoders reuse a singular, fixed-capacity scratch buffer owned by the outer `DefaultTerminalInputEncoder`. The scratch buffer formats integer decimals and UTF-8 multibyte codepoints in-place without producing `String` or `StringBuilder` instances.
* **Immediate Consumption**: The byte arrays written to `TerminalHostOutput.writeBytes(bytes, offset, length)` may refer to scratch-backed ranges. Consumers must synchronously process or copy these bytes, as the scratch buffer will be immediately cleared and reused for the subsequent event.

---

## Testing & Verification

The testing suite inside `:jvterm-input` ensures strict compliance with terminal protocols:
* **focused unit tests** cover modifier translations, individual key conversions, mouse limits, paste sanitization policies, and edge-case boundary coordinates.
* **matrix-based xterm profile testing** ([`XtermInputProfileTest`](./src/test/kotlin/input/impl/XtermInputProfileTest.kt)) validates a massive cross-product of modifiers, physical keys, modes, and protocols.

To run tests:
```bash
./gradlew :jvterm-input:test
```
