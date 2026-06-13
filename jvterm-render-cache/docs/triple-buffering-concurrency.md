# Triple-Buffered Render Cache Concurrency

The `jvterm-render-cache` module uses a triple-buffering mechanism implemented in [TerminalRenderPublisher](file:///c:/Users/gagik/IdeaProjects/terminal-buffer/jvterm-render-cache/src/main/kotlin/io/github/jvterm/render/cache/TerminalRenderPublisher.kt) to decouple the high-frequency terminal session rendering worker from UI repainting ticks.

---

## 1. The Buffering Model

At any given time, the three cache buffers (`TerminalRenderCache`) are distributed across three distinct roles:

```
            +-----------------------------------------+
            |                  Spare                  |
            |     (Unused and ready to write)         |
            +--------------------+--------------------+
                                 |
                        Acquire Writable
                                 |
                                 ▼
+---------------------+     Publish     +---------------------+
|     Back Buffer     | ──────────────► |    Front Buffer     |
|   (Writer-owned)    |                 |    (UI-readable)    |
+---------------------+                 +---------------------+
```

1. **Back Buffer (Writer-owned)**: Exclusively leased by the render worker thread. It pulls fresh row updates from the terminal frame reader.
2. **Front Buffer (UI-readable)**: Exclusively leased by the UI thread for painting and repaint planning.
3. **Spare Buffer**: Sits idle. When the writer finishes updating the back buffer, the back buffer is promoted to the front, and the previous front buffer (or the spare buffer if the front was active) is recycled as the new spare.

---

## 2. Lock-Free and Synchronized Intersections

### Lock-Free Front Queries
For quick diagnostic checks (e.g. CLI utilities, testing), [current()](file:///c:/Users/gagik/IdeaProjects/terminal-buffer/jvterm-render-cache/src/main/kotlin/io/github/jvterm/render/cache/TerminalRenderPublisher.kt#L116) returns the latest front buffer atomically without locks using an `AtomicReference`.

### Leased Read Block
To prevent the front buffer from being recycled or rewritten while the UI thread is actively painting from it, the UI thread must acquire a read lease:

```kotlin
inline fun <T> readCurrent(block: (TerminalRenderCache) -> T): T?
```

* **Lease Acquisition**: Increments `readerCounts[frontIndex]` within a synchronized block.
* **UI Execution**: Passes the leased buffer safely to `block`.
* **Lease Release**: Decrements the count and signals waiting writers.

---

## 3. Allocation-Free Multi-Grapheme Clustered Text Copy

`TerminalRenderCache` optimizes grapheme cluster copies by using a packed primitive structure:
* **`clusterRefs` (LongArray)**: Packed indices mapped 1:1 to grid columns.
  * The upper 32 bits encode the starting offset in `clusterCodepoints`.
  * The lower 32 bits encode the number of codepoints in the grapheme cluster.
* **`clusterCodepoints` (IntArray)**: A single flat array containing the raw codepoints of all cached grapheme clusters.

This structure allows the cache to copy clusters from the frame and paint them on the screen without producing garbage string allocations.
