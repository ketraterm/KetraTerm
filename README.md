# 🌀 Lattice Terminal

**Lattice Terminal** is a next-generation, high-performance, strictly modular, and allocation-conscious terminal emulator pipeline written in **Kotlin/JVM 21**. 

Unlike legacy JVM terminal components that suffer from tight coupling, high garbage collection overhead, poor Unicode/grapheme correctness, and visual tearing, Lattice is engineered from the ground up as a **professional, production-ready terminal engine**. It separates concerns cleanly into an asynchronous, unidirectional pipeline: a headless screen-state engine, a table-driven byte-stream parser, an in-memory double/triple-buffered render publisher, and isolated Swing UI components that run glitch-free.

---

## Architectural Philosophy & Unidirectional Data Flow

Lattice is structured to keep terminal state mutation, SSH/PTY I/O, event encoding, and visual rendering entirely independent. 

```
                                    LATTICE PIPELINE HIGH-LEVEL ARCHITECTURE
                                    
  Inbound Path:
  ┌─────────────┐  Raw Bytes  ┌─────────────────┐  Commands  ┌──────────────────────┐  Mutations  ┌───────────────┐
  │ PTY / SSH   ├────────────►│ terminal-parser ├───────────►│ terminal-host ├────────────►│ terminal-core │
  └─────────────┘             └─────────────────┘            └──────────────────────┘             └───────┬───────┘
                                                                                                 Snapshot │ 
  Rendering Path (Triple-Buffered):                                                                       ▼
  ┌────────────────┐  Repaint   ┌───────────────────┐  Lease  ┌───────────────────────┐  copyLine  ┌───────────────┐
  │ Swing Component│ ◄──────────┤ terminal-ui-swing ├────────►│ terminal-render-cache │◄───────────┤ Render API    │
  └────────────────┘            └───────────────────┘         └───────────────────────┘            └───────────────┘
  
  Outbound Path:
  ┌─────────────┐  Key/Paste  ┌─────────────────┐  ANSI Bytes┌──────────────────────┐  write()    ┌───────────────┐
  │ User Event  ├────────────►│ terminal-input  ├───────────►│ terminal-session     ├────────────►│ PTY / SSH     │
  └─────────────┘             └─────────────────┘            └──────────────────────┘             └───────────────┘
```

The system is coordinated by [TerminalSession](./terminal-session/src/main/kotlin/session/TerminalSession.kt) using a strict thread-safe actor-like model. Concurrency is tightly controlled, preventing any race conditions or visual tearing while sustaining a rendering speed of **60+ FPS** under high-throughput data loads.

---

## The Lattice Advantage: Key Strengths

### 1. Zero-Allocation Hot Paths & Extreme Performance
Terminal engines process thousands of bytes per second. To prevent JVM garbage collection pauses, Lattice enforces strict allocation-minimal rules:
* **Primitive Array Packing**: Cells are stored in parallel, flat arrays (`IntArray` for codepoints/handles, `LongArray` for attributes, and `LongArray` for extended attributes) inside [Line](./terminal-core/src/main/kotlin/core/model/Line.kt), ensuring cache locality and eliminating object overhead.
* **Monomorphized LRU Caches**: Rather than using boxed generic caches, the rendering and typography layers use custom, monomorphized primitive caches (`IntFontLru` for 32-bit codepoints to Font fallbacks, `LongTextLayoutLru` for 64-bit styling/codepoint keys, and `ClusterTextLayoutLru` for complex grapheme clusters).
* **Flat FSM Transitions**: [AnsiStateMachine](./terminal-parser/src/main/kotlin/parser/impl/TerminalParser.kt) resolves state transitions in $O(1)$ time with no allocations using packed integers inside a flat `IntArray`.
* **Zero-Allocation Sinks & Builders**: Keyboard and mouse encoders use [InputScratchBuffer](./terminal-input/src/main/kotlin/input/impl/InputScratchBuffer.kt) and pre-allocated [TerminalSequences](./terminal-input/src/main/kotlin/input/impl/TerminalSequences.kt) to format ANSI strings on the fly without spawning a single `String` or `StringBuilder` object.

