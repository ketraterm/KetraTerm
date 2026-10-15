# Grid Storage Layout & Memory Architecture

This is an internal storage guide, not a public encoding contract. Consumers use
`TerminalReader`, `TerminalLine`, and render-frame copies; raw arrays and cluster
handles remain private to core.

## 1. Parallel Array Cell Storage (`Line`)

Each physical [Line](../src/main/kotlin/io/github/ketraterm/core/model/Line.kt)
holds parallel primitive arrays:

| Storage | Type | Contents |
| --- | --- | --- |
| `codepoints` | `IntArray` | Scalar, empty cell, wide spacer, or cluster handle |
| `attrs` | `LongArray` | Primary packed attributes |
| `extendedAttrs` | `LongArray?` | Extended attributes; absent storage reads as zero |

`extendedAttrs` is allocated on the first nonzero extended write and retained
through clears for reuse. A full-row zero overwrite clears the retained array
once, then marks it dormant. Subsequent zero writes, erases, and shifts skip
extended-array work until a nonzero write reactivates it. Partial zero edits
conservatively keep active storage; there is no per-cell count or scan to detect
the last nonzero word. Stored values remain zero while dormant, so reads and
partial reactivation cannot expose old styling.

An exact live-payload count lets scalar-only erases skip cluster accounting.

### Invariants:

- Terminal grids have at least two columns, so a wide occupant always fits on one row.
- `0` denotes an empty cell.
- Positive values hold direct Unicode scalars, including supplementary values.
- `-1` is the trailing spacer of a two-cell occupant.
- Values `<= -2` identify references into that row's own cluster payload array.
- Attributes use the same column index as the cell value. Overwriting or erasing
  a cluster marks its payload dead. Shifts within a row move its references;
  copies between rows copy the actual codepoints.

Rows also carry a soft-wrap flag, wide-wrap padding provenance, authored-output
provenance, line identity, and render generation. These are distinct facts:
erasing text can retain a row's identity and authored blank output, while
replacing the row creates a new identity. Reflow excludes artificial wrap
padding from logical text.

A public `TerminalLine` decodes cluster handles to their base codepoint.
`readCluster` copies the complete payload into caller-owned storage. Render
copies translate internal packed attributes and cluster references into the
stable encodings in `ketraterm-render-api`.

## 2. Off-Screen History Ring (`HistoryRing`)

A screen's [HistoryRing](../src/main/kotlin/io/github/ketraterm/core/buffer/HistoryRing.kt)
contains both retained history and live viewport rows, oldest first. Primary
capacity is `maxHistory + height`; alternate capacity is `height`.

The reference table is allocated at full capacity, while row objects are
constructed for the initial viewport plus a batch of spare rows. Additional
batches are allocated as output fills the ring. Once capacity is reached,
`push()` reuses the oldest row and advances eviction accounting. The caller
clears recycled contents, resets the payload append cursor, and assigns a new
identity before exposing the row again.

Full-width region scrolling rotates row references rather than copying each
cell. Partial-width scrolling copies only the selected cell slices and does not
admit rows to history. History destruction drops logical reachability without
scanning discarded cells or releasing external resources. Spare rows retain
their own arrays and stale contents for reuse, bounded by the ring's allocated
row count. All spare rows are fully initialized before becoming live again.
Retaining the newest rows swaps only surviving row references; it is linear in
the retained row count.

Height-only primary resize adjusts the reference table while retaining surviving
row arrays and payloads. A width change reconstructs logical lines into new
rows. Its temporary builder preserves source-row and source-column provenance
until payloads are copied; equal raw references in different rows are unrelated.
See the [resize contract](terminal-core-contract.md#resize).

## 3. Row-owned cluster payloads

`Line` lazily allocates one flat `IntArray` when it first stores a cluster. A raw
reference packs the sign bit, a 25-bit codepoint offset, and five bits for
`length - 1`. Bit 30 stays clear, so a reference cannot equal the `-1` spacer.
Lengths therefore decode without a metadata-table lookup. The shared
[`TerminalTextLimits`](../../ketraterm-protocol/src/main/kotlin/io/github/ketraterm/protocol/TerminalTextLimits.kt)
defines the 32-codepoint bound; the [writer contract](terminal-core-contract.md)
defines validation and overflow behavior.

Writes copy borrowed input synchronously. A replacement reuses its region when
the new payload fits. Other writes append and mark replaced regions dead. The
exact live-codepoint count resets the append cursor as soon as no clusters
remain. Full clear and fill reset both counters directly, without a cluster scan.

When append space is exhausted, compaction copies only live payloads in column
order and updates their references. A second lazy array is retained and swapped
with the active array. This is safe even after shifts reorder payload offsets;
an in-place column-order copy could overwrite a later source. Both arrays are
reused once their capacities are sufficient, including through row recycling.
Rendering only reads storage and never triggers compaction.

Compaction reserves twice the required live size, with a minimum of 16 integers,
to amortize scans during sustained overwrites. Each array's capacity is bounded
by `max(16, 2 * width * 32)` and the encoding's checked limit of 2^25 integers.
Live-size arithmetic uses `Long` before allocation to prevent overflow. Dead
regions are reclaimed during ordinary mutation; array capacity is deliberately
retained for reuse. A row becomes collectible with all its payload storage when
the ring stops retaining it. Scalar-only rows allocate neither payload array.

All row access requires serialization with terminal mutation. The
assembled session supplies it; direct core callers provide their own boundary.
