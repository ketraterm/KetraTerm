# Terminal Protocol Modes Reference

Mode identifiers declared by `AnsiMode` and `DecPrivateMode`. These are wire
parameters, not packed-state bit positions. Their presence in this module does
not authorize a host action or guarantee an encoding path. Consult the
[feature map](../../docs/terminal-feature-map.md) for support status, the
[core contract](../../ketraterm-core/docs/terminal-core-contract.md#mode-state-contract)
for state semantics, and the
[input contract](../../ketraterm-input/docs/terminal-input-contract.md) for reports.

`CSI` denotes the control-sequence introducer, commonly transmitted as `ESC [`.

## 1. Standard ANSI Modes

`CSI Pn h` sets an ANSI mode; `CSI Pn l` resets it.

| Constant | Value | Meaning |
| :--- | :--- | :--- |
| `INSERT` | `4` | Insert characters at the cursor rather than overwrite. |
| `NEW_LINE` | `20` | Line feeds also return the cursor to the first column. |

## 2. DEC Private Modes

`CSI ? Pn h` sets a DEC private mode; `CSI ? Pn l` resets it.

### Cursor & Display Settings

| Constant | Value | Meaning |
| :--- | :--- | :--- |
| `APPLICATION_CURSOR_KEYS` | `1` | Select application cursor-key sequences. |
| `REVERSE_VIDEO` | `5` | Select reverse display presentation. |
| `ORIGIN` | `6` | Address the cursor relative to active margins. |
| `AUTO_WRAP` | `7` | Enable automatic line wrapping at the right margin. |
| `CURSOR_BLINK` | `12` | Request a blinking cursor. |
| `CURSOR_VISIBLE` | `25` | Show the cursor. |
| `APPLICATION_KEYPAD` | `66` | Select application numeric-keypad sequences. |
| `BACKARROW_KEY` | `67` | Backspace sends BS when set and DEL when reset. |

### Margins & Buffers

| Constant | Value | Meaning |
| :--- | :--- | :--- |
| `DECCOLM` | `3` | Select 132 columns when set, 80 when reset. |
| `LEFT_RIGHT_MARGIN` | `69` | Enable left/right margin controls (DECSLRM). |
| `ALT_SCREEN` | `47` | Switch primary/alternate screens without clearing or saving the cursor. |
| `ALT_SCREEN_BUFFER` | `1047` | Enter a cleared alternate screen; exit without restoring a saved cursor. |
| `SAVE_RESTORE_CURSOR` | `1048` | Save cursor state on set, restore it on reset. |
| `ALT_SCREEN_SAVE_CURSOR` | `1049` | Save the primary cursor and enter a cleared alternate screen; restore on exit. |

The [core lifecycle contract](../../ketraterm-core/docs/terminal-core-contract.md#primary-and-alternate-screens)
describes repeated switches, cursor presentation, and alternate content retention.
`DECCOLM` also clears and resets state; it is not a general-purpose resize API.

### Mouse Tracking & Reporting

Tracking modes select which events are reported; encoding modes select their wire
format. They are independent selections.

| Constant | Value | Meaning |
| :--- | :--- | :--- |
| `MOUSE_X10` | `9` | Button presses only. |
| `MOUSE_NORMAL` | `1000` | Button press/release and wheel events. |
| `MOUSE_BUTTON_EVENT` | `1002` | Normal tracking plus motion with a button held. |
| `MOUSE_ANY_EVENT` | `1003` | Normal tracking plus motion, including hover. |
| `FOCUS_REPORTING` | `1004` | Focus gain/loss reports. |
| `MOUSE_UTF8` | `1005` | UTF-8 extended mouse coordinates. |
| `MOUSE_SGR` | `1006` | SGR decimal reports with cell coordinates. |
| `MOUSE_URXVT` | `1015` | urxvt decimal mouse reports. |
| `MOUSE_SGR_PIXELS` | `1016` | SGR decimal reports with pixel coordinates. |

The [mouse contract](../../ketraterm-input/docs/terminal-input-contract.md#mouse-contract)
defines coordinate bounds and admission. Encoding vocabulary does not imply that
every encoding is enabled by the encoder's policy.

### Modern Enhancements

| Constant | Value | Meaning |
| :--- | :--- | :--- |
| `BRACKETED_PASTE` | `2004` | Surround a paste with `CSI 200 ~` and `CSI 201 ~`. |
| `BELL_IS_URGENT` | `1042` | Request host attention on BEL. |
| `POP_ON_BELL` | `1043` | Request that the host raise its window on BEL. |
| `SYNCHRONIZED_OUTPUT` | `2026` | Hold render publication while a group of updates is applied. |

Bracketed paste lets applications distinguish pasted text from typed input; the
markers do not enforce application behavior or prevent execution. Synchronized
output continues parsing and grid mutation; the session controls publication and
releases a held frame on reset or timeout.

## 3. Mode Status and Host Capabilities

`TerminalModeStatus` defines the DECRPM status parameter:

| Constant | Value | Meaning |
| :--- | :--- | :--- |
| `UNRECOGNIZED` | `0` | No supported status report for this mode. |
| `SET` | `1` | Currently set. |
| `RESET` | `2` | Currently reset. |
| `PERMANENTLY_SET` | `3` | Fixed in the set state. |
| `PERMANENTLY_RESET` | `4` | Fixed in the reset state. |

`TerminalHostModeCapability.URGENT_BELL` (`2`) and `POP_ON_BELL` (`4`) describe
host actions available to status reporting; `ALL` is their combined mask (`6`).
A host can expose a capability while denying an individual request. These bits
are neither DEC mode numbers nor policy permissions.
