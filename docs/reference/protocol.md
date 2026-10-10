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
| Directory (7), palette (4/10/11/12), shell markers (133) | Strict UTF-8; reject malformed commands without updating state. |
| Hyperlinks (8) | Strict UTF-8; malformed or completed oversized commands end active link context without changing retained links. |
| Clipboard (52) | ASCII envelope and strict Base64/UTF-8 text; invalid payloads are rejected before consent or access. |

BEL or `ESC \` completes OSC. CAN/SUB, reset, and EOF discard unfinished commands.
Other C0 controls and DEL are ignored inside OSC. Unknown numeric commands are
ignored unless a custom handler is installed. Recovery preserves later commands.
Host title/notification limits count UTF-16 code units and avoid splitting a valid
surrogate pair; they do not guarantee grapheme-boundary truncation.

## Payload limits

Limits count collected bytes, including family headers/separators and excluding
introducers, terminators, and ignored control bytes.

| Family | Collection limit |
| --- | --- |
| Titles, directory, hyperlinks, notifications, palette 4, shell markers | 4,096 bytes. |
| Dynamic colors 10/11/12 | 256 bytes. |
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
| Device identity | `TERM=xterm-256color`, `COLORTERM=truecolor`, VT420-class primary DA, and secondary DA. No DA3 unique identity. |
| Cursor position | One-based coordinates, relative to active origin/margins when origin mode is enabled. |
| Color scheme | `CSI ?996n` reports dark/light host preference. Application color overrides do not change that preference. |
| Rectangle checksum | Active page only, base VT420 16-bit algorithm; no xterm XTCHECKSUM extensions. |

Denying terminal responses suppresses success and failure replies alike.
Unsupported queries with defined failure responses use those responses rather
than echoing request text.

## Mode status reports

DECRQM returns set (`1`), reset (`2`), or unsupported (`0`). ANSI and DEC namespaces
are independent; no modes claim permanent-set/reset status.

| Namespace | Queryable modes |
| --- | --- |
| ANSI | 4, 20. |
| DEC core | 1, 3, 5, 6, 7, 12, 25, 66, 69, 1004, 2004, 2026. |
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
