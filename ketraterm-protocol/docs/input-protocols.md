# Keyboard Input Protocol Vocabulary

This document outlines the low-level constants and structures defined in `ketraterm-protocol` to support advanced keyboard input encoding standards, specifically **xterm modifyOtherKeys** and the **Kitty Keyboard Protocol**.

---

## 1. xterm `modifyOtherKeys` Mode

The `ModifyOtherKeysMode` constants represent states configured via resource controls to change how keys combined with modifiers (Shift, Control, Alt, Meta) are sent to the application.

| Constant | Value | Description |
| :--- | :--- | :--- |
| `DISABLED` | `0` | Disables advanced modified-key encoding. Keys resolve to legacy ambiguous sequences. |
| `MODE_1` | `1` | Encodes ordinary modified keys whose legacy representation is ambiguous or missing. |
| `MODE_2` | `2` | Encodes ordinary modified keys, and also includes exceptions like Tab, Enter, Backspace. |
| `MODE_3` | `3` | Encodes ordinary keys even when no modifiers are active. |

---

## 2. xterm `formatOtherKeys` Mode

Controls the structure used to format key sequences when `modifyOtherKeys` is active.

| Constant | Value | Description |
| :--- | :--- | :--- |
| `DEFAULT` | `0` | Standard xterm formatting. |
| `CSI_U` | `1` | Encodes key events using the standard CSI-u format (`CSI codepoint ; modifier u`). |

---

## 3. Kitty Keyboard Progressive Enhancement Flags

The `KittyKeyboardProgressiveFlag` constants represent bit fields toggled using `CSI = flags ; mode u`. They enable applications to selectively receive richer keyboard events. `SUPPORTED_MASK` intentionally includes only flags implemented by the current input encoder; additional constants remain shared protocol vocabulary for future event-model work.

| Constant Name | Value (Bit Shift) | Description |
| :--- | :--- | :--- |
| `DISAMBIGUATE_ESCAPE_CODES` | `1` | Enables distinct, non-overlapping escape sequences for key combinations that are traditionally ambiguous. |
| `REPORT_EVENT_TYPES` | `2` (1 << 1) | Configures the terminal to report key press, repeat, and release event types. |
| `REPORT_ALTERNATE_KEYS` | `4` (1 << 2) | Includes alternate layout key values (e.g. shift/caps lock state) to aid keyboard shortcut matching. |
| `REPORT_ALL_KEYS_AS_ESCAPE_CODES` | `8` (1 << 3) | Reports standard printable/text keys as CSI-u sequences instead of plain text characters. |
| `REPORT_ASSOCIATED_TEXT` | `16` (1 << 4) | Emits the UTF-8 text associated with a key press alongside the escape sequence. |

Current advertised support is `DISAMBIGUATE_ESCAPE_CODES | REPORT_ALL_KEYS_AS_ESCAPE_CODES`.

---

## 4. Kitty Keyboard Event Types

The `KittyKeyboardEventType` values represent specific key transition events:

| Value | Event Type |
| :--- | :--- |
| `1` | Key Pressed |
| `2` | Key Repeat |
| `3` | Key Released |

---

## 5. Kitty Keyboard Functional Key Codes

Non-character functional keys (e.g. Navigation, Function Keys, Modifiers) map to dedicated key code offsets starting at `20000` to prevent collisions with normal Unicode codepoints.
