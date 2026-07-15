# KetraTerm Terminal Pipeline Architecture

This document describes the design principles, unidirectional data flow, concurrency architecture, and data structures of **KetraTerm**.

---

## High-Level Architecture & Unidirectional Flow

KetraTerm separates terminal operations into a strict, unidirectional data pipeline to ensure complete decoupling of SSH/PTY I/O, byte parsing, screen-state mutation, and visual painting.

```
  Inbound Path (Host to Screen State):
  ┌─────────────┐  Raw Bytes  ┌────────────────────┐  Commands  ┌───────────────────┐  Mutations  ┌───────────────┐
  │ PTY / SSH   ├────────────►│ ketraterm-parser   ├───────────►│ ketraterm-host    ├────────────►│ ketraterm-core│
  └─────────────┘             └────────────────────┘            └───────────────────┘             └───────┬───────┘
                                                                                                 Snapshot │ 
  Rendering Path (Triple-Buffered):                                                                       ▼
  ┌────────────────┐  Repaint   ┌───────────────────┐  Lease  ┌───────────────────────┐  copyLine  ┌───────────────┐
  │ Swing Component│ ◄──────────┤ ketraterm-ui-swing├────────►│ ketraterm-render-cache│◄───────────┤ Render API    │
  └────────────────┘            └───────────────────┘         └───────────────────────┘            └───────────────┘
  
  Outbound Path (User to Host stdin):
  ┌─────────────┐  Key/Paste  ┌─────────────────┐  ANSI Bytes┌──────────────────────┐  write()    ┌───────────────┐
  │ User Event  ├────────────►│ ketraterm-input ├───────────►│ ketraterm-session    ├────────────►│ PTY / SSH     │
  └─────────────┘             └─────────────────┘            └──────────────────────┘             └───────────────┘
```

The pipeline coordination is managed by `TerminalSession` using a thread-safe, actor-like model. Concurrency is tightly controlled, preventing race conditions or visual tearing while sustaining a rendering speed of **60+ FPS** under heavy stdout throughput.

---

## Module Responsibility Matrix

The KetraTerm codebase is partitioned into highly specialized modules with strict boundaries:

| Module | Purpose & Core Responsibility | What It Owns | What It Does NOT Own |
| :--- | :--- | :--- | :--- |
| [**ketraterm-protocol**](./ketraterm-protocol) | Shared Vocabulary | C0/C1 constants, `AnsiMode`, `DecPrivateMode`, mouse modes, `TerminalHostOutput` | Has no execution logic or sub-dependencies. |
| [**ketraterm-parser**](./ketraterm-parser) | Stream Parsing | UTF-8 streaming decoder, table-driven `AnsiStateMachine`, DEC charset remapping, UAX #29 segmentation | Has no grid physics, cursor calculations, or UI state. |
| [**ketraterm-core**](./ketraterm-core) | Headless Grid Engine | Circular scrollback history, parallel array cells (`Line`), wide-character erasure, margins, resizing reflow | Has no byte stream parsing, input encoding, or UI code. |
| [**ketraterm-host**](./ketraterm-host) | Translation Adapter | `HostCommandAdapter`, SGR Pen state, OSC 8 Hyperlink LRU registry, DECSTR/RIS resets | Has no byte parsing or state duplication. |
| [**ketraterm-input**](./ketraterm-input) | Event Encoding | Key arrow/numpad maps, xterm `modifyOtherKeys`, SGR/legacy mouse coordinate mapping, bracketed paste | Has no screen mutation or output parsing logic. |
| [**ketraterm-render-api**](./ketraterm-render-api) | Rendering Contract | Viewport frame models, cursor shapes, cell state flags (`TerminalRenderCellFlags`), packed color ARGB resolution | Has no UI frame painting or glyph metrics. |
| [**ketraterm-render-cache**](./ketraterm-render-cache) | Frame Snapshotting | `TerminalRenderCache` double-buffering, `TerminalRenderPublisher` triple-buffering | Agnostic to UI paint platforms (AWT/Swing/Compose). |
| [**ketraterm-transport-api**](./ketraterm-transport-api) | Duplex Channel Contract | `TerminalConnector` interface, connection callbacks, size change signaling | Has no thread policies or payload inspection. |
| [**ketraterm-session**](./ketraterm-session) | Pipeline Sync & Event Loop | Synchronized `mutationLock` and `outboundWriteLock`, daemon `renderWorker` coalescing, fast UTF-8 encoder | Does not own transport background threads or paint loops. |
| [**ketraterm-pty**](./ketraterm-pty) | Native Process Host | Cross-platform Pty4J management, daemon reader/watcher threads, system default shell detection | Has no input encoding or grid cell mutations. |
| [**ketraterm-ui-swing**](./ketraterm-ui-swing) | Swing Component | `SwingTerminal` component, bifurcated text rendering, smart double-click path selection | Has no shell process awareness or protocol parsing. |
| [**ketraterm-testkit**](./ketraterm-testkit) | Testing Fakes | In-memory `MockConnector`, outbound write capture, remote crash/exit simulators | Has no physical thread spawning or shell requirements. |

