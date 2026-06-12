# Terminal Session

**terminal-session** is the central orchestration hub and runtime synchronization engine of the Lattice Terminal pipeline. It coordinates the data-flow between the asynchronous transport layer, the byte-stream parser, the headless terminal grid, and platform-agnostic keyboard/mouse input encoders. 

The module is engineered to be highly performant, race-free, and allocation-minimal. It encapsulates the synchronization policies necessary to support concurrent multithreaded execution—ensuring that rapid host output, user keystrokes, window resizing, and UI rendering frames never corrupt the terminal state or produce rendering anomalies.

---

## Architectural Scope and Boundaries

To maintain a strict separation of concerns, **terminal-session** operates under clear design rules that protect its boundaries:

### What the Module Owns
- **Runtime Orchestration**: Binding [TerminalBufferApi](../terminal-core/src/main/kotlin/core/api/TerminalBufferApi.kt), [TerminalOutputParser](../terminal-parser/src/main/kotlin/parser/api/TerminalOutputParser.kt), [TerminalInputEncoder](../terminal-input/src/main/kotlin/input/api/TerminalInputEncoder.kt), [TerminalRenderPublisher](../terminal-render-cache/src/main/kotlin/com/gagik/terminal/render/cache/TerminalRenderPublisher.kt), and the active [TerminalConnector](../terminal-transport-api/src/main/kotlin/transport/TerminalConnector.kt) into a cohesive, running session.
- **Parser & Core Synchronization**: Enforcing absolute serialized access to parser mutations, coordinate conversions, and buffer writes via a dedicated internal lock (`mutationLock`).
- **Safe Read-Only Frame Copying**: Providing safe access for UI layers to copy state out of the grid ([readRenderFrame](./src/main/kotlin/session/TerminalSession.kt)) without racing against background PTY inbound parser threads or resize operations.
- **Asynchronous Coalesced Rendering**: Offloading heavy copying tasks to a dedicated background daemon worker (`renderWorker`) that merges rapid UI-repaint requests and publishes them to the render-cache.
- **Outbound Stream Serialization**: Synchronizing user input (keys, focus, mouse, paste) and terminal-generated replies (DSR, CPR, DA queries) through a single outbound write lock (`outboundWriteLock`).
- **Zero-Allocation Host Buffering**: Managing preallocated scratch buffers ([ConnectorTerminalHostOutput](./src/main/kotlin/session/ConnectorTerminalHostOutput.kt)) for manual, allocation-free ASCII and UTF-8 encoding on the hot input path.
- **Orderly Session Shutdown**: Guiding graceful, idempotent close lifecycles for both locally initiated exits, remote process termination (tracking exit codes), and socket/transport errors.

### What the Module Does NOT Own
- **Transport I/O Threads**: It does not own or spawn transport reading loops. The [TerminalConnector](../terminal-transport-api/src/main/kotlin/transport/TerminalConnector.kt) is entirely responsible for spawning and managing its own reading and writing threads.
- **Byte-Stream Parsing**: It never parses terminal streams itself, delegating all ANSI/DEC state mutations to the parsed command sink and [TerminalOutputParser](../terminal-parser/src/main/kotlin/parser/api/TerminalOutputParser.kt).
- **Core State Physics**: It has no knowledge of wrap policies, margins, scrollback buffers, tab stops, or SGR pen attributes—all grid physics are kept strictly inside [terminal-core](../terminal-core).
- **Input Encoding Logic**: It does not define how keystrokes are converted to escape codes, relying entirely on the [TerminalInputEncoder](../terminal-input/src/main/kotlin/input/api/TerminalInputEncoder.kt) contract.
- **Swing/UI Painting**: It does not choose fonts, render borders, process scrollbar views, or manage platform windows.

---

## Concurrency and Locking Architecture

In a modern multithreaded environment, terminal events arrive concurrently from three directions: the host PTY (background read thread), the host OS (resize and window focus events), and the local UI (event dispatch thread). To prevent state corruption, [TerminalSession](./src/main/kotlin/session/TerminalSession.kt) coordinates concurrency through three highly focused locks:

1. **`inboundLock`**: Guards the entry of raw host bytes on [onBytes](./src/main/kotlin/session/TerminalSession.kt). It ensures bytes are fed to the parser in absolute sequential stream order and prevents concurrent processing if multiple read buffers are delivered.
2. **`mutationLock`**: The core-critical section. It locks during all [TerminalOutputParser.accept](../terminal-parser/src/main/kotlin/parser/api/TerminalOutputParser.kt) parser steps, [TerminalBufferApi.resize](../terminal-core/src/main/kotlin/core/api/TerminalBufferApi.kt) operations, and render copying steps.
   > [!NOTE]
   > While `mutationLock` is held by a thread copying the current screen state (like `readRenderFrame`), background PTY data-processing and resizing are blocked. This guarantees that UI drawing loops never view a half-mutated row, mismatched width, or corrupted Unicode surrogate sequence.
