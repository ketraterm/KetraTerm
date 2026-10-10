# Terminal features

These capabilities belong to the shared terminal pipeline and are available to
headless and Swing hosts. Host actions and replies remain subject to session policy.

## Screen and text

| Feature | Support |
| --- | --- |
| Cursor movement | Absolute/relative positioning, origin mode, DEC and ANSI/SCO save/restore, and configurable forward/backward tab stops. |
| Scrolling and margins | Vertical and horizontal regions, forward/reverse scrolling, line insertion/deletion. |
| Cell editing | Character and column insertion/deletion, erase, protected cells, and selective erase. |
| Writing modes | Insert/replace, autowrap, and automatic newline modes. |
| Rectangular operations | Erase, selective erase, fill, copy, attribute changes/reversal, and base VT420 checksum. Copy/checksum use the active page only. |
| Alternate screen | Modes 47/1047/1049, screen-local saved cursor state, and primary scrollback preservation. |
| Scrollback | Bounded history, incremental storage allocation, and logical-line resize reflow. |
| Grid width | 80/132-column switching through DECCOLM. |
| Resets | Hard reset, soft reset, screen/history erase, and DEC alignment test. |
| Synchronized output | Mode 2026 delays frame publication until completion or timeout. |
| Character sets | ASCII and DEC Special Graphics, G0–G3 designation, locking and single shifts. |
| Unicode | UTF-8 decoding, Unicode 17 grapheme segmentation, combining marks, wide characters, and emoji sequences. |
| Stream handling | Incremental decoding across byte chunks, malformed UTF-8 recovery, and bounded CSI/OSC/DCS collection with cancellation and overflow recovery. |
| Width policy | Configurable ambiguous width and emoji presentation. |

Streaming graphemes retain up to 32 codepoints. Later width changes update the
published cell without undoing committed wraps, scrolling, or overwrites, so
placement can depend on input chunk boundaries. See the
[streaming contract](../reference/protocol.md#streaming-grapheme-placement).

## Appearance

| Feature | Support |
| --- | --- |
| Colors | ANSI/bright colors, 256-color palette, 24-bit RGB, and underline colors. |
| Text attributes | Bold, faint, italic, blink, inverse, conceal, strikethrough, and overline. |
| Reverse video | Screen-wide reverse-video mode, independent of per-cell inverse attributes. |
| Underlines | Single, double, curly, dotted, and dashed. |
| Cursor | Block, underline, and bar shapes; visibility and blinking controls. |
| Dynamic colors | OSC 4/10/11/12 palette and foreground/background/cursor changes and queries. |
| Hyperlinks | OSC 8 links with bounded retention. |

## Keyboard, mouse, and paste

| Feature | Support |
| --- | --- |
| Keyboard | Cursor/function/keypad keys, modifiers, application modes, Backarrow mode, and committed text. |
| xterm key resources | modifyOtherKeys and independent modifier/format resources with status queries. |
| Kitty keyboard | Progressive flag negotiation and encoding; available flags depend on host input metadata. |
| Paste | Bracketed paste, control-character protection, and configurable newline handling. |
| Mouse tracking | X10, normal, button-event, and any-event tracking. |
| Mouse encoding | Legacy, UTF-8, SGR, URXVT, and SGR pixel coordinates. |
| Focus | Mode-controlled focus-in/focus-out reports. |

Portable Swing hosts advertise conservative Kitty flags; native layout, IME,
and complete key-lifecycle support remain [open work](../terminal-feature-gap-map.md#input).
The [input contract](../../ketraterm-input/docs/terminal-input-contract.md)
defines event and encoding behavior.

## Host integration and queries

| Feature | Support |
| --- | --- |
| Titles | Window/icon titles and title stack operations. |
| Shell metadata | OSC 7 directories and OSC 133 prompt/command markers; see [shell support](shells.md). |
| Clipboard | Selection-aware OSC 52 host callbacks. Built-in desktop providers write to the clipboard and read from the clipboard or available primary selection. |
| Notifications | OSC 9 and OSC 777, including optional severity; [protocol](../reference/notifications.md). |
| Bell | BEL events and retained urgency/pop-on-bell modes for host handling. |
| Window actions | Minimize, restore, move, raise/lower, resize, and maximize requests where the host permits them. |
| Status queries | Operating status, cursor position, primary/secondary device attributes, and supported mode status. |
| Capability queries | DECRQSS and XTGETTCAP through explicit allowlists. |
| Window reports | Minimized state, window pixel dimensions, grid dimensions, and screen-size reports. Pixel dimensions require host data; the current screen-size reply uses the terminal grid. |
| Color scheme | One-shot light/dark query; no unsolicited mode 2031 notifications. |
| Custom OSC | Ordered callbacks for unsupported numeric OSC commands. |
| Host policy | Independent controls for clipboard, titles, links, notifications, window actions, colors, and terminal replies. |

Clipboard access is denied by default in the library. Fresh product settings
allow clipboard writes and ask before reads. Payload limits and permission
checks apply to all output in a session, including remote programs.

See the [protocol reference](../reference/protocol.md) for collection
limits and query allowlists, and the [gap map](../terminal-feature-gap-map.md)
for unsupported protocols.
