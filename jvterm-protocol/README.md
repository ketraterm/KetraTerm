# JvTerm Protocol (`:jvterm-protocol`)

The `jvterm-protocol` module represents the zero-dependency, immutable core vocabulary of **JvTerm Terminal**. It defines the fundamental, standard-aligned constants, enumerations, and interfaces shared by all terminal pipeline components.

By centralizing ANSI/DEC protocol keys, mode identifiers, and low-level byte sinks, the module ensures strict consistency across the entire terminal stack while maintaining a lightweight, JIT-friendly, and allocation-conscious footprint.

---

## Upstream Dependencies
* **None**. This is a standalone, zero-dependency module compiling against the bare-metal Kotlin Standard Library.

---

## Architectural Role

To maintain a strict separation of concerns, `jvterm-protocol` contains **no execution logic, no parser engines, and no terminal grid memory**. It acts purely as a shared typing and constant definitions layer.

```mermaid
classDiagram
    direction TB
    class ControlCode {
        <<constants>>
        +NUL: Int
        +ESC: Int
        +BEL: Int
        +...
    }
    class AnsiMode {
        <<constants>>
        +INSERT
        +NEW_LINE
    }
    class DecPrivateMode {
        <<constants>>
        +CURSOR_VISIBLE
        +ALT_SCREEN_BUFFER
        +SYNCHRONIZED_OUTPUT
        +...
    }
    class MouseTrackingMode {
        <<enum>>
        +OFF
        +NORMAL
        +ANY_EVENT
    }
    class MouseEncodingMode {
        <<enum>>
        +DEFAULT
        +SGR
        +URXVT
    }
    class TerminalHostOutput {
        <<interface>>
        +writeByte(byte: Int)
        +writeBytes(bytes: ByteArray, offset: Int, length: Int)
        +writeAscii(text: String)
        +writeUtf8(text: String)
    }

    ControlCode --> TerminalHostOutput : used in
```

### What the Module Owns
* **ANSI & ECMA-48 C0/C1 Byte Constants**: Raw numerical mappings for physical ASCII controls and 8-bit terminal state transitions.
* **Standard ANSI & DEC Private Modes**: Integer constants for CSI SM/RM (Set/Reset Mode) and DECSET/DECRST parameters.
* **State & Tracking Enums**: High-level semantic enums representing mouse tracking and encoding policies.
* **Performance-Optimized Primitive Mappings**: Zero-overhead integer constants for fast keyboard encoders, preventing object allocations during intensive key and mouse event dispatches.
* **Outbound Host Communication Interface**: A clean, unified, platform-neutral byte sink (`TerminalHostOutput`) that acts as the target for all encoded host communication (PTY stdin, SSH buffers, etc.).

### What the Module Does NOT Own
* **No Byte-Stream Parsing**: Does not parse escape sequences, decode UTF-8 streams, or analyze CSI/OSC/DCS structures.
* **No State Mutation**: Contains no terminal grids, scrollback history, or margin clamping logic.
* **No Input Event Encoding**: Does not translate UI actions or keys into ANSI strings.

---

## 🗛 Architectural Vocabularies & Components

The module's source directory is organized into concise files, each mapping a specific area of the terminal wire protocols:

### 1. ASCII C0, DEL & C1 Controls ([`ControlCode`](./src/main/kotlin/protocol/ControlCode.kt))
Provides standard ANSI/ECMA-48 constants for single-byte control codes. 
* **C0 Controls (`0x00..0x1F`)**: Mapped to classic codes like `NUL`, `BEL`, `BS` (Backspace), `HT` (Tab), `LF` (Line Feed), `CR` (Carriage Return), `ESC` (Escape), `CAN` (Cancel), and `SUB` (Substitute).
* **DEL Code (`0x7F`)**: Mapped to standard delete character semantics.
* **C1 Controls (`0x80..0x9F`)**: Mapped to 8-bit controls such as `IND` (Index), `NEL` (Next Line), `RI` (Reverse Index), `DCS` (Device Control String), `CSI` (Control Sequence Introducer), `ST` (String Terminator), and `OSC` (Operating System Command).

> [!NOTE]
> C1 byte values are provided for completeness. In typical UTF-8 streams, bytes above `0x7F` are treated as UTF-8 multibyte payloads rather than 8-bit controls, avoiding collisions with normal multibyte characters.

---

### 2. Standard ANSI Modes ([`AnsiMode`](./src/main/kotlin/protocol/AnsiMode.kt))
Houses mode identifiers toggled via CSI `SM` (Set Mode) and `RM` (Reset Mode) sequences.
* `INSERT` (`4`): Controls character insertion behavior in the active grid.
* `NEW_LINE` (`20`): Determines whether `LF`, `VT`, or `FF` cause the cursor to go to the first column of the next line, or just perform a vertical transition.