3. **`outboundWriteLock`**: Synchronizes all writes to PTY stdin. It guarantees that user keystrokes, paste payloads, mouse tracking reports, and synchronous terminal replies (like CPR/DSR queries triggered by the parser thread) are written atomically and do not interleave on the transport channel.

### Threading & Concurrency Flow

```text
                                  Lattice Terminal Pipeline Runtime Context
                                 
      PTY Inbound Thread              UI Thread / Repaint Thread              Keypress / Paste Event
     ┌──────────────────┐                ┌──────────────────────┐              ┌──────────────────────┐
     │  Reads Raw Bytes │                │  Triggers Paint &    │              │  UI Component Event  │
     └─────────┬────────┘                │  Reads Render Frame  │              └──────────┬───────────┘
               │                         └──────────┬───────────┘                         │
               ▼                                    │                                     ▼
      onBytes(bytes, offset, len)                   │                              encodeKeyEvent()
     ┌──────────────────────────┐                   │                              ┌──────────────────────┐
     │  Locks inboundLock       │                   │                              │  Locks               │
     │  Guards byte-order       │                   │                              │  outboundWriteLock   │
     └─────────┬────────────────┘                   │                              └──────────┬───────────┘
               │                                    │                                         │
               ▼                                    ▼                                         ▼
         mutationLock                         mutationLock                              inputEncoder
     ┌──────────────────────────────────────────────────────────────────┐          ┌──────────────────────┐
     │                     CRITICAL SYNCHRONIZATION POINT               │          │  Encodes to ANSI     │
     │  Guards core grid mutations against concurrent reads/resizes     │          │  via inputPolicy     │
     └─────────┬────────────────────────────────────┬───────────────────┘          └──────────┬───────────┘
               │                                    │                                         │
               ▼                                    ▼                                         ▼
         parser.accept()                     readRenderFrame()                         connector.write()
     ┌──────────────────────────┐        ┌──────────────────────────┐              ┌──────────────────────┐
     │  Parses ANSI commands    │        │  Safely copies primitive │              │  Sends sequence      │
     │  via Core Sink -> Grid   │        │  grid data to render-    │              │  to PTY stdin        │
     └─────────┬────────────────┘        │  cache with NO racing    │              └──────────────────────┘
               │                         └──────────────────────────┘
               ▼
        drainResponses()
     ┌──────────────────────────┐
     │  Reads synchronous DSR/  │
     │  CPR response bytes      │
     └─────────┬────────────────┘
               │
               ▼
         connector.write()
     ┌──────────────────────────┐
     │  Writes responses under  │
     │  outboundWriteLock       │
     └──────────────────────────┘
```

---

## Asynchronous Render Publisher & Coalescing

Rendering a terminal grid onto a UI component involves copying primitive arrays of codepoints, attributes, and styles. Performing this work on either the critical PTY reading thread or the Swing UI Event Dispatch Thread (EDT) causes performance bottlenecks.

[TerminalSession](./src/main/kotlin/session/TerminalSession.kt) resolves this by offloading rendering copies to a single-threaded background daemon worker (`renderWorker`):

- **Bitwise Request Packing**: To eliminate GC allocations, render requests are packed into a single atomic `Long` (`pendingRenderRequest`):
  - **Upper 32 bits**: `scrollbackOffset` (allowing the UI to scroll back without allocating scroll states).
  - **Lower 32 bits**: `viewportRows` (specifying overscan rows for UI layout calculations).
- **Request Coalescing**: A generation counter (`pendingRenderGeneration`) tracks requested renders. If the host is streaming text at a high frequency and triggers multiple render dirty signals before the `renderWorker` completes its current frame copy, the worker skips obsolete intermediates. It publishes exactly **one** render frame representing the absolute latest terminal state. This prevents rendering backlogs and reduces UI overhead.
- **Publish Callback**: Once a render frame is safely copied and cached in the [TerminalRenderPublisher](../terminal-render-cache/src/main/kotlin/com/gagik/terminal/render/cache/TerminalRenderPublisher.kt), the `onDirty` callback is executed on the background worker thread. UI frameworks hook into this callback to queue light repaint operations (e.g. `repaint()` in Swing) on the EDT.

---

## Zero-Allocation Host Output Bridge

User inputs (like key presses) and large paste operations must be transmitted to the connector as raw byte streams. Instantiating new arrays or formatting strings for every keypress causes massive memory churn on the JVM.

The [ConnectorTerminalHostOutput](./src/main/kotlin/session/ConnectorTerminalHostOutput.kt) internal component operates as a zero-allocation bridge:

- **Pre-allocated Buffers**:
  - **1-byte Array**: For individual control characters and fast ASCII writes.
  - **ASCII Buffer (1024 bytes)**: For normal string inputs.
  - **UTF-8 Buffer (8192 bytes)**: For pasting clipboard text and multibyte inputs.
