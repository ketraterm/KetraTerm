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
| Reverse wrap | Modes 45/1045 apply to BS and CUB with autowrap enabled: soft-wrapped rows only (45), or hard breaks and top-to-bottom margin cycling (1045). |
| Rectangular operations | Erase, selective erase, fill, copy, attribute changes/reversal, and base VT420 checksum. Copy/checksum use the active page only. |
| Alternate screen | Modes 47/1047/1049, screen-local saved cursor state, and primary scrollback preservation. |
| Scrollback | Bounded history, incremental storage allocation, and logical-line resize reflow. |
| Grid width | Minimum two columns, with 80/132-column switching through DECCOLM. |
| Resets | Hard reset, soft reset, screen/history erase, and DEC alignment test. |
| Synchronized output | Mode 2026 delays frame publication until completion or timeout. |
| Character sets | ASCII and DEC Special Graphics, G0–G3 designation, locking and single shifts. |
| Unicode | UTF-8 decoding, Unicode 17 grapheme segmentation, combining marks, wide characters, and emoji sequences. Mode 2027 reports grapheme processing as permanently enabled; see [mode status](../reference/protocol.md#mode-status-reports). |
| Stream handling | Incremental decoding across byte chunks, malformed UTF-8 recovery, and bounded CSI/OSC/DCS collection with cancellation and overflow recovery. |
| Width policy | Pinned Unicode width tables, configurable ambiguous width, and base-specific VS15/VS16 cell allocation; unsupported presentation bases retain their ordinary width. See the [core contract](../../ketraterm-core/docs/terminal-core-contract.md#unicode-scalar-and-width-policy). |

Streaming graphemes retain up to 32 codepoints; direct core cluster writes reject
longer prefixes before mutation. Later width changes update the
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
| Dynamic colors | OSC 4/10/11/12 palette and foreground/background/cursor changes and queries; OSC 104 and 110/111/112 restore configured theme colors. |
| Kitty colors | OSC 21 ordered set/reset/query batches for indexed, foreground/background, cursor, and selection colors; dynamic cursor/selection colors. [Protocol details](../reference/protocol.md#kitty-colors). |
| Color stack | Kitty OSC 30001/30101 save and restore effective colors and dynamic behavior in a bounded stack. [Protocol details](../reference/protocol.md#color-stack). |
| Hyperlinks | OSC 8 links with bounded retention. |
| Mouse pointer | OSC 22 set/reset for 14 portable CSS shapes; [protocol details](../reference/protocol.md#pointer-shapes). |

## Keyboard, mouse, and paste

| Feature | Support |
| --- | --- |
| Keyboard | Cursor/function/keypad keys, modifiers, application modes, Backarrow mode, and committed text. |
| xterm key resources | modifyOtherKeys and independent modifier/format resources with status queries. |
| Kitty keyboard | Progressive flag negotiation and encoding; available flags depend on host input metadata. |
| Paste | Bracketed paste, control-character protection, and configurable newline handling. |
| Mouse tracking | X10, normal, button-event, and any-event tracking. |
| Mouse behavior | Mode 1007 controls alternate-screen wheel-to-cursor-key input; XTSHIFTESCAPE (`CSI > Ps s`) requests Shift mouse capture. Hosts retain control over reporting and wheel translation. |
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
| Window reports | Minimized state, text-area and character-cell pixel dimensions (`CSI 14 t` / `CSI 16 t`), grid dimensions, and screen-size reports. Pixel dimensions require host data; the current screen-size reply uses the terminal grid. |
| Color scheme | One-shot light/dark query and mode 2031 subscriptions to host theme changes, including palette changes within the same light/dark preference. |
| In-band resize | Mode 2048 reports text-area rows, columns, and pixel dimensions on enable and after size changes; unavailable pixel dimensions are zero. |
| Kitty graphics | Inline RGB/RGBA and PNG, bounded zlib/chunked uploads, queries, ordinary cropped/scaled placements, deletion, screen lifecycle and Swing rendering. Session decoding preserves stream order while frame reads and ordinary input remain available; bounded raster reuse covers scrolling out of view and back. [Real application verification](../../tools/kitty-graphics/README.md#real-application-smoke-tests). [Windows backend caveats](../../ketraterm-pty/docs/pty4j-backend-limitations.md#windows-graphics-cursor-desynchronization). [Remaining extensions](../terminal-feature-gap-map.md#optional-protocol-work). |
| Custom OSC | Ordered callbacks for unsupported numeric OSC commands. |
| Host policy | Independent controls for clipboard, titles, links, notifications, window actions, colors, and terminal replies. |

Clipboard access is denied by default in the library. Fresh product settings
allow clipboard writes and ask before reads. Payload limits and permission
checks apply to all output in a session, including remote programs.

See the [protocol reference](../reference/protocol.md) for collection
limits and query allowlists, and the [gap map](../terminal-feature-gap-map.md)
for unsupported protocols.