---

### 3. DEC Private Modes ([`DecPrivateMode`](./src/main/kotlin/protocol/DecPrivateMode.kt))
Tracks common DEC private mode parameters toggled via `DECSET` (`CSI ? Pn h`) and `DECRST` (`CSI ? Pn l`) sequences:
* **Cursor & Viewport Behavior**: `APPLICATION_CURSOR_KEYS` (`1`), `CURSOR_BLINK` (`12`), `CURSOR_VISIBLE` (`25`), `AUTO_WRAP` (`7`), and `ORIGIN` (`6`).
* **Sizing & Margins**: `DECCOLM` (`3`) and `LEFT_RIGHT_MARGIN` (`69`).
* **Screen Buffers**: `ALT_SCREEN` (`47`), `ALT_SCREEN_BUFFER` (`1047`), and `ALT_SCREEN_SAVE_CURSOR` (`1049`).
* **Mouse Tracking**: `MOUSE_X10` (`9`), `MOUSE_NORMAL` (`1000`), `MOUSE_BUTTON_EVENT` (`1002`), and `MOUSE_ANY_EVENT` (`1003`).
* **Mouse Encodings**: `MOUSE_UTF8` (`1005`), `MOUSE_SGR` (`1006`), and `MOUSE_URXVT` (`1015`).
* **Modern Shell Enhancements**: `FOCUS_REPORTING` (`1004`), `BRACKETED_PASTE` (`2004`), and `SYNCHRONIZED_OUTPUT` (`2026`).

---

### 4. High-Level Core Enums ([`MouseModes.kt`](./src/main/kotlin/protocol/MouseModes.kt))
Exposes semantic `enum class` definitions in package `io.github.jvterm.protocol` to represent configuration states:
* **`MouseTrackingMode`**: Represents high-level mouse reporting state:
  * `OFF`: Mouse reporting disabled.
  * `X10`: Report button presses only.
  * `NORMAL`: Report presses, releases, and scroll-wheel movements.
  * `BUTTON_EVENT`: Report presses, releases, scroll wheel, and active drags.
  * `ANY_EVENT`: Report all mouse movements.
* **`MouseEncodingMode`**: Defines the encoding protocol used to serialize mouse coordinates:
  * `DEFAULT`: Classic legacy `ESC [ M` byte-packed encoding.
  * `UTF8`: Extends coordinate limits via multi-byte UTF-8 sequences.
  * `SGR`: Modern, unlimited decimal protocol (`CSI < button ; column ; row M/m`).
  * `URXVT`: Extends coordinate limits via decimal-packed `CSI <button> ; <column> ; <row> M`.

---

### 5. Low-Level Performance Constants
Designed for JIT-friendly performance in allocation-sensitive hot paths. These constants map configuration states directly to packed integer bits without producing garbage allocations.

Mouse reporting constants live under package `io.github.jvterm.protocol.mouse`:
* **`io.github.jvterm.protocol.mouse.MouseTrackingMode`**: Defines integer constants (`NONE = 0`, `X10 = 1`, `NORMAL = 2`, `BUTTON_EVENT = 3`, `ANY_EVENT = 4`) matching the packed ordinals in the core decoder.
* **`io.github.jvterm.protocol.mouse.MouseEncodingMode`**: Defines integer constants (`DEFAULT = 0`, `UTF8 = 1`, `SGR = 2`, `URXVT = 3`).

Keyboard input protocol constants live under package `io.github.jvterm.protocol.keyboard`:
* **`ModifyOtherKeysMode`** ([source](./src/main/kotlin/protocol/keyboard/ModifyOtherKeysMode.kt)): Mapped to `DISABLED = 0`, `MODE_1 = 1`, `MODE_2 = 2`, and `MODE_3 = 3`, matching xterm's modifyOtherKeys states.
* **`FormatOtherKeysMode`** ([source](./src/main/kotlin/protocol/keyboard/FormatOtherKeysMode.kt)): Mapped to `DEFAULT = 0` and `CSI_U = 1`.
* **`XtermKeyModifierResource`** and **`XtermKeyFormatResource`** ([source](./src/main/kotlin/protocol/keyboard/XtermKeyModifierResource.kt), [source](./src/main/kotlin/protocol/keyboard/XtermKeyFormatResource.kt)): Resource ids for option controls.
* **Kitty keyboard constants** ([flags](./src/main/kotlin/protocol/keyboard/KittyKeyboardProgressiveFlag.kt), [application modes](./src/main/kotlin/protocol/keyboard/KittyKeyboardFlagApplicationMode.kt), [event types](./src/main/kotlin/protocol/keyboard/KittyKeyboardEventType.kt), [functional key codes](./src/main/kotlin/protocol/keyboard/KittyKeyboardFunctionalKeyCode.kt)): Primitive vocabulary for Kitty keyboard protocol path.

