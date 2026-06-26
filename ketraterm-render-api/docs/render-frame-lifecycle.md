# Render Frame Lifecycle & Concurrency Invariants

The `ketraterm-render-api` defines a pull-based visual frame reading model designed for high concurrency safety, zero heap allocations on hot paths, and absolute isolation from internal terminal storage mutation.

---

## 1. Frame Reader & Consumer Synchronization

Render frame reading is orchestrated through the [TerminalRenderFrameReader](../src/main/kotlin/io/github/ketraterm/render/api/TerminalRenderFrameReader.kt) and [TerminalRenderFrameConsumer](../src/main/kotlin/io/github/ketraterm/render/api/TerminalRenderFrameConsumer.kt) contracts.

```
       TerminalSession Thread                UI/Render Thread
                 │                                  │
                 │ (Terminal Mutation)              │ readRenderFrame(consumer)
                 │                                  ▼
                 │                          [Lock Mutation Lock]
                 │                                  │
                 │                                  ├─► consumer.onFrame(frame)
                 │                                  │     │
                 │                                  │     ├─► copyLine(...)
                 │                                  │     └─► copyCursor(...)
                 │                                  │
                 │                          [Unlock Mutation Lock]
                 │                                  ▼
```

### Critical Lifespan Rule
> [!IMPORTANT]
> **Frame Reference Escape:**
> The `TerminalRenderFrame` instance passed to the consumer callback is **short-lived and valid only during the enclosing read function**.
> * Consumers **must not** cache or hold references to `TerminalRenderFrame` or any of its sub-objects (like `TerminalRenderCursor`) outside the callback.
> * Implementations are allowed to reuse a single `TerminalRenderFrame` instance across read calls to prevent garbage collection pressure.

---

## 2. Monotonic Generation Counters

To optimize cache invalidation and UI repaint schedules without costly deep comparisons, a frame exposes three distinct generation metrics:

* **`frameGeneration` (Long)**: Incremented monotonically on any visually relevant change in the terminal (cursor movements, cell writes, scroll actions). Excellent for a cheap "does the UI need redrawing?" check.
* **`structureGeneration` (Long)**: Incremented only when the grid structure layout changes (such as resizes, terminal resets, buffer switching, or scrollback line reflowing). When this changes, previous row caches must be invalidated or resized.
* **`lineGeneration(row)` (Long)**: Incremented per individual line when its text, attributes, or flags are updated. Allows UI paint engines to redraw only the specific modified rows.

---

## 3. Allocation-Free Copy Contracts

To paint or cache frame data, the frame provides primitive bulk array copy procedures:

```kotlin
fun copyLine(
    row: Int,
    codeWords: IntArray,
    codeOffset: Int = 0,
    attrWords: LongArray,
    attrOffset: Int = 0,
    flags: IntArray,
    flagOffset: Int = 0,
    extraAttrWords: LongArray? = null,
    extraAttrOffset: Int = 0,
    hyperlinkIds: IntArray? = null,
    hyperlinkOffset: Int = 0,
    clusterSink: TerminalRenderClusterSink? = null,
    clusterDataSink: TerminalRenderClusterDataSink? = null,
)
```

By providing destination primitive arrays, callers perform bulk reads from backing grid storage without allocating temporary objects per cell or per row.
* **`extraAttrWords`** and **`hyperlinkIds`** parameters are optional (`null` by default) so renderers that do not support hyperlinks or extended overlines can bypass those copy overheads entirely.
