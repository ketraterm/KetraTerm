# Render Frame Lifecycle & Concurrency Invariants

A render read supplies a consistent borrowed view of one viewport. The API is
synchronous and supports copying primitive data without requiring per-cell
objects. Allocation and synchronization depend on the reader and consumer
implementations.

## 1. Frame Reader & Consumer Synchronization

`TerminalRenderFrameReader.readRenderFrame` invokes
`TerminalRenderFrameConsumer.accept` before returning. An implementation may hold
a mutation lock while invoking the callback. Keep callbacks short: copy rows,
clusters, and metadata, then paint or analyze the copied data after the read.

### Critical Lifespan Rule

The `TerminalRenderFrame` is valid only during its callback. Do not cache it or
send it to another thread. Readers may reuse a frame instance across calls.
Caller-owned destination arrays retain the copied values after the read returns;
the consumer owns their subsequent synchronization.

Nested reads on the same reader need not be supported. An unsupported nested read
must throw `IllegalStateException` without disturbing the enclosing frame. A
consumer should finish its current read before requesting another viewport.

`copyCursor` passes primitive cursor fields to a `TerminalRenderCursorSink`.
Its default implementation adapts the immutable `cursor` value; providers can
override it to avoid creating a cursor object. Blink mode is terminal state;
current blink phase and timing belong to the UI.

<a id="2-monotonic-generation-counters"></a>

## 2. Generation counters

Compare generations for equality or inequality, never for ordering: counters can
wrap and are local to one reader's content source. Include reader identity in
cache keys, and reset caches when unrelated content replaces that source.

| Field | Meaning and invalidation |
| --- | --- |
| `frameGeneration` | Any visually relevant mutation, including cursor changes. A caller-selected viewport still needs its own cache key. |
| `contentGeneration` | Retained cell content and terminal-owned row mapping, including off-screen history. Cursor-only changes need not advance it. Defaults to `frameGeneration`. |
| `historyContentGeneration` | Retained-history identity/layout in the active buffer. Replacement, clearing, and resize/reflow advance it; ordinary live edits, history admission, and eviction do not. Defaults to `contentGeneration`. |
| `structureGeneration` | Terminal-owned row mapping/shape changes, including scrolling, resize, reset, buffer switches, and reflow. |
| `lineGeneration(row)` | Visual cell content, attributes, hyperlinks, cluster text, or wrap status for that visible row. |
| `lineId(row)` | Stable line identity moving with content. `0` means unavailable. |

Include `activeBuffer` alongside `historyContentGeneration`; a non-clearing buffer
switch preserves each buffer's own history counter. Use line identities, line
generations, and `discardedCount` to detect edits and eviction. A history counter
alone does not describe those changes.

Global presentation changes can preserve `contentGeneration`, but must change
`lineGeneration` for rows whose rendered attributes change. Palette, cursor, and
viewport metadata must also participate in the consumer's invalidation strategy.

`outputEndAbsoluteRow` is the exclusive boundary of authored output, independent
of the requested viewport. It includes explicitly authored blank lines and
excludes an untouched live tail. It can retreat after row replacement and is
scoped to `historyContentGeneration`. It does not promise that the last logical
line is complete or immutable. `Long.MAX_VALUE` means the provider does not expose
this boundary.

<a id="3-allocation-free-copy-contracts"></a>

## 3. Primitive Copy Contracts

`copyLine` copies one row in logical terminal-column order. Required destination
arrays are `codeWords`, `attrWords`, and `flags`; optional channels are
`extraAttrWords` and `hyperlinkIds`. Each supplied array needs `columns` entries
from its corresponding nonnegative offset. The row is in `0 until rows`.
Core's frame implementation rejects invalid rows or capacities with
`IllegalArgumentException`.

Use [public cell flags](attribute-packing.md#cell-flags) to distinguish scalar,
cluster, empty, wide-leading, and wide-trailing cells. `codeWords` is zero for
clusters, empty cells, and wide trailing cells. Attribute words remain available
for every cell, including cells with no glyph. A hyperlink ID of zero means no
hyperlink; resolving a nonzero ID belongs to the host/session integration.

Cluster receivers run synchronously during `copyLine`:

- `TerminalRenderClusterDataSink` receives a borrowed code-point array slice.
  Copy that slice before the sink callback returns; a provider may reuse it for
  the next cluster.
- `TerminalRenderClusterSink` receives full grapheme text. Its advertised lifetime
  is the enclosing frame callback unless the provider promises more.
- The `column` argument identifies the logical cluster-leading column in the
  copied row, independent of the destination-array offsets.

Prefer the primitive cluster sink when maintaining a cache. Requesting the text
sink may create strings; providing primitive arrays does not make all reads
allocation-free. Optional channels can be omitted when the consumer does not need
them.

## Viewports and absolute ranges

A scrollback offset is measured in rows above the live bottom viewport. Providers
clamp it to available history and report the resolved value in
`frame.scrollbackOffset`. Requesting a viewport does not mutate terminal state.
The overload with `viewportRows` supports render-only overscan and must not resize
the terminal or change host-visible dimensions.

The default scrollback overload delegates to the bottom-pinned read; the default
overscan overload delegates to the scrollback read. Consumers must use the
returned metadata rather than assume every provider implements these options.

`readRenderFrameForAbsoluteRange(start, end, consumer)` accepts an inclusive range
with `0 <= start <= end`. It exposes a clamped retained frame that can start
before the requested row when the range begins inside the live grid. Intersect
the requested range with the returned frame before extracting text, including
when the entire requested range has been discarded.

For a returned frame, its top absolute row is
`discardedCount + historySize - scrollbackOffset`; visible row `r` is that value
plus `r`. Absolute coordinates continue across history eviction but need source,
buffer, and history-generation context after replacement or reset.

The default range method reads metadata and then makes a relative viewport read.
A stateful caller must serialize the whole operation against mutation, or the
provider must override it to resolve the range and expose the frame atomically.
`TerminalSession` provides that serialization for its readers. For bounded copied
range data, see
[`TerminalRenderRangeCopy`](../../ketraterm-render-cache/README.md).