---

## 6. Outbound Host Communication Sink ([`TerminalHostOutput`](./src/main/kotlin/protocol/host/TerminalHostOutput.kt))
Acts as the central interface for all host-bound byte traffic generated by the emulator (like keyboard sequences, mouse coordinate reports, focus events, or query responses).

```kotlin
interface TerminalHostOutput {
    fun writeByte(byte: Int)
    fun writeBytes(bytes: ByteArray, offset: Int, length: Int)
    fun writeAscii(text: String)
    fun writeUtf8(text: String)
}
```

> [!IMPORTANT]
> **Thread Synchronization Boundary:**
> `TerminalHostOutput` implementations are typically mapped directly to PTY or SSH standard input streams. Callers must guarantee that operations from independent threads are properly serialized to prevent overlapping or corrupted packet sequences.

---

## 🔗 How to Use

Below are typical integration examples demonstrating how a client codebase would import and consume the `jvterm-protocol` definitions.

### A. Byte Classification in a Byte Stream Parser
```kotlin
import io.github.jvterm.protocol.ControlCode

fun processNextByte(byte: Int) {
    when (byte) {
        ControlCode.ESC -> startEscapeSequence()
        ControlCode.CAN -> cancelCurrentSequence()
        ControlCode.BEL -> triggerBell()
        else -> bufferCharacter(byte)
    }
}
```

### B. Tracking Configuration State in a Grid Component
```kotlin
import io.github.jvterm.protocol.MouseEncodingMode
import io.github.jvterm.protocol.MouseTrackingMode

class CustomTerminalWidget {
    var mouseTracking: MouseTrackingMode = MouseTrackingMode.OFF
    var mouseEncoding: MouseEncodingMode = MouseEncodingMode.DEFAULT
}
```

### C. Low-Allocation Input Encoder Mapping
```kotlin
import io.github.jvterm.protocol.mouse.MouseEncodingMode
import io.github.jvterm.protocol.mouse.MouseTrackingMode

fun encodeMouseEvent(event: InputMouseEvent, tracking: Int, encoding: Int): ByteArray? {
    if (tracking == MouseTrackingMode.NONE) return null
    
    return if (encoding == MouseEncodingMode.SGR) {
        // Build SGR format CSI < btn ; col ; row M/m without object allocations
        val command = "CSI < ${event.button};${event.column};${event.row} M"
        command.toByteArray(Charsets.US_ASCII)
    } else {
        null
    }
}
```

---

## 🔗 How to Extend: Custom Transport Sinks

To output bytes from the emulator to a custom channel (such as a TCP Socket, SSH Session, or Mock Console), implement the `TerminalHostOutput` interface:

```kotlin
import io.github.jvterm.protocol.host.TerminalHostOutput
import java.io.OutputStream

class OutputStreamHostOutput(private val out: OutputStream) : TerminalHostOutput {
    override fun writeByte(byte: Int) {
        out.write(byte)
    }

    override fun writeBytes(bytes: ByteArray, offset: Int, length: Int) {
        out.write(bytes, offset, length)
    }

    override fun writeAscii(text: String) {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        out.write(bytes)
    }

    override fun writeUtf8(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        out.write(bytes)
    }
}
```

---

## Performance & Engineering Discipline

To satisfy the high-performance targets of the JvTerm Terminal stack:
1. **Zero External Dependencies**: Must never import external or third-party libraries.
2. **Compile-Time Constant Propagation**: All constant values (CSI parameters, ASCII ranges, mode flags) are declared as `const val` compile-time constants, enabling compilers to inline these values directly.
3. **No Garbage Allocation**: The primitives and enums inside this module must not prompt dynamic heap allocations. Memory-sensitive layers use flat `Int` mappings, keeping garbage collector pressure at zero on execution hot paths.

---

## Testing & Validation

Although `jvterm-protocol` is largely composed of constants, it includes unit tests to verify that values adhere to standard terminal specifications.

* **Mode and Code Validation ([`TerminalProtocolModesTest`](./src/test/kotlin/protocol/TerminalProtocolModesTest.kt))**: Asserts that every control code matches its exact wire-byte value (e.g. `BEL` = `0x07`, `CSI` = `0x9B`), that ANSI modes match CSI SM/RM standard targets, and that DEC private modes align with correct specification values.
* **Keyboard Vocabulary Validation ([`TerminalKeyboardProtocolTest`](./src/test/kotlin/protocol/keyboard/TerminalKeyboardProtocolTest.kt))**: Asserts that xterm modified-key constants and Kitty keyboard protocol constants match their documented wire values.

To run the protocol module checks:
```bash
./gradlew :jvterm-protocol:test
```
