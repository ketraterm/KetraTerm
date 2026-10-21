# Swing repainting, viewport geometry, and selection

Painting, damage planning, and pointer hit testing use the same installed
viewport geometry. The following types are internal implementation details;
public viewport and selection APIs are on `SwingTerminal`.

## 1. Minimal Repaint Planning (`SwingRepaintPlanner`)

The EDT-owned planner remembers the state for which it last scheduled a repaint,
including row metadata, cursor state, and a copied search projection. This is
a scheduled state, not a guarantee that Swing has painted it; Swing may coalesce
pending requests. Reusable primitive storage preserves the previous comparison
state when the next projection is built.

- A generation, wrapping, or search-segment change damages the affected row.
  Adjacent damaged rows are combined into one region. Search comparisons inspect
  visible segments rather than traversing all history.
- Cursor changes damage old and new visual cell bounds, except where row damage
  already covers them. Focus changes damage the current cursor; inactive cursors
  do not receive cursor-only blink damage. Text blink damage remains independent.
- Grid shape, buffer, viewport mapping, and graphics-generation changes require a full repaint.
  Chrome or settings changes may force one explicitly.

Search refresh precedes frame damage planning. Query changes and result navigation
also advance the planner's scheduled state between frame publications. For
example, a wrapped match in `abc` / `def` for `cde` damages both rows if the second
row becomes `xef`, because the first row loses its highlight too.

Row metadata capacity is retained across overscan transitions and resets.
`TerminalBidiLayout` likewise retains row permutations and scratch capacity for
unchanged content.

### Image composition

`GridPainter` composes images in three z-index bands: below nondefault cell
backgrounds, above backgrounds but below text, and above text. The final band
also covers row-level selection, search and hyperlink backgrounds; the cursor,
scrollbar and visual-bell overlays paint afterward. This is the current overlap
behavior when an image obscures selected or matched text.

Image positions use physical viewport cells and the installed row geometry.
Contiguous rows share one image draw; prompt dividers split it into separate
runs. Fractional scrolling and content padding use the same translation as text.
Image pixels are copied into EDT-owned rasters during frame installation, never
during painting. Placements
sharing an immutable resource share its raster. Fully opaque resources use an
RGB view of the same pixel storage to enable Java2D's opaque scaling path.
The view releases unused rasters on the next installation and all raster
references on unbinding or disposal.
See the [render lifetime contract](../../ketraterm-render-api/docs/render-frame-lifecycle.md)
for the distinction between borrowed placements and retainable image resources.

The image-paint benchmark measures the actual Java2D path separately from frame
copying, including alpha, scaling, divider clipping and fractional translation.
Java2D may allocate for scaling translucent images or changing clips; raster
reuse alone does not establish allocation-free painting on every backend.

### Smooth viewport ownership

`SwingScrollModel` owns the precise row position, history baseline, and animation.
The integer render anchor is its ceiling; overscan and fractional translation
follow that position. Output anchoring moves the position and destination
without restarting the animation deadline. Following output returns to live
content and cancels motion. History shrink/reset settles on surviving rows.

`SwingViewportController` owns precise-input accumulation and the Swing timer.
An anchor/overscan change requests a frame; movement within the installed window
updates geometry. Scrollbar dragging applies an immediate position. Reset stops
the timer and clears accumulated input; metric changes finish scrolling before
new geometry is installed.

Translation is bounded by the installed frame's coverage while a replacement
window is pending. Painting and hit testing continue to use that installed
geometry. Resize reconciliation uses the session-captured anchor, history size,
and discarded-row count, so reflow discards are not mistaken for new output.
The alternate buffer has zero native history and offset, even when primary
history reflows in the background.

Viewport snapshot reads copy a completed primitive publication under a short
monitor, without EDT dispatch. The EDT is the sole writer and invokes listeners
after releasing the monitor. Animation and scrollbar painting use primitive
metrics rather than constructing public snapshots. Painting and interaction
share scrollbar thumb geometry, including short tracks.

## 2. Selection Drag Matrix & Text Extraction

Linear selections store logical text columns; bidi layout projects them to
visual paint spans. Block selections retain visual column intervals per row,
while copying visits the selected cells in logical text order. Wide cells and
grapheme clusters are included whole. Alt changes during a drag preserve the
original logical and visual anchors, including an offscreen anchor.

Double-click expansion recognizes path/URI characters when the surrounding token
contains `/`, `\`, `.`, or `:`; otherwise it expands by word-character category.
Text extraction omits empty row padding and trailing spaces at hard line ends.
Soft-wrap joining depends on the requested extraction mode; block selections
preserve row breaks.

`SwingTerminal.selectedText()` reads the complete retained selection's current
content on the EDT without touching the clipboard. Linear selection joins soft
wraps; block selection preserves row breaks. Missing selection returns `null`,
while a nonempty range of trimmed blanks may return an empty string. It includes
offscreen rows and closed-session output. Extraction may clip evicted rows or
clear invalidated selection; resulting listener notifications occur outside the
frame lease. Clipboard copying shares this extraction.

`TerminalSelectionRange` uses absolute physical rows and half-open cell edges.
It describes a region, not frozen text. Scrolling preserves it; reflow, history
replacement, rebinding, and observed buffer changes invalidate its context.
Evicted endpoints prevent restoration. Ordinary edits change the selected text.
See the public range and extraction methods for EDT and snapshot contracts.

## 3. Clipboards & Key Mapping Services

Clipboard access belongs to `TerminalClipboardHandler`. The view's copy actions
extract selection text; paste is admitted through the session, which owns paste
transformation, bracketed-paste encoding, and write ordering. The view does not
sanitize protocol bytes itself. See the [manual paste contract](../README.md#manual-paste).

Component focus events are encoded through session input. DEC 1004 determines
whether `CSI I` / `CSI O` reports are emitted; these are focus reports rather than
bracketed-paste sequences. Local cursor presentation does not change that mode.
Mouse reporting takes priority over local interaction. Shift restores local
interaction unless the application requests Shift capture through XTSHIFTESCAPE.
Without tracking, primary-wheel input scrolls history. When
`alternateScreenWheelToArrowEnabled` and application mode 1007 are enabled, local alternate-screen input
accumulates precise deltas and emits bounded arrow-key steps through the input
encoder. It defaults to enabled and also applies when Shift bypasses reporting.
When disabled, local viewport scrolling receives wheel input; unhandled events
remain available to the host. Route/session changes and reloads of either
wheel-routing setting clear partial alternate input. See the
[middle-button paste contract](../README.md#middle-button-paste) for source-aware
clipboard access and deferred host completion.
