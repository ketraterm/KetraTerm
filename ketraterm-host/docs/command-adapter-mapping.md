# Command Adapter Coordinate Mapping & Buffer Swaps

[`HostCommandAdapter`](../src/main/kotlin/io/github/ketraterm/host/HostCommandAdapter.kt)
maps semantic parser calls to public core methods. The parser normalizes wire
parameters; core resolves geometry, origin mode, clipping, and state transitions.
The adapter does not parse escape sequences or implement grid physics.

## 1. Coordinate Normalization

There is no single conversion for every command:

| Command family | Parser sink convention | Adapter/core convention |
| --- | --- | --- |
| Cursor row/column positioning | Zero-based row/column | Passed unchanged to `positionCursor` |
| Vertical scroll region | Zero-based inclusive; `bottom = -1` means omitted | Add one; omitted bottom becomes `terminal.height` |
| Left/right margins | Zero-based inclusive; `right = -1` means omitted | Add one; omitted right becomes `terminal.width` |
| Rectangular operations and checksums | DEC one-based inclusive; zero means omitted | Passed unchanged |
| Cursor/edit counts | Normalized command count | Passed to the responsible core method |

For example, `CSI 2;5 H` arrives as `(row = 1, col = 4)` and is passed directly
to core. `CSI 2;5 r` arrives as `(top = 1, bottom = 4)` and becomes the core
margin call `(top = 2, bottom = 5)`. Core applies origin-mode semantics and
validates or clamps geometry according to each operation's contract.

See the parser
[`TerminalCommandSink`](../../ketraterm-parser/src/main/kotlin/io/github/ketraterm/parser/spi/TerminalCommandSink.kt)
and [core contract](../../ketraterm-core/docs/terminal-core-contract.md)
for parameter-level rules.

## 2. Screen Buffer Toggling Modes

The adapter delegates screen transitions to core. See the
[screen-mode table](../../ketraterm-core/docs/terminal-core-contract.md#primary-and-alternate-screens)
for modes 47, 1047, 1048, and 1049.

Logical column mode (`?3`) is separate from physical window manipulation. The
adapter calls `resizeForColumnMode` before `executeDeccolm`, then
`columnModeChanged` after it succeeds. A direct embedder must synchronize its
transport in the first hook; a failure must occur before destructive core
changes. The second hook can schedule a host window adjustment. Neither hook
should wait for UI work or reenter mutation.

## 3. Soft and Hard Resets

### Soft Reset (`DECSTR` / `CSI ! p`)

`softReset` delegates to core and clears the active hyperlink metadata. Visible
content, history, dimensions, and active-screen selection remain intact.
Retained hyperlink registry entries still resolve. Core owns the detailed mode,
pen, margin, wrap, and saved-cursor reset rules.

### Hard Reset (`RIS` / `ESC c`)

`resetTerminal` delegates to core, clears active hyperlink metadata and both
registry indexes, and emits `hyperlinksCleared` if the registry was nonempty.
It also emits `paletteChanged` if core's effective palette changed. Numeric
hyperlink IDs are never recycled.

RIS returns to primary and clears its content/history. Retained alternate cells
can remain reachable through mode `47`; their old hyperlink IDs are unresolved.
Do not treat hard reset as a new adapter identity lifetime. See the
[registry lifetime contract](hyperlink-registry.md#4-identity-lifetime-and-reset)
and [core reset contract](../../ketraterm-core/docs/terminal-core-contract.md#hard-reset).

## Response and host-action boundaries

Core-generated replies are queued in its response channel, then drained by
session or a direct embedder. `HostEventSink` carries metadata and action
requests rather than writing transport bytes or performing platform work.

`terminalResponsePolicy` gates status, device, mode, keyboard, window,
rectangular-checksum, DECRQSS, and XTGETTCAP replies, including supported failure
replies. OSC palette replies are independently gated by `palettePolicy`.
OSC 52 reads require a valid selector, clipboard-read permission, and terminal
response permission; a valid denied read can still be admitted for an empty
failure reply when response permission allows it. Response denial suppresses
that path too.

Permissions do not enlarge protocol allowlists. Unsupported selectors retain
their protocol-defined failure or silence behavior. Detailed support status
belongs in the [feature map](../../docs/terminal-feature-map.md) and
[gap map](../../docs/terminal-feature-gap-map.md).
