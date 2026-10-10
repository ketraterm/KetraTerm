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
through clears for reuse. A row-local cluster count lets erases skip handle
scanning when no cluster cells are present.

### Invariants:

- `0` denotes an empty cell.
- Positive values hold direct Unicode scalars, including supplementary values.
- `-1` is the trailing spacer of a two-cell occupant.
- Values `<= -2` encode handles into the row's owning cluster store.
- Attributes use the same column index as the cell value. Overwriting or erasing
  a cluster releases its handle; moving cells must transfer ownership exactly
  once.

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
clears recycled contents and releases their clusters before reuse.

Full-width region scrolling rotates row references rather than copying each
cell. Partial-width scrolling copies only the selected cell slices and does not
admit rows to history. History destruction releases discarded live contents
before dropping their logical reachability, leaving row storage available for
reuse.

Height-only primary resize adjusts the reference table while retaining surviving
row arrays and their store. A width change reconstructs logical lines into a new
ring and store. See the [resize contract](terminal-core-contract.md#resize).

## 3. The Arena Allocator (`ClusterStore`)

Each screen owns a separate
[ClusterStore](../src/main/kotlin/io/github/ketraterm/core/store/ClusterStore.kt).
Its primitive arrays contain:

- `clusterData`: the flat codepoint pool.
- `slotStarts` and `slotLengths`: each slot's region and live payload length.
- `slotCapacities`: the reusable capacity of that region.
- `nextFree` and segregated free-list heads: reusable slot links.

A cell stores `handle = -(slot + 2)`; decoding uses `slot = -(handle + 2)`.
Allocation copies the caller's codepoint range synchronously. A freed slot
retains its data region and may be reused; double-free is rejected. Freeing does
not zero the payload or shrink the arena.

Capacity classes are exact for lengths one through four, then powers of two.
Allocation searches suitable classes instead of scanning all history. Small
requests use only the small classes. A reused slot never abandons its original
region in order to hold a larger payload.

The store grows when reuse cannot satisfy a request. There is no fixed public
cluster-length bound, so history capacity alone does not define a byte-size
limit. Width reflow deep-copies surviving clusters into a fresh store; height
resize preserves their existing handles. Rows and handles must never cross
store boundaries without that copy.

All row and store access requires serialization with terminal mutation. The
assembled session supplies it; direct core callers provide their own boundary.