- **Manual Fast UTF-8 Encoder**:
  Rather than calling Java's string encoding routines (which allocate byte arrays), [ConnectorTerminalHostOutput.writeUtf8](./src/main/kotlin/session/ConnectorTerminalHostOutput.kt) encodes string characters directly into the pre-allocated buffer on the fly. It resolves high-/low-surrogate unicode codepoints, writes multibyte sequences directly, and flushes the buffer to the connector only when it becomes full or the writing concludes.
- **Synchronized Writes**: All output streams are locked under `outboundWriteLock` to maintain proper packet ordering.

---

## Public API Surface

The primary public API of the module centers around [TerminalSession](./src/main/kotlin/session/TerminalSession.kt):

### Configuration and Properties
- `terminal`: The underlying headless [TerminalBufferApi](../terminal-core/src/main/kotlin/core/api/TerminalBufferApi.kt) representing the current screen.
- `publisher`: The [TerminalRenderPublisher](../terminal-render-cache/src/main/kotlin/com/gagik/terminal/render/cache/TerminalRenderPublisher.kt) holding cached frames.
- `exitCode`: A volatile integer capturing the remote process exit code (populated upon normal PTY closure).
- `failure`: A volatile throwable holding any transport-level exception that led to session closure.
- `onDirty`: A callback invoked from the background worker thread after a new render frame has been successfully published to the cache.

### Operational Lifecycle Methods
- **[start(columns, rows)](./src/main/kotlin/session/TerminalSession.kt)**: Configures the terminal dimensions, resizes the underlying PTY connector, starts transport threads, and transitions the session to an active, input-accepting state.
- **[resize(columns, rows)](./src/main/kotlin/session/TerminalSession.kt)**: Resizes the core terminal grid and the transport PTY atomically under `mutationLock`, triggering a render dirty notification.
- **[setTreatAmbiguousAsWide(enabled)](./src/main/kotlin/session/TerminalSession.kt)**: Changes East Asian Ambiguous width handling policy for future parser writes.
- **[requestRender(scrollbackOffset, viewportRows)](./src/main/kotlin/session/TerminalSession.kt)**: Submits an asynchronous render task to publish the given viewport window.
- **[readRenderFrame(scrollbackOffset, viewportRows, consumer)](./src/main/kotlin/session/TerminalSession.kt)**: Directly reads a synchronous frame under `mutationLock`. Use this when rendering or copying state to prevent concurrent parser writes.
- **[close()](./src/main/kotlin/session/TerminalSession.kt)**: Shuts down the local connector, signals the parser that input has concluded, terminates the background worker, and cleans up resources idempotently.

### Wiring Factory
- **[TerminalSession.create(...)](./src/main/kotlin/session/TerminalSession.kt)**: Wires the standard pipeline components together. It creates a production session instance by wrapping:
  - The [TerminalBufferApi](../terminal-core/src/main/kotlin/core/api/TerminalBufferApi.kt) core buffer.
  - The [TerminalConnector](../terminal-transport-api/src/main/kotlin/transport/TerminalConnector.kt) transport connector.
  - A [TerminalHostEventSink](../terminal-integration/src/main/kotlin/integration/TerminalHostEventSink.kt) adapter for handling sound, title changes, or custom clipboard events.
  - A [TerminalHostPolicy](../terminal-integration/src/main/kotlin/integration/TerminalHostPolicy.kt) for terminal gap maps and behavior control.
  - A [TerminalInputPolicy](../terminal-input/src/main/kotlin/input/policy/TerminalInputPolicy.kt) to configure Backspace mapping and paste sanitization.

---

## Testing Boundaries and Mocking Doctrine

Ensuring race-free synchronization and precise protocol ordering requires tests that evaluate physical concurrency:

- **Headless Pipeline Tests**: [TerminalSessionHeadlessTest.kt](./src/test/kotlin/session/TerminalSessionHeadlessTest.kt) validates that host-bound printable bytes mutate the headless state correctly, control sequences parse flawlessly across chunked transport buffers, and modes (like bracketed paste and mouse tracking) apply correctly before input transmission.
- **Concurrency Integration Tests**: [TerminalSessionTest.kt](./src/test/kotlin/session/TerminalSessionTest.kt) uses strict thread synchronization constructs (`CountDownLatch`, multi-threaded execution runnables) to prove:
  - PTY replies and user inputs do not interleave on the write channel.
  - UI reading blocks concurrent host mutations.
  - Resize operations are serialized relative to row copy routines.
  - High-frequency dirty notifications are correctly coalesced.
  - Render publish failures recover cleanly upon subsequent generations.
- **Mocking Strategy**: The session test suite uses [MockConnector](../terminal-testkit/src/main/kotlin/testkit/MockConnector.kt) from the testkit module. This avoids spinning up real system PTYs or running complex subprocesses for unit level verification, while still asserting exact byte-level transmission and transition codes.
