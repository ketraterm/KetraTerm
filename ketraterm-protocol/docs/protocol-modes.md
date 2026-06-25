# Terminal Protocol Modes Reference

This document defines the standard ANSI and DEC private modes supported by the `jvterm-protocol` module. These mode constants are used to toggle terminal behavior via standard control sequences.

---

## 1. Standard ANSI Modes

These modes are toggled using the standard Set Mode (`CSI Pn h` / `SM`) and Reset Mode (`CSI Pn l` / `RM`) sequences.

| Constant Name | Value | Description |
| :--- | :--- | :--- |
| `INSERT` | `4` | Controls character insertion behavior. When set, characters are inserted at the cursor position, shifting subsequent text to the right. When reset, new characters overwrite existing characters. |
| `NEW_LINE` | `20` | Determines whether line feed characters (`LF`, `VT`, `FF`) move the cursor to the beginning of the next line (when set) or just down one line without modifying the column (when reset). |

---

## 2. DEC Private Modes

These modes are prefix-gated and toggled using the DEC private Set Mode (`CSI ? Pn h` / `DECSET`) and Reset Mode (`CSI ? Pn l` / `DECRST`) sequences.

### Cursor & Display Settings

| Constant Name | Value | Description |
| :--- | :--- | :--- |
| `APPLICATION_CURSOR_KEYS` | `1` | Configures the cursor keys to send Application sequences (`ESC O key`) when set, or standard ANSI Cursor sequences (`ESC [ key`) when reset. |
| `REVERSE_VIDEO` | `5` | Inverts the background and foreground colors of the entire display. |
| `ORIGIN` | `6` | Constrains cursor movements to the active scroll margins when set. When reset, coordinates are relative to the physical screen origin. |
| `AUTO_WRAP` | `7` | Enables automatic wrapping to the next line when text reaches the right margin. |
| `CURSOR_BLINK` | `12` | Enables blinking of the text cursor. |
| `CURSOR_VISIBLE` | `25` | Toggles visibility of the text cursor. |
| `APPLICATION_KEYPAD` | `66` | Configures the numeric keypad to send Application sequences (`ESC O key`) when set. |

### Margins & Buffers

| Constant Name | Value | Description |
| :--- | :--- | :--- |
| `DECCOLM` | `3` | Toggles the column count between 80 (reset) and 132 (set) columns. |
| `LEFT_RIGHT_MARGIN` | `69` | Enables/disables vertical (left/right) margin support (DECSLRM). |
| `ALT_SCREEN` | `47` | Switches the terminal to the alternative screen buffer. |
| `ALT_SCREEN_BUFFER` | `1047` | Modern alternative screen buffer switch. |
| `SAVE_RESTORE_CURSOR` | `1048` | Saves (set) or restores (reset) cursor state (position, attributes, etc.). |
| `ALT_SCREEN_SAVE_CURSOR` | `1049` | Combined alternative screen buffer switch that also handles cursor save/restore. |

### Mouse Tracking & Reporting

These modes control when and how mouse interaction events are reported back to the host process.

| Constant Name | Value | Description |
| :--- | :--- | :--- |
| `MOUSE_X10` | `9` | Reports button press events only. |
| `MOUSE_NORMAL` | `1000` | Reports button presses, releases, and scroll-wheel movements. |
| `MOUSE_BUTTON_EVENT` | `1002` | Reports button presses, releases, scroll-wheel movements, and active drags. |
| `MOUSE_ANY_EVENT` | `1003` | Reports all mouse movement events, whether buttons are pressed or not. |
| `FOCUS_REPORTING` | `1004` | Toggles window focus gain and loss notifications. |
| `MOUSE_UTF8` | `1005` | Legacy UTF-8 extended mouse coordinate reporting. |
| `MOUSE_SGR` | `1006` | Modern, standard decimal-packed mouse coordinates. |
| `MOUSE_URXVT` | `1015` | URXVT extended decimal mouse coordinates. |

### Modern Enhancements

| Constant Name | Value | Description |
| :--- | :--- | :--- |
| `BRACKETED_PASTE` | `2004` | Wraps pasted text in control tokens (`ESC [ 200 ~` and `ESC [ 201 ~`) to prevent local execution. |
| `BELL_IS_URGENT` | `1042` | Configures the window bell (`BEL`) to trigger an OS urgent hint. |
| `POP_ON_BELL` | `1043` | Configures the window to pop to the foreground upon a bell signal. |
| `SYNCHRONIZED_OUTPUT` | `2026` | Defers grid updates until a corresponding reset/flush is received, preventing screen flickering. |
