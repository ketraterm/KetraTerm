# Terminal Core Contract

Behavioral contract of the standard buffer returned by `TerminalBuffers.create`.
Method KDoc defines individual parameters and validation. The
[feature map](../../docs/terminal-feature-map.md) and
[gap map](../../docs/terminal-feature-gap-map.md) own capability status and
protocol scope.

## Public API surfaces

`TerminalBuffers.create(width, height, maxHistory)` returns `TerminalRenderBuffer`,
which combines `TerminalBuffer` with `TerminalRenderFrameReader` over the same
state. Headless implementations may implement `TerminalBuffer` alone.

`TerminalBuffer` composes these roles:

| Role | Responsibility |
| --- | --- |
| `TerminalWriter` | Text, erase/edit operations, pen, titles, and protection |
| `TerminalCursor` | Positioning, movement, saved cursor, and tab stops |
| `TerminalModeController` | Durable modes, palette, and screen switching |
| `TerminalModeReader` / `TerminalInputState` | Typed or packed mode snapshots |
| `TerminalResponseChannel` | State-dependent query replies and queued bytes |
| `TerminalReader` | Borrowed lines, palette, grid/cursor dimensions, and metadata |
| `TerminalInspector` | Allocating text and attribute inspection |

Depend on the narrowest role needed. The factory requires positive visible
dimensions and nonnegative history capacity; `maxHistory + height` must fit in
`Int`. Zero history capacity disables primary scrollback. Creation installs no
lock: mutations, response access, cursor/grid/history reads, and borrowed frames
require external serialization. Factory mode snapshots are atomic, but cannot
make separately read grid state coherent.

Cursor positioning and cell reads use zero-based coordinates. With origin mode,
positioning is relative to the top margin; with both origin and left/right margin
modes, columns are relative to the left margin. Margin methods and rectangular
commands retain their documented DEC one-based conventions; zero rectangle
parameters mean omitted values. The host adapter performs parser-to-core
coordinate conversion.

### Render frame generations

Frames are valid only within their callback and must not escape it. Nested frame
reads on the same buffer throw `IllegalStateException` before disturbing the
outer frame. Callback failure releases the lease. Copy rows and cluster data
into caller-owned arrays before returning.

Generation values belong to one source. Compare them for equality, including
across overflow; include source and active-buffer identity in retained cache keys.

| Value | Invalidation meaning |
| --- | --- |
| `frameGeneration` | Any visually relevant change |
| `contentGeneration` | Retained cell, authored-output provenance, or terminal-owned row-mapping changes |
| `structureGeneration` | Terminal shape or row mapping changes |
| `lineGeneration(row)` | That rendered row, including global reverse-video interpretation |
| `historyContentGeneration` | Per-buffer retained-history replacement, clear, or resize/reflow |

Ordinary history admission and eviction preserve `historyContentGeneration`;
track row identities and `discardedCount` for those changes. Caller-requested
scrollback offsets do not mutate terminal state and must be included separately
in viewport cache keys. Reverse video preserves stored content, row identities,
and structure generation while invalidating rendered attributes for every row,
including history and the inactive screen. Global invalidation uses a counter
rather than walking retained rows.

`outputEndAbsoluteRow` is the exclusive absolute end of authored output in the
active buffer, independent of the viewport. It includes retained history,
written cells, and explicit blank linefeed sources, and excludes untouched live
capacity. Cursor motion alone does not extend it. Same-row erasure preserves
authored blanks; row replacement resets provenance. Reflow carries authored
output without treating cursor-placement or wide-wrap padding as new text.
Changes advance content and frame generations. The boundary can retreat and
does not imply that the final logical line is immutable or terminated.

`TerminalReader.palette` returns the current immutable effective palette without
acquiring a frame or allocating. Serialize the read; a retained palette remains
safe afterward. Unchanged indexed/dynamic color writes and unsupported targets
preserve palette identity and row generations. Host theme updates replace the
reset baseline even when effective values are equal; only an effective change
invalidates visible rows. Palette equality includes colors and presentation
preferences, including dark/light classification.

## Writer contract

### Printable ingress

- `writeCodepoint` accepts one Unicode scalar. It performs placement and width
  handling without grapheme segmentation.
- `writeAscii` accepts a borrowed printable ASCII range (`0x20..0x7E`) and has the
  same cell/cursor semantics as successive scalar writes. It validates the
  entire range before mutation, does not retain the array, and leaves state
  unchanged for an empty range.
