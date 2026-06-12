# Terminal Input

**terminal-input** is the platform-agnostic, host-bound input encoding module for Lattice Terminal. It is designed to convert UI-level, platform-neutral events—such as keyboard presses, text paste, window focus transitions, and mouse pointer actions—into standardized ANSI/DEC/xterm byte sequences that are written to the terminal host's input stream (e.g., PTY or SSH stdin).

The module is engineered to be highly performant, allocation-minimal, and robust, with explicit support for modern shell and TUI protocols (e.g., bracketed paste, focus reporting, SGR mouse tracking, and xterm `modifyOtherKeys`).

---

## Architectural Scope and Boundaries

To preserve a strict separation of concerns, **terminal-input** operates under clear design constraints:

### What the Module Owns
- **Platform-Neutral Models**: Normalized representation of [TerminalKeyEvent](./src/main/kotlin/input/event/TerminalKeyEvent.kt), [TerminalPasteEvent](./src/main/kotlin/input/event/TerminalPasteEvent.kt), [TerminalFocusEvent](./src/main/kotlin/input/event/TerminalFocusEvent.kt), and [TerminalMouseEvent](./src/main/kotlin/input/event/TerminalMouseEvent.kt).
- **Validation**: Strict validation of input-only invariants (e.g., rejecting invalid modifier bitmasks, surrogate Unicode codepoints, negative coordinates).
- **ANSI/DEC Encoding**: Translating events into byte sequences based on the terminal's active modes.
- **Scratch Buffers**: Reusable, allocation-conscious scratch buffers ([InputScratchBuffer](./src/main/kotlin/input/impl/InputScratchBuffer.kt)) for generated terminal input sequences.
- **Input Policy**: Decision-making policies ([TerminalInputPolicy](./src/main/kotlin/input/policy/TerminalInputPolicy.kt)) for handling backspace bytes, meta keys, unsupported modified combinations, out-of-range legacy mouse coordinates, and paste sanitization.

### What the Module Does NOT Own
- **Terminal Output Parsing**: The input module never parses byte streams or terminal replies.
- **State Mutation**: It never directly mutates core screen buffers, cursors, or style attributes.
- **UI Event Conversions**: It is not responsible for platform-specific keyboard/mouse listeners or converting mouse pixel coordinates into cell coordinates.
- **Thread-Safe Ordering**: Encoders are not thread-safe and expect serialized calls from a terminal event loop actor.

### Serialization and Threading Boundary
Host-bound byte ordering must be absolutely deterministic. To achieve this, the input encoder is designed for **serialized-use only** by the terminal actor:
```text
UI Adapter  ───►  Terminal Event Loop Actor  ───►  TerminalInputEncoder  ───►  TerminalHostOutput  ───►  PTY stdin
                                                                                     ▲
Parser/Core Replies  ────────────────────────────────────────────────────────────────┘
```
Concurrent writes from multiple UI or core threads must be avoided; the terminal event loop actor coordinates and serializes all traffic directed toward `TerminalHostOutput`.

---

## Public API Surface

The primary public API of the module is designed to be simple and cohesive:

1. **[TerminalInputEncoder](./src/main/kotlin/input/api/TerminalInputEncoder.kt)**: The entrypoint facade interface for encoding normalized input events.
2. **[DefaultTerminalInputEncoder](./src/main/kotlin/input/impl/DefaultTerminalInputEncoder.kt)**: The standard implementation. It takes a read-only `TerminalInputState` and a `TerminalHostOutput` sink, then coordinates the specialized internal encoders.
3. **[TerminalInputPolicy](./src/main/kotlin/input/policy/TerminalInputPolicy.kt)**: A configurable set of policies determining:
   - `BackspacePolicy`: Whether to send `DEL` (`0x7F`) or `BS` (`0x08`) for Backspace.
   - `MetaKeyPolicy`: Prefixes, ignores, or suppresses the `Meta` modifier.
   - `UnsupportedModifiedKeyPolicy`: Suppresses or emits unmodified keys when a modified key sequence is unsupported.
   - `MouseCoordinateLimitPolicy`: Limits legacy mouse coordinates by clamping or suppressing them.
   - `PasteSanitizationPolicy`: Determines if pasted text is emitted RAW, stripped of C0 controls (except Tab, CR, LF), or normalized for line endings (CR/CRLF normalized to LF).

---

## Normalized Event Models

Input events are decoupled from any specific UI framework (e.g., Swing, AWT, JavaFX):

- **[TerminalKeyEvent](./src/main/kotlin/input/event/TerminalKeyEvent.kt)**: Carries either a non-printable physical [TerminalKey](./src/main/kotlin/input/event/TerminalKey.kt) or a printable Unicode scalar codepoint, along with active modifiers.
- **[TerminalModifiers](./src/main/kotlin/input/event/TerminalModifiers.kt)**: Bit mask constants (`SHIFT`, `ALT`, `CTRL`, `META`) and utility helpers. Wire-boundary translations map modifiers to standard CSI parameter formats via `1 + modifiers`.
- **[TerminalPasteEvent](./src/main/kotlin/input/event/TerminalPasteEvent.kt)**: Houses the raw string being pasted.
- **[TerminalFocusEvent](./src/main/kotlin/input/event/TerminalFocusEvent.kt)**: Indicates whether the terminal window gained or lost focus.
- **[TerminalMouseEvent](./src/main/kotlin/input/event/TerminalMouseEvent.kt)**: Employs zero-based cell-coordinates and maps them to a [TerminalMouseButton](./src/main/kotlin/input/event/TerminalMouseButton.kt) and [TerminalMouseEventType](./src/main/kotlin/input/event/TerminalMouseEventType.kt) (PRESS, RELEASE, MOTION, WHEEL).

