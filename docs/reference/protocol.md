# Protocol limits and replies

This reference records boundary behavior for parser and host implementers.
For a capability overview, see [terminal features](../features/terminal.md).

## CSI parameters

CSI collection has a shared limit of 32 semicolon/colon fields, including omitted
fields. Opening field 33 rejects the entire command; no truncated SGR, mode,
cursor, or query is dispatched. Numeric saturation is independent of this limit.
CAN/SUB cancels, ESC starts a new sequence, and reset/EOF discards unfinished
state. Ordinary C0 handling continues during rejection.

## OSC encoding

Built-in OSC commands use UTF-8. There is no locale encoding fallback or raw
8-bit C1 termination inside payloads.

| Family | Encoding and malformed-input behavior |
| --- | --- |
| Titles (0/1/2), notifications (9/777) | Replacement decoding; repaired text still passes host policy and length checks. |
| Directory (7), palette (4/10/11/12/21), shell markers (133) | Strict UTF-8; reject malformed commands without updating state. |
| Color resets (104/110/111/112) | ASCII decimal indices or an empty payload; non-ASCII input rejects the complete reset. |
| Color stack (30001/30101) | Bare command or an empty semicolon; nonempty arguments reject the command. |
| Hyperlinks (8) | Strict UTF-8; malformed or completed oversized commands end active link context without changing retained links. |
| Clipboard (52) | ASCII envelope and strict Base64/UTF-8 text; invalid payloads are rejected before consent or access. |
| Pointer shape (22) | Exact ASCII CSS names from the supported subset, optionally prefixed with `=`; empty name restores host defaults. Unknown or malformed names leave state unchanged. |