### 2. Glitch-Free Triple-Buffered UI Pipeline
UI paint loops on the Event Dispatch Thread (EDT) must not block background PTY readers or resize operations. Lattice solves this via a robust triple-buffering publication model in [TerminalRenderPublisher](./terminal-render-cache/src/main/kotlin/com/gagik/terminal/render/cache/TerminalRenderPublisher.kt):
* **Back Buffer**: Leased by the background render worker to copy the screen snapshot.
* **Front Buffer**: Leased by the UI thread during paint traversals to guarantee visual stability.
* **Spare Buffer**: Used to ingest incoming updates while the UI is currently painting.
This decouples the visual rendering from live mutations, guaranteeing **perfect frame isolation and zero tearing**.

### 3. Ultimate Typography & Custom Primitive Rendering
Text rendering in terminals is traditionally slow due to complex shaping of non-ASCII glyphs. Lattice uses a **bifurcated text rendering pipeline** in the Swing UI module:
* **ASCII Fast Path**: Contiguous runs of ASCII characters with identical style attributes are drawn in a single call via `Graphics2D.drawChars` or `drawGlyphVector`, completely bypassing shaping.
* **Complex Unicode Fallback**: Non-ASCII scripts, emojis, and combined grapheme clusters are processed using prioritized fallback font chains (asynchronously resolved off the EDT) and cached Java2D `TextLayout` objects.
* **Pixel-Perfect Primitives**: Box-drawing and block-element characters are drawn programmatically cell-by-cell instead of relying on monospaced fonts, eliminating visual gaps or line misalignments across different monitor scale factors.

### 4. Precision Unicode & Grapheme Alignment
Lattice treats the Unicode standard as a first-class citizen:
* **UAX #29 Segmentation**: The parser segments incoming codepoints into grapheme clusters using generated tables, properly accumulating Combining Marks, Regional Indicators, and Zero-Width-Joiner (ZWJ) emoji sequences.
* **Interactive Echo Optimization**: To eliminate character echo latency, the parser publishes incomplete clusters immediately (`flushForRender`). If subsequent packets contain accent combiners, the parser uses `appendToPreviousCluster` to modify the cell without affecting cursor placement.
* **East Asian Width Engine**: [UnicodeWidth](./terminal-core/src/main/kotlin/core/util/UnicodeWidth.kt) employs standard ASCII fast-checks and quick BMP/SMP bitset lookups to decide grid occupancy (0, 1, or 2 cells) with ambiguous-width configuration support.

### 5. Security-Hardened Integration
Rogue or high-output subprocesses can exhaust memory limits by flooding the terminal with malformed or infinite payloads:
* **LRU Bounded Hyperlinks**: OSC 8 hyperlinks are registered inside a bounded LRU cache in [CoreTerminalCommandSink](./terminal-integration/src/main/kotlin/integration/CoreTerminalCommandSink.kt). The cells store only a packed integer ID, and old entries are automatically evicted, preventing memory exhaustion.
* **Strict Payload Discarding**: OSC and DCS accumulation buffers are strictly capped (default 4KB). Characters exceeding this threshold set an overflow flag and are discarded safely.
* **Title Stack Guards**: xterm title stacking is capped at `16` elements to prevent infinite stack expansion.

---

## Module Architecture & Responsibility Matrix

The Lattice codebase is split into highly specialized modules with rigid architectural boundaries:

| Module | Purpose & Core Responsibility | What It Owns | What It Does NOT Own |
| :--- | :--- | :--- | :--- |
| [**terminal-protocol**](./terminal-protocol) | Shared Vocabulary | C0/C1 constants, `AnsiMode`, `DecPrivateMode`, mouse modes, [TerminalHostOutput](./terminal-protocol/src/main/kotlin/protocol/host/TerminalHostOutput.kt) | Has no execution logic or sub-dependencies. |
| [**terminal-parser**](./terminal-parser) | Stream Parsing | UTF-8 streaming decoder, table-driven [AnsiStateMachine](./terminal-parser/src/main/kotlin/parser/impl/TerminalParser.kt), DEC charset remapping, UAX #29 segmentation | Has no grid physics, cursor calculations, or UI state. |
| [**terminal-core**](./terminal-core) | Headless Grid Engine | Circular scrollback history, parallel array cells ([Line](./terminal-core/src/main/kotlin/core/model/Line.kt)), wide-character erasure, margins, resizing reflow | Has no byte stream parsing, input encoding, or UI code. |
| [**terminal-host**](./terminal-integration) | Translation Adapter | [CoreTerminalCommandSink](./terminal-integration/src/main/kotlin/integration/CoreTerminalCommandSink.kt) adapter, SGR Pen state, OSC 8 Hyperlink LRU registry, DECSTR/RIS resets | Has no byte parsing or state duplication. |
| [**terminal-input**](./terminal-input) | Event Encoding | physical key arrow/numpad maps, xterm `modifyOtherKeys`, SGR/legacy mouse coordinate mapping, bracketed paste | Has no screen mutation or output parsing logic. |
| [**terminal-render-api**](./terminal-render-api) | Rendering Contract | Viewport frame models, cursor shapes, cell state flags ([TerminalRenderCellFlags](./terminal-render-api/src/main/kotlin/com/gagik/terminal/render/api/TerminalRenderCellFlags.kt)), packed color ARGB resolution | Has no UI frame painting or glyph metrics. |
| [**terminal-render-cache**](./terminal-render-cache) | Frame Snapshotting | [TerminalRenderCache](./terminal-render-cache/src/main/kotlin/com/gagik/terminal/render/cache/TerminalRenderCache.kt) double-buffering, [TerminalRenderPublisher](./terminal-render-cache/src/main/kotlin/com/gagik/terminal/render/cache/TerminalRenderPublisher.kt) triple-buffering | Agnostic to UI paint platforms (AWT/Swing/Compose). |
| [**terminal-transport-api**](./terminal-transport-api) | Duplex Channel Contract | [TerminalConnector](./terminal-transport-api/src/main/kotlin/transport/TerminalConnector.kt) interface, connection callbacks, size change signaling | Has no thread policies or payload inspection. |
| [**terminal-session**](./terminal-session) | Pipeline Sync & Event Loop | Synchronized `mutationLock` and `outboundWriteLock`, daemon `renderWorker` coalescing, fast UTF-8 encoder | Does not own transport background threads or paint loops. |
| [**terminal-pty**](./terminal-pty) | Native Process Host | Cross-platform Pty4J management, daemon reader/watcher threads, system default shell detection | Has no input encoding or grid cell mutations. |
| [**terminal-ui-swing**](./terminal-ui-swing) | Swing Component | [TerminalSwingTerminal](./terminal-ui-swing/src/main/kotlin/com/gagik/terminal/ui/swing/api/TerminalSwingTerminal.kt) component, bifurcated text rendering, smart double-click path selection | Has no shell process awareness or protocol parsing. |
| [**terminal-testkit**](./terminal-testkit) | Testing Fakes | In-memory [MockConnector](./terminal-testkit/src/main/kotlin/testkit/MockConnector.kt), outbound write capture, remote crash/exit simulators | Has no physical thread spawning or shell requirements. |

---

## Seamless Integration Guide

One of Lattice's greatest strengths is how easily it integrates into existing desktop systems or custom runtimes. Hooking a local system shell (e.g. bash or cmd) to a fully interactive Swing JComponent requires just a few lines of configuration:

```kotlin
import io.github.jvterm.pty.TerminalPtySessions
import io.github.jvterm.pty.TerminalPtyOptions
import io.github.jvterm.ui.swing.api.TerminalSwingTerminal
import io.github.jvterm.ui.swing.settings.TerminalSwingSettings
import io.github.jvterm.ui.swing.settings.TerminalTheme
import java.awt.BorderLayout
import javax.swing.JFrame
import javax.swing.JPanel

fun spawnTerminalWindow() {
    // 1. Configure the local PTY process (spawns native process via Pty4J)
    val options = TerminalPtyOptions(
        command = listOf("bash"),          // or "cmd.exe", "powershell.exe", etc.
        columns = 100,
        rows = 30,
        maxHistory = 5000,
        treatAmbiguousAsWide = false
    )
    
    // 2. Wires up standard components and starts PTY reader daemon threads
    val session = TerminalPtySessions.localPty(options)
    
    // 3. Create JComponent with customizable, immutable visual presets
    val settings = TerminalSwingSettings(
        theme = TerminalTheme.ONE_DARK,
        fontSize = 14,
        fontFamily = "JetBrains Mono"
    )
    val terminalComponent = TerminalSwingTerminal(
        settingsProvider = { settings }
    )
    
    // 4. Bind the Swing UI thread-safely to the session Snapshots
    terminalComponent.bind(session)
    
    // 5. Host it in standard Swing layouts
    val frame = JFrame("Lattice Terminal")
    frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
    frame.layout = BorderLayout()
    frame.add(terminalComponent, BorderLayout.CENTER)
    frame.pack()
    frame.isVisible = true
}
```

