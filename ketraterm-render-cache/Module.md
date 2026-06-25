# Module jvterm-render-cache

## JvTerm Render Cache (`:jvterm-render-cache`)

The `jvterm-render-cache` module provides a high-performance, renderer-side double and triple-buffering publication system for **JvTerm Terminal**. It consumes short-lived render frames exposed by `:jvterm-render-api` and stores flat, primitive-packed, allocation-free snapshotted layouts.

These cached layouts allow asynchronous UI paint loop threads to perform font resolution, selection calculations, and pixel drawing without directly accessing the stateful terminal core or blocking backend execution threads.

---

## Upstream Dependencies
* **`:jvterm-render-api`** (for rendering interfaces, attributes, and cell flags).

---

## Architectural Role & Boundaries

To guarantee safety, memory locality, and absolute performance, `jvterm-render-cache` operates under strict boundaries:

```text
  ┌────────────────────────┐
  │ State Provider Thread  │ (Background write & mutate)
  └───────────┬────────────┘
              │
              ▼ [TerminalRenderFrameReader]
  ┌────────────────────────┐
  │  Render Worker Thread  │ (Copies raw frame into BACK buffer)
  └───────────┬────────────┘
              │
              ▼ updateAndPublish()
  ┌────────────────────────┐
  │ TerminalRenderPublisher│ (Triple-buffered rotation & lock-free read leases)
  └───────────┬────────────┘
              │
              ▼ readCurrent { front -> ... }
  ┌────────────────────────┐
  │    UI Paint Thread     │ (Paints from stable, snapshotted FRONT buffer)
  └────────────────────────┘
```

### What the Module Owns
- **Primitive Array Retention**: Deep copying of active buffer, lines, cursor, and text generation metrics from [TerminalRenderFrameReader](file:///c:/Users/gagik/IdeaProjects/terminal-buffer/jvterm-render-api/src/main/kotlin/io/github/jvterm/render/api/TerminalRenderFrameReader.kt) into flat, reusable primitive arrays.
- **Double-Buffered Row Synchronization**: Comparing generation numbers on a per-row basis to skip copying rows whose visual contents have not changed since the previous frame.
- **Ping-Pong Grapheme Cluster Storage**: Double-buffering complex multi-codepoint grapheme clusters and preserving active references for unchanged rows with zero allocations.
- **Triple-Buffered Thread Isolation**: Standardizing a thread-safe publication pipeline via a triple-buffered publisher ([TerminalRenderPublisher](file:///c:/Users/gagik/IdeaProjects/terminal-buffer/jvterm-render-cache/src/main/kotlin/io/github/jvterm/render/cache/TerminalRenderPublisher.kt)) that separates the background render worker from the UI paint reader.

### What the Module Does NOT Own
- **Terminal Output Parsing**: The render cache has no dependencies on protocols or ANSI/DEC byte parsers.
- **Font Selection & Painting**: It is agnostic to fonts, glyph metrics, rendering hints, color mappings, selections, or whether painting is driven by AWT, Swing, Compose, Java2D, or OpenGL.

---

## Sub-Documentation

For deep-dive technical details on triple-buffering logic and grapheme cluster copy optimizations:
* [triple-buffering-concurrency.md](file:///c:/Users/gagik/IdeaProjects/terminal-buffer/jvterm-render-cache/docs/triple-buffering-concurrency.md) - Buffer rotation phases, reader lease counts, and packed cluster allocation safety.

---

## How to Use

The following example shows how a UI component initializes a publisher and draws using the double/triple-buffered cache:

```kotlin
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import io.github.ketraterm.render.cache.TerminalRenderCache

class ComponentPainter(private val reader: TerminalRenderFrameReader) {
    // 1. Initialize publisher with three cache buffers
    private val publisher = TerminalRenderPublisher(columns = 80, rows = 24)
    
    // 2. Background worker pulls from reader and writes to back buffer
    fun onStateChange() {
        publisher.updateAndPublish(reader)
    }

    // 3. UI thread reads from front buffer lease-safely
    fun paintScreen() {
        publisher.readCurrent { frameCache ->
            val cols = frameCache.columns
            val rows = frameCache.rows
            
            // Loop through columns and rows in frameCache
            val charCode = frameCache.codeWords[0] // Reaches directly into flat cache arrays
            // Draw charCode to screen
        }
    }
}
```