BEL or `ESC \` completes OSC. CAN/SUB, reset, and EOF discard unfinished commands.
Other C0 controls and DEL are ignored inside OSC. Unknown numeric commands are
ignored unless a custom handler is installed. Recovery preserves later commands.
Host title/notification limits count UTF-16 code units and avoid splitting a valid
surrogate pair; they do not guarantee grapheme-boundary truncation.

## Pointer shapes

`OSC 22 ; name ST` sets the mouse pointer; BEL also terminates the command.
An optional `=` prefix is accepted. An empty name restores the host default.
Supported CSS names are `default`, `crosshair`, `text`, `wait`, `pointer`,
`move`, and `n-resize`, `ne-resize`, `e-resize`, `se-resize`, `s-resize`,
`sw-resize`, `w-resize`, `nw-resize`.

Names are exact, case-sensitive ASCII. Invalid, unsupported, or oversized
requests preserve the previous shape and never reach a custom OSC handler.
The override is session-wide: screen switches, cursor save/restore, resize,
and DECSTR preserves it; RIS clears it.

Pointer presentation is independent of mouse reporting and window-manipulation
permission. Activatable links and prompt actions can override the application
pointer. See [Ghostty's basic command](https://ghostty.org/docs/vt/osc/22), the
[Kitty specification](https://sw.kovidgoyal.net/kitty/pointer-shapes/), and
[remaining extensions](../terminal-feature-gap-map.md#optional-protocol-work).

## Payload limits

Limits count collected bytes, including family headers/separators and excluding
introducers, terminators, and ignored control bytes.

| Family | Collection limit |
| --- | --- |
| Titles, directory, hyperlinks, notifications, palette 4/21/104, shell markers | 4,096 bytes. |
| Dynamic colors 10/11/12/110/111/112 | 256 bytes. |
| Pointer shape 22 | 32 bytes. |
| Color stack 30001/30101 | 6 bytes, including the command number and optional empty semicolon. |
| DECRQSS | 64 bytes. |
| XTGETTCAP | 4,096 bytes. |
| Custom OSC | 4,096 bytes by default; configurable full-envelope limit, with a separate 4,096-byte header limit. |
| OSC 52 | Ordinary 4,096-byte limit; eligible writes use a budget derived from the active decoded-text limit. |

Overflow stops collection and consumes through termination/cancellation without
dispatching a truncated prefix. An oversized OSC 8 ends active link context;
other rejected commands preserve prior metadata. Oversized DCS queries receive
no reply. DCS terminates with ST, not BEL.

Session-integrated clipboard writes permit up to 1 MiB of decoded text by default.
Standalone parsers without that integration retain the ordinary collection limit.
Permissions and decoded-size limits are checked again before execution.

## Query allowlists

| Query | Reply scope |
| --- | --- |
| DECRQSS | SGR (`m`), vertical margins (`r`), horizontal margins (`s`), cursor style (`SP q`). Unsupported selectors receive empty failure replies. |
| XTGETTCAP | Terminal name (`TN`/`name`), color count (`Co`/`colors`), and true-color support (`RGB`/`Tc`). |
| Kitty colors (OSC 21) | Indices 0–255, `foreground`, `background`, `cursor`, `cursor_text`, `selection_foreground`, `selection_background`. Unknown keys receive `unknown=` plus unpadded Base64 of the original key. Palette permission gates the command; terminal-response permission additionally gates every reply. |
| Device identity | `TERM=xterm-256color`, `COLORTERM=truecolor`, VT420-class primary DA, and secondary DA. No DA3 unique identity. |
| Cursor position | One-based coordinates, relative to active origin/margins when origin mode is enabled. |
| Color scheme | `CSI ?996n` reports dark/light host preference; mode 2031 permits automatic reports for host theme changes. Application color overrides and resets do not change that preference or emit change reports. |
| In-band resize | Mode 2048 permits `CSI 48;rows;columns;heightPixels;widthPixels t` reports on enable and after text-area size changes. |
| Rectangle checksum | Active page only, base VT420 16-bit algorithm; no xterm XTCHECKSUM extensions. |

Denying terminal responses suppresses success and failure replies alike.
Unsupported queries with defined failure responses use those responses rather
than echoing request text.

## Kitty colors

`OSC 21;key=value;… ST` processes fields in order and combines query results
into one reply. `key=?` queries, bare `key` restores the current host theme, and
`key=` requests dynamic behavior. Keys are exact and case-sensitive; decimal
indices accept leading zeros. Empty keys and invalid color values are ignored.
Unknown keys receive encoded replies for set, reset, and query operations.

Colors accept Kitty's named colors, `#RGB`/6/9/12-digit forms, scaled `rgb:`
channels, and clipped `rgbi:` channels. The short `#RGB` form uses the most
significant bits (`#3a7` becomes `#30a070`). Optional `@alpha` is validated but
has no effect on these opaque color roles. This syntax is independent of the
older OSC 4/10/11/12 parser.