---

## Specialized Internal Encoders

`DefaultTerminalInputEncoder` delegates encoding logic to dedicated, stateless sub-encoders that receive packed terminal mode bits on every invocation:

### 1. Keyboard Encoding ([KeyboardEncoder](./src/main/kotlin/input/impl/KeyboardEncoder.kt))
Responsible for key mappings, arrow keys, function keys, and complex modifier sequences:
- **Arrow Keys & Home/End**: Respects *Application Cursor Keys* mode, selecting `SS3` (e.g., `ESC O A`) or normal `CSI` (e.g., `ESC [ A`) sequences.
- **Function Keys (F1-F12, PF1-PF4)**: Maps unmodified keys to classic DEC/ANSI sequences, and maps modified keys using modern CSI parameter syntax.
- **Numeric Keypad**: Checks *Application Keypad* mode, mapping keys (e.g., `NUMPAD_0` through `NUMPAD_9`, operational keys) to `SS3` code points (e.g., `ESC O p`) or their default ASCII representation.
- **Control Modifiers**: Direct Ctrl mappings are generated via standard ASCII control code rules (e.g., Ctrl+A to `0x01`, Ctrl+[ to `0x1B`).
- **xterm `modifyOtherKeys`**: Implements both Mode 1 (ambiguous/missing keys) and Mode 2 (all modified keys) protocols. Modified printable characters are packaged into the classic wire format: `CSI 27 ; <modifier> ; <codepoint> ~`.

### 2. Mouse Tracking and Encodings ([MouseEncoder](./src/main/kotlin/input/impl/MouseEncoder.kt))
Filters mouse events based on the active tracking mode and encodes coordinates according to the selected encoding protocol:
- **Tracking Modes**: Supports `NONE`, `X10` (press only), `NORMAL` (press, release, wheel), `BUTTON_EVENT` (press, release, wheel, button motion), and `ANY_EVENT` (all tracking including no-button motion).
- **Encoding Protocols**:
  - **SGR Mouse Mode (`SGR`)**: The modern decimal format (`CSI < button ; column ; row M/m`). Supports unlimited coordinates and reports exact button release states.
  - **Legacy Mode (`DEFAULT`)**: Traditional `ESC [ M <button> <column> <row>` byte packing. Bounded to coordinates up to 223, utilizing `TerminalInputPolicy` for coordinates that overflow this range.
  - **UTF-8 Extended (`UTF8`)**: Extended multi-byte coordinate formatting.
  - **URXVT Mode (`URXVT`)**: Decimal-based `CSI <button> ; <column> ; <row> M` protocol.

### 3. Paste Sanitization ([PasteEncoder](./src/main/kotlin/input/impl/PasteEncoder.kt))
- Applies [PasteSanitizationPolicy](./src/main/kotlin/input/policy/TerminalInputPolicy.kt) filters to text streams.
- Handles **Bracketed Paste Mode**: Wraps the text inside `CSI 200~` and `CSI 201~` markers when bracketed paste is active in the terminal.
- Implements zero-allocation, on-the-fly UTF-8 encoding.

### 4. Focus Transition ([FocusEncoder](./src/main/kotlin/input/impl/FocusEncoder.kt))
- Sends focus transition bytes—`CSI I` (focus in) and `CSI O` (focus out)—only if focus reporting mode is enabled.

---

## High-Performance and Low-Allocation Design

The hot path for terminal input must not trigger unnecessary JVM garbage collection. **terminal-input** enforces strict performance rules:
- **Pre-Encoded Constants**: Common fixed sequences (e.g., arrow keys, default function keys, bracketed paste tokens) are pre-allocated as byte arrays in [TerminalSequences](./src/main/kotlin/input/impl/TerminalSequences.kt) and written directly.
- **[InputScratchBuffer](./src/main/kotlin/input/impl/InputScratchBuffer.kt)**: Sub-encoders reuse a singular, fixed-capacity scratch buffer owned by the outer `DefaultTerminalInputEncoder`. The scratch buffer formats integer decimals and UTF-8 multibyte codepoints in-place without producing `String` or `StringBuilder` instances.
- **Immediate Consumption**: The byte arrays written to `TerminalHostOutput.writeBytes(bytes, offset, length)` may refer to scratch-backed ranges. Consumers must synchronously process or copy these bytes, as the scratch buffer will be immediately cleared and reused for the subsequent event.

---

## Testing Boundary

The testing suite inside `:terminal-input` ensures strict compliance with terminal protocols:
- **focused unit tests** cover modifier translations, individual key conversions, mouse limits, paste sanitization policies, and edge-case boundary coordinates.
- **matrix-based xterm profile testing** ([XtermInputProfileTest](./src/test/kotlin/input/impl/XtermInputProfileTest.kt)) validates a massive cross-product of modifiers, physical keys, modes, and protocols to confirm xterm/ANSI compatibility.
- Assertions assert **exact bytes** on the wire, making the protocol contract explicit.
