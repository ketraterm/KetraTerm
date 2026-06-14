# JVTerm Desktop Notifications Protocol Specification

JVTerm supports native desktop notifications triggered directly from the terminal via Operating System Command (OSC) escape sequences. This document specifies the supported sequences, behavior policies, security gates, and the JVTerm-specific severity extension.

---

## 1. Supported Escape Sequences

JVTerm supports two main notification protocols: **iTerm2-style OSC 9** and **urxvt-style OSC 777**.

### OSC 9 (iTerm2 style)
Triggers a desktop notification using a single body payload.

*   **Syntax:** `OSC 9 ; <message> ST` (or `BEL`)
*   **Behavior:** Displays a notification where the title defaults to `JvTerm`, the body is set to `<message>`, and the severity level is implicitly `INFO`.

### OSC 777 (urxvt style)
Triggers a desktop notification with separate title and body payloads.

*   **Syntax:** `OSC 777 ; notify ; <title> ; <body> ST` (or `BEL`)
*   **Behavior:** Displays a notification with the specified `<title>` and `<body>`. Semicolons inside the body are correctly reconstructed. The severity level defaults to `INFO`.

---

## 2. JVTerm Extended OSC 777 Severity Protocol

To support structured, rich native notifications, JVTerm extends the urxvt OSC 777 protocol to accept an optional case-insensitive fourth parameter specifying the **severity level**.

*   **Extended Syntax:** `OSC 777 ; notify ; <title> ; <body> ; [<level>] ST` (or `BEL`)
*   **Level Parameter:** Can be one of `info`, `warning`, `error`, or `none`.

### Severity Mappings

| Level | AWT MessageType | Behavior / Visual Representation |
| :--- | :--- | :--- |
| `info` (Default) | `MessageType.INFO` | Displays the notification with the OS-standard Information icon. |
| `warning` | `MessageType.WARNING` | Displays the notification with the OS-standard Warning icon. |
| `error` | `MessageType.ERROR` | Displays the notification with the OS-standard Error icon. |
| `none` | `MessageType.NONE` | Suppresses default OS symbols, rendering the custom JVTerm `>` logo instead. |

*Backward Compatibility:* If the last parameter does not match one of the four severity levels, the parser treats it as part of the notification body and defaults the level to `INFO`.

---

## 3. Operational Policies

### Host Safety and Filtering (ConEmu Conflict)
ConEmu uses `OSC 9;4;...` for progress bars and `OSC 9;9;...` for working directory sync. To prevent spamming desktop notifications when running under environments that emit these sequences, JVTerm explicitly inspects the sub-parameters of OSC 9. 

If the OSC payload begins with any of the ConEmu subcommand digits (`0`, `1`, `2`, `3`, `4`, `9`), the sequence is skipped and does not trigger a notification.

### Flood Protection and System Tray Clutter
Creating a new system tray icon for every notification causes taskbar clutter and lingering ghost icons. JVTerm handles this by:
1. Reusing a single, lazily-initialized `TrayIcon` instance on the Event Dispatch Thread (EDT).
2. Automatically removing the icon from the system tray after **10 seconds** of notification inactivity using a self-cleaning Swing `Timer`.

### Payload Clamping
To prevent excessive resource consumption and visual glitches, parameters are validated and clamped at the host integration layer:
*   **Maximum Title Length:** 256 characters (excess is truncated).
*   **Maximum Body Length:** 1024 characters (excess is truncated).
*   **Zero-Allocation Paint Loop:** Event handling and notification triggering are offloaded to the EDT and system daemon threads. The rendering hot loop (`GridPainter`) remains `0-allocation`.
