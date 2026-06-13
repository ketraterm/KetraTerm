# Keyboard & Mouse Input Serialized Encodings

The `jvterm-input` module translates high-level UI events (such as key strokes, mouse clicks, movements, and pasted text) into standard terminal escape sequences.

---

## 1. Keyboard Cursor & Application Modes

The encoding of cursor (Arrow) and keypad keys depends on terminal modes configured in the core buffer state:

### A. Application Cursor Keys (`DECCKM` / `DecPrivateMode.APPLICATION_CURSOR_KEYS`)
* **Normal Mode** (Default): Emits ANSI cursor sequences starting with `ESC [`:
  * Up: `ESC [ A`
  * Down: `ESC [ B`
  * Right: `ESC [ C`
  * Left: `ESC [ D`
* **Application Mode**: Emits Application sequences starting with `ESC O`:
  * Up: `ESC O A`
  * Down: `ESC O B`
  * Right: `ESC O C`
  * Left: `ESC O D`

### B. Application Keypad Mode (`DECNKM` / `DecPrivateMode.APPLICATION_KEYPAD`)
Toggles the numeric keypad keys (like `Home`, `End`, digits) to send custom Application escape sequences (`ESC O character`) instead of standard digits or ANSI cursor sequences.

---

## 2. Advanced Key Modifiers Encodings

When keys are combined with modifiers (Shift, Control, Alt, Meta), the encoder utilizes advanced formatting specifications:

### A. Standard xterm CSI Modifier Format
Used for navigation and function keys. It appends a modifier code as the second parameter:
* Format: `CSI 1 ; modifier character` (e.g. `CSI 1 ; 5 A` for Control+Up, where `5` represents Control).

### B. xterm `modifyOtherKeys` Mode
Encodes ordinary character keys combined with modifiers:
* Format: `CSI 27 ; modifier ; codepoint ~` (e.g. `CSI 27 ; 5 ; 97 ~` for Control+A).

### C. CSI-u (`formatOtherKeys=1`)
Encodes characters using standard CSI-u format:
* Format: `CSI codepoint ; modifier u` (e.g. `CSI 97 ; 5 u`).

---

## 3. Mouse Tracking & Encoding Protocols

Mouse reporting translates mouse clicks, releases, scrolling, and dragging into serial sequences:

### Mouse Tracking Modes
* **Normal (`1000`)**: Reports button presses, releases, and scroll-wheel actions.
* **Button Event (`1002`)**: Adds reporting for cursor movements while a button is held down (dragging).
* **Any Event (`1003`)**: Reports all mouse movements, regardless of button states.

### Mouse Encoding Schemes
* **Default Legacy**: Packs button and coordinate parameters into 3 bytes:
  * Format: `ESC [ M Cb Cx Cy` (where `Cx` and `Cy` are packed ASCII characters shifted by `32`). Limited to coordinates `<= 223`.
* **SGR Mouse (`1006`)** (Standard/Default): Emits unlimited decimal coordinates:
  * Format: `CSI < Cb ; Cx ; Cy M` (press/drag) or `CSI < Cb ; Cx ; Cy m` (release).
* **URXVT Mouse (`1015`)**: Legacy decimal-packed coordinates:
  * Format: `CSI Cb ; Cx ; Cy M`.