- `writeText` decodes UTF-16 as literal scalars. It does not interpret control
  characters or escape sequences. Use explicit control operations or a parser.
- `writeCluster` accepts one pre-segmented sequence; core computes its width.
  `length` must be in `1..codepoints.size`.
- `updatePreviousCluster` replaces the complete retained sequence of the most
  recently written printable cell. The caller includes the previously published
  prefix; core neither compares prefixes nor validates grapheme boundaries.

Both cluster operations validate and synchronously copy the used prefix, so the
caller may immediately reuse its array. No parser retention limit applies to
direct core callers. Invalid scalar input is rejected before mutation; see
[Unicode scalar and width policy](#unicode-scalar-and-width-policy).

Cluster continuation preserves the target's attributes. Same-width updates
preserve cursor position; in-row width changes adjust the following cursor and
pending wrap against the active right margin. Narrowing clears the released
spacer. Widening overwrites the next cell only when the span fits inside the
margin; otherwise the cluster remains one cell. Updates do not insert or shift
cells, including in insert mode, and do not undo completed wraps, scrolling,
overwrites, or insert shifts. A printable write rejected by geometry or disabled
autowrap clears the target; validation failure preserves it. A valid update with
no remembered target is ignored.

All write paths preserve complete wide/cluster spans. Overwriting a spacer
clears its previous occupant rather than leaving an orphaned leader or handle.

### Erase and edit commands

Structural edits cancel pending wrap. `newLine` advances vertically without a
carriage return; `carriageReturn` moves to the active left boundary. The host
adapter applies newline mode when mapping linefeed controls.

`scrollUp(count)` and `scrollDown(count)` ignore nonpositive counts and cap work
at the region height. They preserve cursor position. Full-width upward scrolling
from the top of the viewport may admit rows to primary history; partial-width
scrolling never does. Downward scrolling does not consume history.

`ICH` / `DCH` operate within horizontal margins; `IL` / `DL` operate within the
vertical region and do nothing when the cursor is outside it. Vertical scrolling
and line edits preserve cells outside horizontal margins, except complete
occupants crossing a slice boundary. `ECH` erases without shifting; zero means
one cell and negative values are ignored. Rectangle operations use their
method-level DEC coordinates and extent rules.

Normal erasure ignores protection; selective erasure respects it. Clears ignore
protection. Clearing operations have different state effects:

| Operation | Effect |
| --- | --- |
| `eraseEntireScreen` | Erases visible cells, preserving cursor and history |
| `eraseScreenAndHistory` | Erases history, preserving visible cells |
| `eraseBuffer` | Erases active screen and history with current erase attributes; preserves cursor, pen, modes, margins, tabs, saved cursor, and inactive screen |
| `clearScreen` | Clears visible cells and homes the cursor; preserves history |
| `clearAll` | Clears active screen and history, resets pen, homes cursor, clears its save slot, and resets tabs; retains margins |
| `reset` | Performs RIS; see buffer lifecycle below |

`updatePenColors` changes only non-null SGR fields. Null means unchanged,
`CellColor.DEFAULT` selects default color, and false clears a flag. Hyperlink IDs
and selective-erase protection survive. It does not change existing cells.

## Cursor contract

Cursor movement clamps to applicable viewport or margin boundaries without
integer overflow. Tab traversal is bounded by viewport width, not the supplied
count. Cursor-affecting commands cancel pending wrap; saving or restoring the
cursor follows the explicit saved-state rules below.

### `DECSC` / `DECRC`

Each screen has a save slot containing column, row, both pen words, pending wrap,
and origin mode. Charset designation and locking shifts remain parser-owned.
The parser selects its charset save slot using the core's actual active screen
through the command sink.

`restoreCursor` clamps saved coordinates to current bounds. Pending wrap is
restored only if the resulting column is the active right margin. Without a
save, it homes to absolute `(0, 0)`, clears pending wrap and origin mode, and
resets the pen.

### Tabs

`HT`, `CHT`, and `CBT` never wrap and stop at applicable horizontal boundaries.
`HTS`, `TBC 0`, and `TBC 3` cancel pending wrap. Resize preserves surviving custom
stops, drops truncated stops, and seeds newly exposed columns with the default
8-column rhythm. RIS, `clearAll`, and DECCOLM restore default stops.

## Mode-state contract

`TerminalModeController` changes durable modes. `getModeSnapshot` returns an
immutable atomic snapshot of the typed fields declared by
`TerminalModeSnapshot`, not an inventory of every mode. The packed snapshot
exposes `TerminalModeBits` plus the xterm resource helpers on
`TerminalInputState`; it includes DECCOLM and resources absent from the typed
snapshot. Capture one word when decoding several fields for one decision.

Existing bit positions, widths, sentinels, and mouse ordinal meanings are stable.
Bits 37..62 hold xterm resources; bits 19 and 63 are unassigned and must be
ignored. Neither snapshot promises space for every future mode.
`keyModifierOption` and `keyFormatOption` decode the same packed word. Resource
setters reject invalid identifiers/values before mutation; family resets restore
defaults atomically while preserving unrelated bits.

Independent `TerminalInputState` implementations can construct resource values
with `withKeyModifierOption` and `withKeyFormatOption`. These pure helpers return
a new packed word, validate supported resources and values, and preserve all
unrelated bits. The producer owns coherent publication; the helpers do not
update a terminal or input state.

Mode setters that home the cursor or affect wrap/margin physics cancel pending
wrap. Input reporting and presentation setters preserve it. `resetCursorStyle`
restores the configured default shape and enables the blink flag without
changing visibility, position, or pending wrap; the host owns blink animation.

Kitty keyboard flags and stacks are screen-local. Each stack retains at most 32
saved entries, evicting the oldest on overflow. A positive counted pop resets
flags when the retained stack is exhausted, including exact exhaustion; a
partial pop restores the last popped flags. Nonpositive counts do nothing, and
work is constant regardless of the requested count. Soft and hard reset clear
both stacks. Serialize these operations with other core mutation.

## Unicode scalar and width policy

`writeCodepoint`, `writeCluster`, and `updatePreviousCluster` accept scalars in
`0..0x10FFFF`, excluding `0xD800..0xDFFF`. Invalid input throws
`IllegalArgumentException` before grid, cursor, or pending-wrap mutation, even
without a continuation target. Cluster APIs validate the entire used prefix;
unused array entries are ignored. `writeText` replaces each unpaired UTF-16
surrogate with U+FFFD and preserves valid pairs. Parser UTF-8 recovery remains
outside core.

Width uses generated Unicode 17.0.0 tables, independently of JDK assignment,
locale, and available fonts:

- Unlisted valid scalars default to one cell, including ordinary unassigned values.
- Reserved wide ranges retain East Asian Width defaults, including unassigned
  CJK values and planes 2 and 3 through `xFFFD`.
- Noncharacters are valid and narrow.
- Private-use scalars and U+FFFD follow ambiguous-width mode.
- Zero-width classification, terminal cell-graphics overrides, and cluster
  variation-selector presentation rules retain precedence.

Reserved defaults follow the pinned
[Unicode East Asian Width data](https://www.unicode.org/Public/17.0.0/ucd/EastAsianWidth.txt).
These are cell-width rules, not host security permissions. They do not add
segmentation to scalar ingress. DECFRA remains a single-cell fill operation and
ignores invalid fill values.

## Reader contract

`TerminalReader.getLine` returns a void line for an invalid row;
`getCodepointAt` returns `0` for an invalid coordinate. On a real borrowed
`TerminalLine`, callers must use valid columns within its `width`; these indexed
methods are not out-of-bounds probes.

Blank cells read as `0`, wide spacers as `-1`, and cluster cells as their base
codepoint. `isCluster` identifies cells whose full payload requires
`readCluster`. Its destination must hold the complete sequence; insufficient
capacity throws `IndexOutOfBoundsException`. Directly written clusters have no
fixed public length bound.

Use `getClusterLength(col)` to size a reusable destination before `readCluster`.
It returns the exact codepoint count, or zero for a scalar, blank, or wide spacer.
Keep the borrowed line, sizing, and copying inside one uninterrupted
serialization boundary. Clustered custom line implementations must override
the sizing operation; its scalar-only default rejects an unsupported cluster.

Alternatively, use
`TerminalRenderFrame.copyLine` and its primitive cluster-data sink. The callback
supplies the full length and a borrowed range to copy before returning. Keep
serialization held for either read path. Render frames expose primitive public
attributes and cell flags; `getAttrAt` offers allocating unpacked inspection.
Raw storage handles and internal arrays are not public APIs.

## Buffer lifecycle contract

### Primary and alternate screens

Each screen has its own cursor, margins, save slot, ring, and cluster store.
Alternate content has no scrollback.

| DEC mode | Core operation |
| --- | --- |
| `47` | Non-clearing alternate switch, without cursor save/restore |
| `1047` | Clearing alternate entry that homes cursor and resets margins; exit does not restore a save slot |
| `1048` | Save/restore cursor without switching screens |
| `1049` | Save primary cursor, enter a cleared alternate screen, then restore on exit |

Actual alternate entry also saves primary cursor shape and blink flag separately
from the DECSC slot; exit restores them. Repeated entry/exit requests are no-ops
and preserve this presentation snapshot. Alternate content survives non-clearing
switches until a clearing entry or effective resize discards it. Resize and soft
reset while alternate is active preserve the primary presentation snapshot; RIS
restores configured default presentation.

### Resize

`resize(newWidth, newHeight, oldScrollbackOffset)` validates positive dimensions
and retained capacity before allocation or mutation. Identical dimensions are a
no-op and return a clamped offset plus current history size.

An effective resize:

- Reconstructs and rewraps primary logical lines when width changes, relocating
  the cursor and deep-copying surviving clusters into a new store.
- Preserves retained primary physical rows, attributes, wrap flags, identities,
  and cluster handles for height-only changes. Untouched trailing rows serve as
  layout capacity; cursor and viewport positions adjust to the new boundary.
- Retains newest rows within configured capacity. A scrolled viewport keeps its
  retained anchor or clamps to the oldest surviving row if it is evicted.
- Wipes and recreates the alternate grid at the new dimensions.
- Resets vertical and horizontal margins on both screens, resizes tab stops,
  clamps saved cursors, and clears active pending wrap.

The returned pair is `(newScrollbackOffset, newHistorySize)` for the active
screen. Alternate returns `(0, 0)` while primary is resized in the background.

### Soft reset

`softReset` implements DECSTR (`CSI ! p`). It preserves visible content,
history, dimensions, tabs, active screen, and cursor position. It resets pen,
protection, margins on both screens, saved cursors to home/default state, and
pending wrap.

Mode defaults restore autowrap, cursor visibility/blinking, normal video, and
normal cursor/keypad/input behavior. Bracketed paste, focus reporting, mouse
tracking, synchronized output, and Kitty keyboard state reset; both xterm
resource families return to defaults. Ambiguous-width policy, mouse encoding,
and the DECCOLM selection survive. Palette and content are preserved.

### Hard reset

`reset` implements RIS (`ESC c`). It returns to primary, clears primary content
and history, homes the cursor, resets pen and terminal modes, restores full
primary scrolling margins and default tabs, clears both Kitty stacks, restores
default cursor presentation, and restores the host theme palette.

Retained alternate cells are not cleared by RIS. A later non-clearing mode `47`
entry can revisit them; clearing alternate entry or an effective resize discards
that content.

### `DECCOLM`

`executeDeccolm` accepts only widths `80` or `132`; other values are ignored.
It resizes both screens, clears the active screen and history, homes to absolute
`(0, 0)`, resets active margins and tabs, cancels pending wrap, and preserves both
saved-cursor slots. With alternate active, primary is reflowed in the background.

The accepted column-mode selection is independent of geometry: it survives
ordinary resize, screen changes, and DECSTR, and resets on RIS. Status queries
observe it without modifying state or render generations.

## Protection contract

Protection applies to selective erasure. `DECSCA` stamps future cells; normal
writes and normal erasure ignore protection. Wide spacers inherit the leader's
protection, and reflow preserves it. Hard clears ignore it.

## Terminal responses

Query methods enqueue replies in `TerminalResponseChannel`.
`readResponseBytes` synchronously copies queued bytes into a caller-owned range;
invalid ranges are rejected without draining the queue or changing the array.
Sessions drain replies after core mutation and send them through the same
serialized outbound path as input. Direct hosts must do that themselves.

The host adapter must enforce terminal-response permission before calling query
methods. Core's explicit capability allowlists govern reply contents and
protocol-specific failure behavior. Denying the response family suppresses
failure replies as well as success replies; supported queries and identities
remain defined by the canonical feature map and protocol module.

## Invariants

Public mutation paths preserve complete wide spans, live cluster ownership,
per-screen arena isolation, tab-stop width, and accurate render invalidation.
Horizontal slices never copy incomplete wide source spans and clear destination
occupants crossing their boundaries. Pending wrap is cleared by cursor/structural
operations that cancel it, and preserved by non-cursor mode changes.