---

## Core Mechanics Under the Hood

### Efficient In-Memory Cell Storage
Lattice rejects object-per-cell designs. The cell grid in [Line](./terminal-core/src/main/kotlin/core/model/Line.kt) uses parallel primitive arrays:
```
  Cell Representation in Line.kt:
  ┌─────────────────────────────────────────────────────────────┐
  │ codepoints [IntArray] (e.g., 'A', Combining Diacritics, -1) │
  ├─────────────────────────────────────────────────────────────┤
  │ attrs [LongArray] (Primary Styles, Bold, Italic, 24-bit RGB)│
  ├─────────────────────────────────────────────────────────────┤
  │ extendedAttrs [LongArray] (Secondary, Underline, Link ID)   │
  └─────────────────────────────────────────────────────────────┘
```
* **Unicode Scalars**: Plain text cells directly store their scalar values.
* **Spacer Sentinel (`-1`)**: Represents trailing visual cells for double-width East Asian glyphs.
* **Cluster Handle (`<= -2`)**: Points to combining marks or ZWJ regional flags inside the [ClusterStore](./terminal-core/src/main/kotlin/core/store/ClusterStore.kt).

### Monomorphized LRU Typography Caches
To dodge Java Type Erasure boxing overhead during font fallbacks, we hand-craft primitive LRU caches:
```kotlin
// IntFontLru.kt: Direct 32-bit codepoint to AWT Font fallback resolver
class IntFontLru(val capacity: Int) {
    private val keys = IntArray(capacity)
    private val values = arrayOfNulls<Font>(capacity)
    // Fast O(1) hash mapping without object boxing
}
```

### High-Fidelity Reflow & Resizing
When resizing a terminal window, a simple coordinate truncation cuts off text. Lattice employs a robust 3-phase reflow in [TerminalResizer](./terminal-core/src/main/kotlin/core/engine/TerminalResizer.kt):
1. **Logical Row Rebuilding**: Assembles segmented rows using `Line.wrapped` flags.
2. **Re-wrapping**: Re-calculates and folds character arrays according to new column dimensions.
3. **Cluster Deep-Copying**: Moves survived cluster indices into a pristine [ClusterStore](./terminal-core/src/main/kotlin/core/store/ClusterStore.kt) arena to clean leaked data pools.

---

## Development & Verification

### Prerequisites
* **JDK 21 or higher** (preferably JetBrains Runtime `JBR 21` for optimal AWT text layouts)
* **Gradle 7.4+**

### Command Reference
* **Run All Tests**:
  ```bash
  ./gradlew test
  ```
* **Run Parser, Core or Swing tests**:
  ```bash
  ./gradlew :terminal-parser:test
  ./gradlew :terminal-core:test
  ./gradlew :terminal-ui-swing:test
  ```
* **Launch Swing PTY Demo**:
  ```bash
  ./gradlew :terminal-ui-swing-demo:run
  ```
* **Launch with Custom Shell**:
  ```bash
  # Windows cmd
  ./gradlew :terminal-ui-swing-demo:run --args="cmd.exe"
  # WSL
  ./gradlew :terminal-ui-swing-demo:run --args="wsl.exe"
  ```

---

## Testing Doctrine & Hermetic Architecture

We treat terminal testing as a critical engineering discipline:
1. **No Faked Quirks**: Tests assert standard-aligned ANSI/DEC protocol states and real screen results, rather than current parser implementation hacks.
2. **Deterministic Multi-threading**: The host and session test suites employ synchronized latches to stress-test high-volume updates, proving that resizes, writes, and renders are race-free.
3. **In-Memory Fakes**: By leveraging `:terminal-testkit`'s [MockConnector](./terminal-testkit/src/main/kotlin/testkit/MockConnector.kt), developers can run complete, bidirectional I/O host tests with exact byte assertions, completely independent of local OS PTY subsystems.

---

## Project Status & Feature Gap Map

Lattice is structured to support modern terminal protocols and rich TUI environments. Unsupported features or pending integrations are strictly documented inside the project [feature gap map](./docs/terminal-feature-gap-map.md) and annotated inside the source code using distinct `TODO` markers.


## License

Copyright 2026 Gagik Sargsyan

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.