Dynamic cursor colors resolve the underlying cell's foreground/background;
dynamic selection colors reverse those cell colors. Fixed selection overrides
use the requested colors; resetting restores host selection presentation.
Foreground, background, and indexed colors do not accept dynamic requests.
Queries return `rgb:rrrr/gggg/bbbb`, or an empty value for dynamic colors and
host-controlled selection styling without a fixed color override.
See the [Kitty specification](https://sw.kovidgoyal.net/kitty/color-stack/).

## Color stack

Kitty `OSC 30001 ST` pushes effective indexed and named colors, including
dynamic cursor/selection behavior; `OSC 30101 ST` restores and removes the last
entry. An empty semicolon is also accepted. Both commands require palette
permission and emit no replies or color-scheme notifications.

The terminal-wide stack holds ten entries, dropping the oldest on overflow;
an empty pop has no effect. Hard and soft reset clear it. Screen switches,
resize, and host theme changes preserve snapshots. Pop restores saved effective
color values while retaining the current host light/dark and bold-as-bright
settings; subsequent color resets still use the latest host theme.

## Color resets

OSC 104 restores the specified palette entries to the current host theme's
configured values. Bare `OSC 104 ST` or `OSC 104; ST` restores all 256 entries;
`OSC 104;1;255 ST` restores only entries 1 and 255. Decimal indices must be in
`0..255`. Invalid and empty fields in a nonempty list are ignored individually,
duplicates are coalesced, and a list without valid indices changes nothing.
Non-ASCII bytes reject the complete list.

OSC 110, 111, and 112 restore the theme's foreground, background, and cursor
colors respectively. They accept no payload, with an optional trailing
semicolon. These resets preserve other application overrides and host
presentation preferences. They obey palette permission and emit no replies.
See [xterm's color operations](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html).

## Change reports

Both subscriptions are initially disabled, accept DECSET/DECRST, and are
disabled by RIS or DECSTR. Terminal-response permission gates reports without
changing subscription state; palette permission independently gates application
color operations. Reports use the same ordered session output path as input
and explicit replies.

Mode 2031 (`CSI ?2031h` / `CSI ?2031l`) subscribes to host theme palette changes.
Reports are `CSI ?997;1n` for dark or `CSI ?997;2n` for light, matching the
one-shot `CSI ?996n` query. Changed host palette values report even if the
light/dark preference is unchanged. Enabling alone emits nothing, and
application OSC color changes or resets emit no change reports. See the
[color-scheme protocol](https://github.com/contour-terminal/contour/blob/f3c3334aa5c861348c5bbe8ffe572c872eef2e08/docs/vt-extensions/color-palette-update-notifications.md).

Mode 2048 (`CSI ?2048h` / `CSI ?2048l`) subscribes to text-area size changes.
Every enable request, including repeated enable, immediately reports the
current size as `CSI 48;rows;columns;heightPixels;widthPixels t`. Subsequent
reports follow completed changes to grid dimensions or text-area pixels.
Identical geometry emits nothing. Pixel dimensions exclude padding and are
zero when unavailable. See the
[in-band resize protocol](https://gist.github.com/rockorager/e695fb2924d36b2bcf1fff4a3704bd83).

## Mode status reports

DECRQM returns set (`1`), reset (`2`), or unsupported (`0`). ANSI and DEC namespaces
are independent; no modes claim permanent-set/reset status.

| Namespace | Queryable modes |
| --- | --- |
| ANSI | 4, 20. |
| DEC core | 1, 3, 5, 6, 7, 12, 25, 45, 66, 69, 1004, 1007, 1045, 2004, 2026. |
| DEC change reports | 2031, 2048. |
| DEC mouse tracking | 9, 1000, 1002, 1003. |
| DEC mouse encoding | 1005, 1006, 1015, 1016. |
| DEC alternate screen | 47, 1047, 1049 report the active buffer. |
| DEC Backarrow | 67 reports the application override or current host Backspace default. |
| DEC host actions | 1042/1043 require host capability declarations. |

Mode 3 reports the last DECCOLM selection, not measured window width. Mode 1048
is an action and reports unsupported. Standalone declares supported bell actions;
IntelliJ does not. Requests accept one mode parameter; malformed, overflowing,
cancelled, or incomplete requests receive no reply. Queries do not mutate state.
See the [mode reference](../../ketraterm-protocol/docs/protocol-modes.md) for names.

## Streaming grapheme placement

Printable prefixes are published at each parser call boundary. Segmentation
continues across calls; retained continuations update the same cell with its
original attributes. Core adjusts the following cursor and pending wrap.

Publication commits grid effects. Later width changes do not relocate the
cluster, reverse completed wraps/scrolls/insert shifts, or restore overwritten
text. Narrowing frees a spacer as blank; widening consumes a neighboring cell
when it fits. At the right margin, text remains in the single available cell.
A rejected initial write has no continuation target.

Whole-input and split-input placement can therefore differ at margins, over
occupied cells, and on tiny grids. Retained text, cell integrity, and following
text must still be preserved. See [grapheme retention](../../ketraterm-parser/docs/grapheme-segmentation.md#bounded-retention)
and [verification](../development/conformance-testing.md#streaming-placement).
