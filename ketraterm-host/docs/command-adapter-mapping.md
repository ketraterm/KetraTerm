# Command Adapter Coordinate Mapping & Buffer Swaps

The [HostCommandAdapter](../src/main/kotlin/io/github/ketraterm/host/HostCommandAdapter.kt) acts as the bridge that translates abstract, 1-based, string-containing parser commands into zero-based physical operations on the stateful terminal buffer core.

---

## 1. Coordinate Normalization

ANSI and DEC wire protocols use **1-based, top-left relative coordinates** (column 1, row 1 is the first printable cell).

* **Adapter Normalization**: The adapter intercepts coordinate parameters and converts them to **0-based, top-left relative coordinates** (column 0, row 0) before calling core API operations.
* **Out-of-bounds Safeguards**: Coordinates that fall outside the active terminal buffer width or height are clamped or handled according to terminal standards (e.g. cursor motion clamping).

---

## 2. Screen Buffer Toggling Modes

ANSI and xterm provide multiple DEC private modes to toggle between the **primary screen buffer** and the **alternative screen buffer**. The adapter maps these as follows:

| Mode Value | Standard | Action Taken by Adapter |
| :--- | :--- | :--- |
| `?47` | Alt Screen | Toggles core active buffer pointer to alternate screen. |
| `?1047` | Alt Screen (w/ Clear) | Clears and homes cursor in alternate screen on entry; resets active margins. |
| `?1049` | Alt Screen (w/ Save) | Saves cursor state, switches to alternate screen, clears contents, and homes cursor. On exit, restores saved cursor state. |

---

## 3. Soft and Hard Resets

### Soft Reset (`DECSTR` / `CSI ! p`)
When a soft reset is requested:
* Core state preservation:Preserves scrollback history, active buffer selection, and window dimensions.
* Adapter resets: Clears the pen formatting mirror (resets bold, colors, italic, Blink flags to defaults) and clears active hyperlink selections (`activeHyperlinkUri` and `activeHyperlinkId`).

### Hard Reset (`RIS` / `ESC c`)
When a hard reset is requested:
* Wipes both screens and clears all history scrollback.
* Resets tab stops to standard 8-column spacing.
* Resets the entire adapter's hyperlink LRU caches and restarts numeric ID sequences from 1.
