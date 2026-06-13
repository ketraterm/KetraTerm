# Grid Storage Layout & Memory Architecture

The `jvterm-core` module implements a highly optimized, flat memory architecture designed to handle high-frequency terminal grid writes and viewport resizes with minimal garbage collector impact.

---

## 1. Parallel Array Cell Storage (`Line`)

Instead of representing grid cells as individual JVM heap objects, each physical line in the grid is modeled by a [Line](file:///c:/Users/gagik/IdeaProjects/terminal-buffer/jvterm-core/src/main/kotlin/io/github/jvterm/core/model/Line.kt) class backed by flat parallel primitive arrays:

```
                  Parallel Array Mapping in a Line
+---------------+-----------------------------------------------+
| Array Name    | Type      | Contents                          |
+---------------+-----------+-----------------------------------+
| codepoints    | IntArray  | Codepoint, EMPTY, SPACER, or      |
|               |           | negative Cluster Handle (<= -2)  |
| attrs         | LongArray | Primary styling attributes        |
| extendedAttrs | LongArray | Extended styling attributes       |
+---------------+-----------+-----------------------------------+
```

### Invariants:
* **`codepoints` values**:
  * `>= 0`: Direct Unicode codepoint (ASCII or BMP).
  * `0`: Empty cell (`TerminalConstants.EMPTY`).
  * `-1`: Wide character spacer cell continuation (`TerminalConstants.WIDE_CHAR_SPACER`).
  * `<= -2`: A negative handle pointer into the `ClusterStore` arena for multi-codepoint grapheme clusters.
* **`attrs` & `extendedAttrs`**: Parallel arrays that match the `codepoints` array indices, allowing attributes to be retrieved at $O(1)$ without pointer chasing.

---

## 2. Off-Screen History Ring (`HistoryRing`)

Scrollback and off-screen history are managed by [HistoryRing](file:///c:/Users/gagik/IdeaProjects/terminal-buffer/jvterm-core/src/main/kotlin/io/github/jvterm/core/buffer/HistoryRing.kt), which is a fixed-capacity ring buffer of physical `Line` objects.

* **Line Recycling**: When the history capacity is reached, new lines pushed into the ring reuse the oldest line's physical arrays (`push()` returns the recycled line). This avoids new object allocations during active shell scroll outputs.
* **Logical-to-Physical Rotation**: Methods `rotateUp` and `rotateDown` rotate logical indices within the active scroll margins, shifting array pointers rather than copying line values element-by-element.

---

## 3. The Arena Allocator (`ClusterStore`)

Multi-codepoint grapheme clusters (such as emojis with joiners) cannot fit in a single 32-bit cell integer. To avoid allocating standard string objects for these cells, `jvterm-core` utilizes a buffer-scoped arena allocator called [ClusterStore](file:///c:/Users/gagik/IdeaProjects/terminal-buffer/jvterm-core/src/main/kotlin/io/github/jvterm/core/store/ClusterStore.kt).

### Memory Mapping:
* **`clusterData` (IntArray)**: A single flat array containing the raw codepoints of all allocated clusters.
* **`slotStarts` & `slotLengths` (IntArray)**: Metadata tables mapping slot indices to their offsets and sizes inside `clusterData`.
* **Allocation Handle**: The allocator returns a negative handle `slot = -(handle + 2)`. This handle is stored directly inside the `codepoints` array of the `Line`.

### Freelist Slot Reclamation:
* When a cell containing a cluster handle is overwritten or erased, `free(handle)` returns the slot to a segregated freelist.
* Subsequent allocations reuse these freed slots before expanding the metadata tables, maintaining a bounded memory footprint.
* **Thread Safety**: Access to `ClusterStore` is confined strictly to the terminal core's state mutation thread.
