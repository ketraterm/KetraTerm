# Protocol limits and replies

This reference records boundary behavior for parser and host implementers.
For a capability overview, see [terminal features](../features/terminal.md).

## CSI parameters

CSI collection has a shared limit of 32 semicolon/colon fields, including omitted
fields. Opening field 33 rejects the entire command; no truncated SGR, mode,
cursor, or query is dispatched. Numeric saturation is independent of this limit.
CAN/SUB cancels, ESC starts a new sequence, and reset/EOF discards unfinished
state. Ordinary C0 handling continues during rejection.

## Relative cursor aliases

Plain CSI movement aliases route to the existing cursor operations:

| Sequence | Name | Equivalent operation |
| --- | --- | --- |
| `CSI Ps a` | HPR | Forward by `Ps` columns (`CSI Ps C`). |
| `CSI Ps e` | VPR | Down by `Ps` rows (`CSI Ps B`). |
| `CSI Ps j` | HPB | Backward by `Ps` columns (`CSI Ps D`). |
| `CSI Ps k` | VPB | Up by `Ps` rows (`CSI Ps A`). |

Omitted and zero counts mean one. Decimal overflow saturates to `Int.MAX_VALUE`,
as with the equivalent cursor commands; core bounds the movement to the active
screen and margins. Origin mode, pending wrap, and enabled reverse wrapping
follow those same operations. Private markers and intermediates do not select
these aliases. The shared [CSI parameter limits](#csi-parameters) and cancellation
rules apply.

These names and encodings follow [ECMA-48, sections 8.3.58–59 and 8.3.159–160](https://www.ecma-international.org/wp-content/uploads/ECMA-48_5th_edition_june_1991.pdf).

## Repeat character

REP (`CSI Ps b`) writes `Ps` additional copies of the preceding graphic, as defined
by [xterm's control sequences](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html).
Omitted and zero counts mean one. KetraTerm repeats the complete retained grapheme
after charset mapping, including spaces, combining sequences, and emoji, bounded
by the existing 32-codepoint retention limit. With no preceding graphic, REP is
ignored. Cursor movement, erasure, SGR, and screen switches preserve the source;
parser reset, RIS, and DECSTR clear it.

Copies use the current pen and width policy, insert mode, margins, autowrap,
and scrolling, as ordinary printable writes do. REP does not copy the original
cell's attributes or read text back from the grid. Counts through `Int.MAX_VALUE`
are accepted; work is proportional to generated output without a count-sized
temporary buffer. Numeric overflow, extra fields, colon subparameters, private
markers, and intermediates reject REP. The shared [CSI limits](#csi-parameters)
and cancellation rules apply.

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
| Progress (9;4) | ASCII decimal fields; malformed envelopes preserve prior progress and never become notifications. |
| Pointer shape (22) | Exact ASCII CSS names from the supported subset, optionally prefixed with `=`; empty name restores host defaults. Unknown or malformed names leave state unchanged. |

BEL or `ESC \` completes OSC. CAN/SUB, reset, and EOF discard unfinished commands.
Other C0 controls and DEL are ignored inside OSC. Unknown numeric commands are
ignored unless a custom handler is installed. Recovery preserves later commands.
Host title/notification limits count UTF-16 code units and avoid splitting a valid
surrogate pair; they do not guarantee grapheme-boundary truncation.

## Progress reporting

`OSC 9 ; 4 ; state ; percentage ST` reports application-controlled progress;
BEL also terminates the command. The [Microsoft protocol documentation](https://learn.microsoft.com/en-us/windows/terminal/tutorials/progress-bar-sequences)
defines the following states:

| State | Presentation |
| --- | --- |
| `0` | Hidden; clears progress. |
| `1` | Normal determinate progress. |
| `2` | Error determinate progress. |
| `3` | Indeterminate work; ignores the percentage. |
| `4` | Warning/paused determinate progress. |

Percentages are inclusive `0..100`. Determinate zero remains zero, and 100
remains visible until cleared. Hidden/indeterminate snapshots use percentage
zero. Progress is independent of shell command metadata and never triggers a
notification or a terminal reply.

KetraTerm defaults missing/empty state and percentage fields to zero. It rejects
unknown states, out-of-range/overflowing numbers, signs, whitespace, non-ASCII
fields and extra fields. The ordinary 4,096-byte OSC envelope limit applies;
oversized or cancelled reports leave prior progress unchanged. [Windows Terminal's parser](https://github.com/microsoft/terminal/blob/main/src/terminal/adapter/adaptDispatch.cpp)
also implements tolerant conventions such as clamping values above 100; those
are outside this documented slice.

`HostPolicy.progressPolicy` authorizes progress as presentation metadata,
independently of title, notification, window-manipulation and terminal-response
permissions. It defaults to allow. `HostEventSink.progressChanged` runs
synchronously with accepted reports, including equal reports, under the existing
host callback contract. RIS always emits hidden progress; DECSTR, buffer switches
and local screen clearing preserve it.

The standard session retains an immutable `TerminalProgress` in
`TerminalSession.progress`, a read-only `StateFlow`. It initially contains
`HIDDEN`, conflates intermediate/equal presentation states, and does not complete
on closure. Consumers own their collection lifetime. Reports received before a
collector starts are retained. Identical accepted reports renew freshness.
A single atomic retained report and lazy expiry worker bound pending work per session.

A session clears progress after five minutes without an accepted report, on
RIS, progress-permission revocation, or immediately when closure begins. Expiry uses
monotonic time and needs no collector. The five-minute limit is KetraTerm product
policy, not a protocol requirement. Reallowing permission does not restore stale
progress; an application must send a new report.

Both products replace the shell icon with a thin progress ring while progress is
active. Normal, error and warning states use accent, red and amber arcs filled
according to the percentage; indeterminate work uses a rotating arc. Tab names
remain unchanged. Localized tooltips/accessibility text retain the numeric/status
information, and the selected session's window/tool-window title retains status.
Hidden progress restores the shell icon. Both products always enable progress independently of
the existing title permission. Library embedders control progress separately
through `HostPolicy.progressPolicy`.
Standalone additionally uses per-window taskbar state/value APIs where supported;
IntelliJ retains ownership of the IDE's global taskbar. A standalone split tab
uses its active pane, following the existing title-selection convention. Queued
UI refreshes are bounded and read the latest retained state at delivery, so a
late callback cannot restore cleared progress.

Custom parser sinks opt in through `TerminalProgressCommandSink`; older plain
`TerminalCommandSink` implementations remain valid and ignore these reports.
Decorators must preserve the optional capability explicitly. Custom session
assembly owns its host-event wiring; automatic retained progress is supplied by
`TerminalSession.create`.

The optional Swing-host `SwingTerminalProgressIcon` is a host-owned, EDT-confined
painter with configurable size, colors, phase and accessible description. It owns
no session, observation job or timer. `SwingTerminalProgressAnimation` supplies
one explicitly closed, visibility-bound clock per host container; callers enable
it only for indeterminate work. Embedders can omit the helper or drive phase with
their own animation/reduced-motion policy.

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

OSC/DCS limits count collected bytes, including family headers/separators and
excluding introducers, terminators, and ignored control bytes. Graphics APC uses
separate header and payload budgets.

| Family | Collection limit |
| --- | --- |
| Titles, directory, hyperlinks, notifications/progress, palette 4/21/104, shell markers | 4,096 bytes. |
| Dynamic colors 10/11/12/110/111/112 | 256 bytes. |
| Pointer shape 22 | 32 bytes. |
| Color stack 30001/30101 | 6 bytes, including the command number and optional empty semicolon. |
| DECRQSS | 64 bytes. |
| XTGETTCAP | 4,096 bytes. |
| Custom OSC | 4,096 bytes by default; configurable full-envelope limit, with a separate 4,096-byte header limit. |
| OSC 52 | Ordinary 4,096-byte limit; eligible writes use a budget derived from the active decoded-text limit. |
| Graphics APC | Separate header, encoded-chunk and decoded-transfer bounds; see [graphics collection](../../ketraterm-parser/docs/ansi-fsm-specification.md#graphics-apc-collection) and [resource limits](../development/kitty-graphics-contract.md#resource-limits). |

Overflow stops collection and consumes through termination/cancellation without
dispatching a truncated prefix. An oversized OSC 8 ends active link context;
other rejected commands preserve prior metadata. Oversized DCS queries receive
no reply. DCS terminates with ST, not BEL.

Graphics APC drains rejected payloads through ST. Unknown/malformed headers are
discarded; identified transfer-limit failures can produce fixed failure replies
subject to quiet flags and response permission. Its per-chunk accounting excludes
the APC header, separator and terminator.

Session-integrated clipboard writes permit up to 1 MiB of decoded text by default.
Standalone parsers without that integration retain the ordinary collection limit.
Permissions and decoded-size limits are checked again before execution.

## Query allowlists

The optional PowerShell completion exchange below is host-initiated semantic
input. Receiving its OSC metadata never sends a terminal reply, executes command
text, or requests completion by itself; it adds no terminal-response allowlist entry.

| Query | Reply scope |
| --- | --- |
| DECRQSS | SGR (`m`), vertical margins (`r`), horizontal margins (`s`), cursor style (`SP q`). Unsupported selectors receive empty failure replies. |
| XTGETTCAP | Identity, colors, parameterized scrolling, paste delimiters, underline capabilities and legacy keyboard names from the [explicit allowlist](#xtgettcap). Each well-formed name receives its own success or failure frame. |
| Kitty colors (OSC 21) | Indices 0–255, `foreground`, `background`, `cursor`, `cursor_text`, `selection_foreground`, `selection_background`. Unknown keys receive `unknown=` plus unpadded Base64 of the original key. Palette permission gates the command; terminal-response permission additionally gates every reply. |
| Kitty graphics (APC G) | Identified inline RGB/RGBA and PNG requests, including zlib transfers, validate actual data before success; unsupported formats/media/actions return fixed `ENOTSUP` errors. Queries require nonzero `i`. Quiet flags and terminal-response permission govern every reply; no window-manipulation permission is required. |
| Device identity | `TERM=xterm-256color`, `COLORTERM=truecolor`, VT420-class primary DA, and secondary DA. No DA3 unique identity. |
| XTVERSION | Product/build identity from `CSI > q` or `CSI > 0 q`; see the [reply contract](#xtversion). |
| Cursor position | One-based coordinates, relative to active origin/margins when origin mode is enabled. |
| Window geometry | `CSI 14 t` reports text-area pixels and `CSI 16 t` reports cell pixels as `CSI 6;height;width t`; pixel reports require known positive dimensions. Cell dimensions use integer text-area pixels divided by grid dimensions, independently of margins. `CSI 18 t` and `CSI 19 t` report the terminal grid. These queries require terminal-response permission, not window-manipulation permission. |
| Mode status | ANSI/DEC DECRQM replies for the [listed modes](#mode-status-reports), including permanently enabled grapheme mode 2027. Unknown modes report `0`. |
| Color scheme | `CSI ?996n` reports dark/light host preference; mode 2031 permits automatic reports for host theme changes. Application color overrides and resets do not change that preference or emit change reports. |
| In-band resize | Mode 2048 permits `CSI 48;rows;columns;heightPixels;widthPixels t` reports on enable and after text-area size changes. |
| Rectangle checksum | Active page only, base VT420 16-bit algorithm; no xterm XTCHECKSUM extensions. |

Denying terminal responses suppresses success and failure replies alike.
Unsupported queries use their defined failure frames rather than replaying
arbitrary request text.

## XTVERSION

`CSI > q` and `CSI > 0 q` return `DCS > | KetraTerm (version) ST`, using the
library build version, including `-SNAPSHOT` for development builds. The identity
is at most 76 printable ASCII characters.
Existing DA identity and `$TERM` values remain unchanged.

Terminal-response permission gates the entire reply. Nonzero, extra, colon or
overflowing parameters are ignored. XTVERSION defines no failure reply. Custom
parser sinks and response channels may leave the optional `requestTerminalVersion()`
default unimplemented; those integrations stay silent.

## XTGETTCAP

Requests use `DCS +q hexName[;hexName...] ST`. Allowlisted names are case-sensitive
ASCII. Each request token encodes bytes using two ASCII hex digits per byte;
hex digits may use either case. The responder uses the following immutable allowlist:

| Names | Type | Decoded value |
| --- | --- | --- |
| `TN`, `name` | String | `xterm-256color` |
| `Co`, `colors` | Number | Decimal `256` |
| `RGB`, `Tc` | Boolean | Present, without a value; preserves KetraTerm's existing truecolor contract. |
| `indn` | String | `ESC [%p1%dS` |
| `PS`, `PE` | String | `ESC [200~`, `ESC [201~`; paste delimiters used when mode 2004 is enabled. |
| `Su` | Boolean | Styled and colored underlines. |
| `Smulx` | String | `ESC [4:%p1%dm`; parameter 0–5 selects none, single, double, curly, dotted, or dashed. |
| `Setulc` | String | `ESC [58:2::%p1%{65536}%/%d:%p1%{256}%/%{255}%&%d:%p1%{255}%&%dm`; parameter is packed 24-bit RGB. |

Here `ESC` denotes the actual byte `1b`, and the following space is notation,
not part of the value. Values contain terminfo parameter expressions literally;
the application expands them. Numeric values are decimal ASCII before hex encoding.
No local terminfo database is consulted.

Keyboard entries are strings describing legacy key presses in the current mode:

| Terminfo / termcap names | Decoded value |
| --- | --- |
| `kcuu1` / `ku`, `kcud1` / `kd`, `kcuf1` / `kr`, `kcub1` / `kl` | Normal cursor mode: `ESC [A/B/C/D`; application cursor mode: `ESC OA/OB/OC/OD`, respectively. |
| `khome` / `kh`, `kend` / `@7` | Normal: `ESC [H/F`; application: `ESC OH/OF`. |
| `kich1` / `kI`, `kdch1` / `kD`, `kpp` / `kP`, `knp` / `kN` | `ESC [2~/3~/5~/6~`, respectively. |
| `kf1`–`kf9` / `k1`–`k9`; `kf10` / `k;`; `kf11` / `F1`; `kf12` / `F2` | F1–F4: `ESC OP/OQ/OR/OS`. F5–F12: `ESC [15~/17~/18~/19~/20~/21~/23~/24~`. |
| `kbs` / `kb` | BS (`08`) or DEL (`7f`), according to DECBKM or the current host Backspace policy. |
| `kcbt` / `kB` | Shift-Tab: `ESC [Z`. |
| `kHOM` / `#2`, `kLFT` / `#4`, `kRIT` / `%i`, `kEND` / `*7` | Shift+Home/Left/Right/End. Default cursor modifier level 2: `ESC [1;2H/D/C/F`. |
| `ka1` / `K1`, `ka3` / `K3`, `kc1` / `K4`, `kc3` / `K5` | Application keypad corners: numeric keypad 7/9/1/3 send `ESC Ow/Oy/Oq/Os`. Numeric keypad mode receives failure replies. |

Slash-separated sequences above denote alternatives, not literal slash bytes.
One coherent mode snapshot is used for the whole request. Queries do not enable
application modes or change input policy. Applications must query again if they
need an answer for a different mode.

The four Shift cursor entries follow xterm's cursor modifier resource (1).
Here `final` is `H`, `D`, `C` or `F`, respectively; `CSI` is `ESC [`
and `SS3` is `ESC O`. Spaces below are notation, not transmitted bytes:

| Cursor modifier level | Normal cursor mode | Application cursor mode |
| --- | --- | --- |
| Disabled (`-1`) | `CSI final` | `SS3 final` |
| `0` | `CSI 2 final` | `SS3 2 final` |
| `1` | `CSI 2 final` | `CSI 2 final` |
| `2` | `CSI 1;2 final` | `CSI 1;2 final` |
| `3` | `CSI >1;2 final` | `CSI >1;2 final` |

Disabling cursor modifiers drops Shift, matching actual key encoding.
Function, special and ordinary-key resource settings do not change these entries.

Keypad corner discovery requires application keypad mode (DECKPAM, `ESC =`);
DECKPNM (`ESC >`) restores numeric mode. These controls share the state selected
by DECSET/DECRST 66. It is independent of the application cursor
mode and follows the keypad modifier resource (3). Numeric-mode digits are not
advertised as function-key sequences, so consumers do not capture ordinary
`7/9/1/3` input. These names describe numeric keypad identities, not the separate
keypad Home/End/Page Up/Page Down identities or dedicated editing keys.
Num Lock can override individual legacy keypad events; it does not change
application mode or capability discovery.

These entries describe the legacy press vocabulary only. While any Kitty keyboard
flags are active, keyboard names receive failure replies. Xterm resource level 4
declines only that resource's keys (cursor/editing, function, keypad, or special keys).
`modifyOtherKeys` level 2 additionally declines Shift-Tab; level 3 declines both
Shift-Tab and Backspace. Other entries still reply normally, including output
capabilities. Resetting the relevant mode restores discovery.

The host adapter supplies its current Backspace default through the optional
`setTerminfoBackspaceDefault` hook, then invokes the original
`TerminalResponseChannel.queryTerminfo(String)` method. Existing Kotlin wrappers
still intercept that method. The standard core retains supplied metadata across
RIS/DECSTR; explicit DECBKM takes precedence. Direct core callers supply the
default before querying Backspace.

Without supplied metadata, `kb`/`kbs` fail unless DECBKM makes the answer known.
This also applies to retained JVM wrappers that cannot forward the new hook.
Other capability replies remain available. The hook has a default no-op for
existing implementations and does not change input encoding or terminal modes.

KetraTerm follows Kitty-style individual replies: each supported name produces
`DCS 1+r hexName[=hexValue] ST`, and each unknown name produces
`DCS 0+r hexName ST`. Boolean successes omit `=hexValue`. Order, duplicates,
and the original hex spelling are preserved; an unknown name does not stop later
names. Well-formed encodings of other bytes, including non-ASCII identifiers,
also receive keyed failure replies. This differs from xterm's combined-response
and stop-at-unknown behavior.
Previously KetraTerm combined recognized entries and omitted unknown entries;
clients consuming batched replies must accept the individual frames.

An empty list/token, odd-length token, or character outside ASCII `0–9`, `a–f`,
and `A–F` rejects the whole batch with `DCS 0+r ST`. Validation and encoded-size preflight
finish before any replies from that batch are queued. A complete response larger
than 64 KiB is rejected the same way, including through the direct core API.
Earlier queued replies remain intact. The parser's 4,096-byte collection ceiling
includes `+q`; overflow drains the string without dispatch or a partial reply.

The existing terminal-response permission gates all frames, including failures.
Window-manipulation and palette permissions do not gate capability discovery.
Direct core callers enforce host permission themselves. Query handling neither
executes advertised templates nor changes grid, cursor, modes, or attributes.

Protocol references: [xterm](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html),
[Kitty responder](https://github.com/kovidgoyal/kitty/blob/master/kitty/terminfo.py).
The [ncurses fixtures](../../ketraterm-host/src/test/resources/xtgettcap/README.md)
verify template expansion and resulting terminal behavior without native test dependencies.
The [keyboard fixtures](../../ketraterm-session/src/test/resources/xtgettcap/README.md)
pin Vim's query list and verify advertised values against actual input encoder output.

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

DECRQM returns set (`1`), reset (`2`), permanently set (`3`), or unsupported (`0`).
ANSI and DEC namespaces are independent.

| Namespace | Queryable modes |
| --- | --- |
| ANSI | 4, 20. |
| DEC core | 1, 3, 5, 6, 7, 12, 25, 45, 66, 69, 1004, 1007, 1045, 2004, 2026. |
| DEC grapheme processing | 2027 always reports permanently set (`3`). |
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

`CSI ?2027$p` replies `CSI ?2027;3$y`: grapheme processing is always enabled.
DECSET/DECRST 2027 leave that policy unchanged, including across RIS, DECSTR,
screen switches, and resize. ANSI mode 2027 is unsupported. Replies use the
normal terminal-response permission; no window permission is required.
This advertises grapheme processing, not complete conformance to the original
[Unicode Core draft](https://github.com/contour-terminal/terminal-unicode-core/blob/master/spec/terminal-unicode-core.tex):
KetraTerm retains its documented [VS15/VS16 width policy](../../ketraterm-core/docs/terminal-core-contract.md#unicode-scalar-and-width-policy)
and [streaming placement](#streaming-grapheme-placement).

## Private PowerShell completion metadata

The same optional producer also accepts the bounded Bash command inventory
[described below](#private-bash-command-inventory).

The optional OSC shell producer accepts `OSC 633;KetraTermCompletion;3;... BEL`
(ST is also accepted) from the workspace's PSReadLine hook. All envelope fields
are printable ASCII; text fields are standard Base64 of strictly decoded UTF-8.
Other completion protocol versions are ignored.
The session's default parser forwards unknown OSC bodies synchronously to the
selected producer. Custom parser factories must forward `observeCustomOsc` and
use `customOscPayloadLimitBytes` themselves.

| Message body after `KetraTermCompletion;3;` | Meaning |
| --- | --- |
| `Ready;epoch` | Prompt completion hook, with 32 lowercase hexadecimal epoch characters. |
| `Ready;epoch;PrefixReset` | Also advertises the installed `F12,r` prefix-reset binding. Plain `Ready` does not advertise this capability. |
| `Ready;epoch;BufferState` | Also advertises `F12,b` buffer-only confirmation. It may be combined with `PrefixReset`, in either order; repeated or unknown capabilities are rejected. |
| `Result;epoch;id;Failed` | Correlated failure. |
| `Result;epoch;id;Ok;cursor;start;end;line;externalCommandName;count;entries...` | Exact live buffer and UTF-16 replacement range, with Base64 `line` and `externalCommandName`. The latter is empty for shell commands, functions, scripts, or unresolved invocations; otherwise it names the resolved application after following aliases. Names are data and must never be evaluated. Each entry has four fields: native result type, Base64 replacement, Base64 label, Base64 tooltip. |

The session assigns each query a positive signed 64-bit request ID, never reused during
that session, including across prompt changes. It admits at most one query at a
time and sends the ID through ordered semantic input as one frame:

1. `F12,q` starts the frame.
2. Nineteen `F12,digit` chords carry the decimal ID, padded with leading zeroes.
3. `F12,e` commits the frame and invokes the completer. With `BufferState`,
   `F12,b` instead commits a buffer-only confirmation request.

Buffer-only confirmation reads the current editor text and UTF-16 cursor without
invoking candidate completers or changing the editor. It returns the same `Result`
envelope with `start = end = cursor`, an empty application name and zero candidates.
Its editing receipt cannot satisfy a later candidate request. Full candidate queries
can also confirm the same buffer; older hooks without `BufferState` use full queries
for confirmation.

The hook echoes the supplied ID and its current prompt epoch; it keeps no
independent request counter. Missing, incomplete, zero, overflowing, or otherwise
invalid frames are ignored without editing the buffer or invoking completion.
Starting a new frame or announcing `Ready` discards any partial frame. Commit
consumes the frame once, including when validation fails, and invalid frames emit
no reply.
Replies must match the active prompt epoch and pending request ID, as well as
the captured command and cursor. A request processed after a prompt change cannot
satisfy a later query because its ID remains distinct. Wrong epochs or IDs are
ignored; a matched malformed reply fails its query. Unsolicited result metadata
has no editing authority.

The hook samples PSReadLine's input buffer and cursor before invoking completers,
then checks both again immediately before emitting a successful result. If either
differs, or the final read fails, it emits `Failed` without candidates. This also
applies when a completer throws after changing the editor. Terminal repainting
does not itself change that buffer. The host treats this correlated buffer
confirmation separately from its screen-derived command observation; see the
[conditional-edit contract](../../ketraterm-session/docs/session-concurrency-locks.md#conditional-command-edits)
for its lifetime and final admission checks.

`Ready` is accepted only within an active primary-screen OSC 133 prompt, after
`A`; it may precede `B`. Command start/finish (`C`/`D`), a local primary-buffer
clear, or an observed alternate-screen transition revoke the completion hook.
A new `A` also clears the previous epoch and requires a fresh announcement.
Prompt announcements never reset the session's request IDs. Every accepted
announcement invalidates earlier editing contexts and pending results,
including announcements that change only `PrefixReset` or `BufferState`.
The hook reinstalls its private PSReadLine bindings before each `Ready` announcement.
Readiness advertises the prompt's hook, not completion of prompt rendering or
PSReadLine initialization. The query handler reads the editor when the shell
processes the completed request frame.

`PrefixReset` lets acceptance clear the text before the real cursor through
`PSConsoleReadLine.Replace(0, cursor, "")`, then paste the edited prefix in the same
ordered input operation. Text after the cursor is preserved. This avoids relying
on version-dependent Delete/Backspace behavior. The binding does not execute the
command and preserves `LASTEXITCODE`. This capability has the same prompt lifetime
as the completion hook.

Complete custom OSC collection is capped at 256 KiB with this producer installed;
built-in command limits remain unchanged. Replies contain at most 128 candidates,
a 4096 UTF-16-unit command and replacement, a 512-unit label, and a 1024-unit
tooltip per candidate. Ranges must contain the cursor and stay within the buffer
on scalar boundaries. Command, application-name, and replacement controls are rejected. Application names are limited to 512 UTF-16 units. The hook
bounds its serialized body below the collection limit and preserves `LASTEXITCODE`.

Empty command buffers use bounded `Get-Command` discovery. The hook uses PowerShell's
parser to quote command names that require explicit invocation. For simple executable
names it removes an extension only after resolving the long and short invocation
to the same application/script path and type. Shadowing functions and aliases do
not authorize that normalization. The command authority and catalog enrichment
policy belongs to the [completion engine](../../ketraterm-completion/docs/completion-architecture.md#internal-implementation).

Each caller's wait expires after ten seconds, allowing cold PowerShell module
discovery. Callers can cancel earlier. Native completers run in the shell and may
continue after host cancellation. See the session's
[query lifecycle and conditional editing contract](../../ketraterm-session/docs/session-concurrency-locks.md#conditional-command-edits)
for shared queries, wire-slot retirement, and validation before suggestion acceptance.

## Private Bash command inventory

Bash and zsh share a versioned snapshot protocol. Each message uses
`OSC 633;KetraTermCommands;3;... BEL` for PATH executables and
`OSC 633;KetraTermShellCommands;3;... BEL` for shell-local commands (or ST).
Each source has independent epochs, scan order, deadlines, and atomic snapshots:

Workspace PATH workers write their `Begin`/`Chunk`/`End` or `Fail` envelopes to
`path.pending` in a private session directory, then rename it to `path.ready`.
They never write to the PTY: concurrent terminal writes can split and interleave
OSC envelopes with Readline/ZLE redraws or prompt markers. `Ready` and shell-local
metadata remain foreground output. The host supplies the mailbox through
`KetraTerm_COMMAND_INVENTORY_DIRECTORY` (WSL receives a translated guest path).
The optional `ShellCommandInventoryInput` accepts only complete BEL-terminated
PATH envelopes, at most 8 MiB encoded and 258 frames, and checks the live epoch
before passing records to the same inventory decoder. It cannot inject terminal
text, prompt markers, native completion replies, or environment announcements.
File arrival may precede `Ready`; the host retains one bounded pending file and
retries, replacing it when a newer file arrives. Terminal bytes are never injected
or replayed to deliver file metadata. Session closure detaches the input.

| Suffix | Meaning |
| --- | --- |
| `Ready;epoch` | Announce the current shell environment. |
| `Begin;epoch;scan` | Start a complete replacement snapshot. |
| `Chunk;epoch;scan;sequence;data` | Append complete records; sequences start at zero. |
| `End;epoch;scan;chunks;entries;bytes` | Verify exact totals and atomically publish. |
| `Fail;epoch;scan;reason` | Abandon the scan (`Overflow` or `Unavailable` from workspace hooks). |

Epochs have 32 lowercase hexadecimal characters; scan IDs are positive increasing
integers within an epoch. A changed Ready epoch clears that source's old command authority and
publishes `PENDING`. Same-environment refresh keeps the last complete snapshot,
including when refresh fails. Without a complete snapshot, failure publishes
`UNAVAILABLE`; it does not enable unverified catalog fallback. Other ready sources
remain usable. Shell-local names override matching PATH entries. The merged
snapshot enforces the same entry/text limits; combined overflow publishes
unavailable rather than truncating. READY can contain one ready source while
another is pending. Retained source snapshots share candidate strings with the
merged index; at most two bounded source assemblies can exist. Null session inventory
means no installed producer. Superseded epoch/scan messages cannot publish results.

`data` is standard Base64 of UTF-8 NUL-terminated quadruples: display name,
shell-escaped insertion, kind (`C` command or `A` alias), and literal alias definition.
An empty insertion reuses the name. Command definitions must be empty; empty alias
definitions also represent definitions exceeding the producer's 4096-byte bound.
Definitions are never executed. Zero chunks/entries/bytes at End publishes a complete
empty inventory. Duplicate names, controls in names/insertions, invalid UTF-8,
unknown kinds, incomplete records, missing/repeated/out-of-order chunks, and
incorrect totals reject the entire scan.

Decoded chunks are at most 48 KiB, with at most 256 chunks, 4 MiB decoded bytes,
32,768 entries, and 2,097,152 retained UTF-16 text units per snapshot. Names allow
512 units and insertions/definitions 4096 each. Identical name/insertion strings
share storage. Workspace hooks enforce conservative UTF-8 byte budgets for text.
The existing 256 KiB custom OSC bound still applies independently. Overflow emits
Fail, never a successful truncated End. Assemblies expire after 60 seconds when
protocol/output activity next checks the deadline; idle partial buffers remain
bounded and are released at session closure. Completed snapshots expose an immutable
sorted list, binary prefix lookup, and a cached alias map.

The workspace PATH collector streams immediate directory entries under separate
scan budgets: 4,096 PATH components, 131,072 entries, and 16 MiB of entry bytes
(including `./` and NUL delimiters). Components count even when empty, repeated,
or inaccessible; entries count before duplicate, executable, or directory filtering.
Emitted names remain limited to 32,768 unique executables and 2 MiB including
NUL terminators. Exceeding any budget emits `Overflow`. The collector requires a
compatible `find` from PATH or the shell's standard utility path. Missing tools
and incomplete enumeration emit `Unavailable`. These budgets bound the collector's
own storage and work, not the external utility's memory or filesystem call duration.

Workspace hooks check shell-local state and PATH independently at each prompt,
with at most one background PATH worker. Bounded shell-local state publishes
at the prompt, independently of PATH I/O. Unchanged PATH discovery is eligible 60 seconds after a prompt observes its
worker completing. Shell-local changes reuse executable authority; only PATH
changes, or directory changes with relative/empty PATH entries, invalidate it.
PATH changes during an active worker require a subsequent prompt after it finishes.
This is one-way shell
metadata, not a terminal query: it emits no host reply, installs no key binding,
and grants no editing authority. Consumers retain ordinary conditional-edit checks.
It supplies command availability and alias definitions for semantic argument analysis;
it does not advertise native argument or remote filesystem completion.
See [completion scope](../features/completion.md#sources-by-host).

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

## Screen-to-history clear

Kitty ED 22 (`CSI 22 J`) retains the primary display in bounded scrollback, then
clears the viewport like ED 2. Text rows move with their attributes, grapheme
payloads, line identities, and soft-wrap provenance. Images retain their immutable
pixel resources; off-bottom image portions are cropped before retention, and
history eviction clips or removes placements normally.

Trailing rows without stored text or visible images are omitted. Explicit spaces
count as text; blank rows between occupied rows remain. An empty display does
not add history. Existing history is preserved up to the configured capacity;
zero capacity still clears the display.

Retention requires the primary screen and a full-height vertical scroll region.
On alternate screens or with partial-height regions, ED 22 only clears. Horizontal
margins do not restrict the operation. Cursor position, pen, modes, margins, tabs,
saved cursor, and inactive screen survive; pending wrap is cancelled. Cleared
cells use the current erase attributes and fresh line identities, as for ED 2.
Protection does not prevent this ordinary erase. The selective form `CSI ? 22 J`
is unsupported and ignored. Colon subparameters and extra parameters on ED 22
are rejected without erasing or admitting history.

Reference: [Kitty's screen-to-scrollback extension](https://sw.kovidgoyal.net/kitty/misc-protocol/#an-escape-code-to-move-the-contents-of-the-screen-into-the-scrollback).
