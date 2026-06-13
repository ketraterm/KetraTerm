# JvTerm Session (`:jvterm-session`)

The `jvterm-session` module is the central orchestration hub and runtime synchronization engine of the JvTerm Terminal pipeline. It coordinates the data-flow between the asynchronous transport layer, the byte-stream parser, the headless terminal grid, and platform-agnostic keyboard/mouse input encoders.

The module encapsulates the synchronization policies necessary to support concurrent multithreaded execution—ensuring that rapid host output, user keystrokes, window resizing, and UI rendering frames never corrupt the terminal state.

---

## Upstream Dependencies
- **`:jvterm-protocol`** (vocabulary, mode IDs, enums)
- **`:jvterm-render-api`** (render frame contracts)
- **`:jvterm-render-cache`** (triple-buffered frame publication)
- **`:jvterm-transport-api`** (duplex connector contracts)
- **`:jvterm-parser`** (byte-stream parser)
- **`:jvterm-core`** (headless terminal grid)
- **`:jvterm-host`** (command mapping and security policies)
- **`:jvterm-input`** (keyboard/mouse encoding and policies)
- **`:jvterm-testkit`** (test connector doubles)

---

## Concurrency and Locking Architecture

In a multithreaded runtime, events arrive concurrently from three directions: the host transport (background read thread), the host OS (resize and window focus events), and the local UI drawing loop. To prevent state corruption, `TerminalSession` coordinates concurrency through three locks:

1. **`inboundLock`**: Guards the entry of raw host bytes on `onBytes`. It ensures bytes are fed to the parser in absolute sequential stream order.
2. **`mutationLock`**: The core-critical section. It locks during all parser `accept()` steps, core `resize()` operations, and render copying steps.
   > [!NOTE]
   > While `mutationLock` is held by a thread copying the screen state, background data-processing and resizing are blocked. This guarantees that UI drawing loops never view a half-mutated row or mismatched dimensions.
3. **`outboundWriteLock`**: Synchronizes all writes to PTY stdin. It guarantees that user keystrokes, paste payloads, mouse tracking reports, and synchronous terminal replies are written atomically and do not interleave.

```text
                                  JvTerm Terminal Pipeline Runtime Context
                                 
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

Rendering a terminal grid onto a UI component involves copying primitive arrays. Performing this work on either the critical transport reading thread or the UI Event Dispatch Thread causes performance bottlenecks. `TerminalSession` offloads rendering copies to a background worker:

* **Coalescing**: If the host streams text at high frequency and triggers multiple dirty signals before the worker completes its current copy, the worker skips obsolete intermediate frames. It publishes exactly **one** render frame representing the latest terminal state, preventing rendering backlogs.
* **Publish Callback (`onDirty`)**: Once a render frame is copied, the `onDirty` callback is executed on the background thread. UI frameworks hook into this callback to queue light paint/repaint operations.

---

## 🔗 How to Use

The following example shows how to create and start a `TerminalSession` by wiring together the core, transport, host, and input elements:

```kotlin
import io.github.jvterm.core.TerminalBuffers
import io.github.jvterm.session.TerminalSession
import io.github.jvterm.transport.TerminalConnector
import io.github.jvterm.host.TerminalHostEventSink
import io.github.jvterm.host.TerminalHostPolicy
import io.github.jvterm.input.policy.TerminalInputPolicy

fun setupSession(connector: TerminalConnector) {
    // 1. Create the backend core buffer
    val terminal = TerminalBuffers.create(width = 80, height = 24)

    // 2. Define a host event sink for titles and bell
    val hostEventSink = object : TerminalHostEventSink {
        override fun bell() { println("Bell!") }
        override fun iconTitleChanged(title: String) {}
        override fun windowTitleChanged(title: String) {}
    }

    // 3. Create the session instance using the factory builder
    val session = TerminalSession.create(
        terminal = terminal,
        connector = connector,
        eventSink = hostEventSink,
        hostPolicy = TerminalHostPolicy(),
        inputPolicy = TerminalInputPolicy.DEFAULT
    )

    // 4. Hook into the render dirty callback
    session.onDirty = {
        // Trigger UI repaints here based on published cached frames
        println("Render cache updated. Frame generation: ${session.publisher.current()?.frameGeneration}")
    }

    // 5. Start the session and allocate resources
    session.start(columns = 80, rows = 24)

    // ... When resizing the window:
    session.resize(columns = 100, rows = 30)

    // ... When pasting clipboard content:
    session.pasteText("Pasted text")

    // ... When shutting down:
    session.close()
}
```

---

## 🔗 How to Extend: Custom Key/Mouse Events

To inject user inputs into the running session from an event listener loop, map UI events to the session's input encoding surface:

```kotlin
import io.github.jvterm.input.event.TerminalKeyEvent
import io.github.jvterm.input.event.TerminalKey
import io.github.jvterm.input.event.TerminalModifiers

fun handleKeyPress(session: TerminalSession, char: Char) {
    if (char == '\n') {
        // Encode enter key
        session.encodeKey(TerminalKeyEvent(TerminalKey.ENTER, 0, TerminalModifiers.NONE))
    } else {
        // Encode ordinary printable key
        session.encodeKey(TerminalKeyEvent(TerminalKey.NONE, char.code, TerminalModifiers.NONE))
    }
}
```

---

## Testing & Verification

The session test suite asserts race-free synchronization and precise protocol ordering under high-frequency writes and concurrent reads:
* **`TerminalSessionHeadlessTest`**: Validates basic input/output byte flows, bracketed paste mode, and mouse tracking state mappings.
* **`TerminalSessionTest`**: Uses multi-threaded constructs to prove locks prevent coordinate-tearing and check that render dirty events coalesce cleanly.

To run checks for this module:
```bash
./gradlew :jvterm-session:test
```