---

## Concurrency & Locking Architecture

Operating a multithreaded terminal on the JVM introduces concurrent events from the host PTY (background reads), the OS (resizing and focus shifts), and the UI (user keystrokes and paint ticks). The transport contract already guarantees ordered, serial byte delivery, so `TerminalSession` needs two locks and one coroutine publication worker:

1. **`mutationLock`**: The core-critical lock. It blocks parser execution, grid resizing, borrowed frame reads, and render-frame extraction. This ensures copied frames never contain half-written rows or mismatched widths.
2. **`outboundWriteLock`**: A reentrant monitor that synchronizes writes to host stdin and protects encoder scratch buffers. It guarantees that user input and synchronous query responses are written in exact order without interleaved bytes.
3. **Render publication worker**: A conflated coroutine channel wakes one session worker. The worker extracts a frame under `mutationLock`, promotes it through `TerminalRenderPublisher`, and updates a `StateFlow` generation consumed by UI renderers.

---

## Core Mechanics & Data Structures

### 1. Flat In-Memory Cell Storage
To eliminate object-per-cell memory overhead and ensure JVM cache-line locality, the terminal cell grid in `Line` uses parallel primitive arrays:
* `codepoints` (`IntArray`): Stores cell characters (e.g. `0` for empty cells, `> 0` for Unicode scalars, `-1` for wide-character continuation spacers, and `<= -2` for grapheme cluster handles).
* `attrs` (`LongArray`): Stores packed primary visual attributes (24-bit RGB or indexed colors, bold, italic, inverse, blink, faint, erase protection).
* `extendedAttrs` (`LongArray`): Stores secondary attributes (underline styles, overline, conceal, and packed hyperlink IDs).

### 2. Monomorphized LRU Typography Caches
To avoid object-boxing during high-speed rendering ticks, custom, primitive-keyed LRU caches are utilized in the rendering and font selection layers:
* `IntFontLru`: Maps 32-bit Unicode codepoints directly to `java.awt.Font` fallbacks using flat primitive arrays and custom hash multipliers.
* `LongTextLayoutLru`: Maps packed 64-bit styling/codepoint keys directly to pre-shaped Java2D `TextLayout` objects.
* `ClusterTextLayoutLru`: Identifies multi-character grapheme clusters by taking direct slices of primitive `IntArray` segments from the render cache and comparing them using array-content hashing.
* `AwtColorCache`: Maps packed 32-bit ARGB integers to `java.awt.Color` instances.

### 3. High-Fidelity Resizing & Reflow
During terminal resizes, KetraTerm uses a 3-phase reflow strategy in `TerminalResizer`:
1. **Logical Row Rebuilding**: Re-assembles full logical lines by joining physical rows that carry the soft-wrap flag.
2. **Re-wrapping**: Re-calculates wrap boundaries and folds character arrays according to new column dimensions.
3. **Cluster Deep-Copying**: Moves survived cluster indices into a pristine `ClusterStore` arena to prevent references from leaking or fragmentation.

---

## Testing Doctrine & Hermetic Architecture

We treat terminal testing as a critical engineering discipline:
1. **Assert Real Semantics**: Tests assert standard-aligned ANSI/DEC protocol states and real screen results, rather than current parser implementation quirks.
2. **Deterministic Multi-threading**: The host and session test suites employ synchronized latches to stress-test high-volume updates, proving that resizes, writes, and renders are race-free.
3. **In-Memory Fakes**: By leveraging `:ketraterm-testkit`'s `MockConnector`, developers can run complete, bidirectional I/O host tests with exact byte assertions, completely independent of local OS PTY subsystems.
