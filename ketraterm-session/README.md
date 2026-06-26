# KetraTerm Session (`:ketraterm-session`)

The `ketraterm-session` module is the central orchestration hub and runtime synchronization engine of the **KetraTerm Terminal** pipeline. It coordinates the data-flow between the asynchronous transport layer, the byte-stream parser, the headless terminal grid, and platform-agnostic keyboard/mouse input encoders.

The module encapsulates the synchronization policies necessary to support concurrent multithreaded execution—ensuring that rapid host output, user keystrokes, window resizing, and UI rendering frames never corrupt the terminal state.

---

## Upstream Dependencies
- **`:ketraterm-protocol`** (vocabulary, mode IDs, enums)
- **`:ketraterm-render-api`** (render frame contracts)
- **`:ketraterm-render-cache`** (triple-buffered frame publication)
- **`:ketraterm-transport-api`** (duplex connector contracts)
- **`:ketraterm-parser`** (byte-stream parser)
- **`:ketraterm-core`** (headless terminal grid)
- **`:ketraterm-host`** (command mapping and security policies)
- **`:ketraterm-input`** (keyboard/mouse encoding and policies)
- **`:ketraterm-testkit`** (test connector doubles)

---

## Architectural Role & Runtime Context

```text
UI Adapter  --->  Terminal Event Loop Actor  --->  TerminalInputEncoder  --->  TerminalHostOutput  --->  PTY stdin
                                                                                      ^
Parser/Core Replies  ----------------------------------------------------------------+
```

The session integrates all independent components into a single lifecycle loop:
1. **PTY Inbound**: Raw bytes arriving from [TerminalConnector](../ketraterm-transport-api/src/main/kotlin/io/github/ketraterm/transport/TerminalConnector.kt) are parsed and applied to the core [TerminalBuffer](../ketraterm-core/src/main/kotlin/io/github/ketraterm/core/api/TerminalBuffer.kt).
2. **UI Outbound**: Key strokes and paste events are serialized, encoded, and written back to the connector stdin.
3. **Cache Synchronization**: Render cache updates are scheduled on background workers and published to the triple-buffered cache.

---

## Sub-Documentation

For detailed specifications on session locks and thread architectures:
* [session-concurrency-locks.md](docs/session-concurrency-locks.md) - Core concurrency locks (`inboundLock`, `mutationLock`, `outboundWriteLock`) and lock order routing.
* [asynchronous-render-coalescing.md](docs/asynchronous-render-coalescing.md) - Background render thread executors, dirty frame invalidation, and pre-allocated query-response buffers.

---

## How to Use

The following example shows how to create and start a `TerminalSession` by wiring together the core, transport, host, and input elements:

```kotlin
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.host.HostEventSink
import io.github.ketraterm.host.HostPolicy
import io.github.ketraterm.input.policy.TerminalInputPolicy

fun setupSession(connector: TerminalConnector) {
    // 1. Create the backend core buffer
    val terminal = TerminalBuffers.create(width = 80, height = 24)

    // 2. Define a host event sink for titles and bell
    val hostEventSink = object : HostEventSink {
        override fun bell() { println("Bell!") }
        override fun iconTitleChanged(title: String) {}
        override fun windowTitleChanged(title: String) {}
    }

    // 3. Create the session instance using the factory builder
    val session = TerminalSession.create(
        terminal = terminal,
        connector = connector,
        eventSink = hostEventSink,
        hostPolicy = HostPolicy(),
        inputPolicy = TerminalInputPolicy.DEFAULT
    )

    // 4. Hook into the render dirty callback
    session.onDirty = {
        // Trigger UI repaints here based on published cached frames
        println("Render cache updated.")
    }

    // 5. Start the session and allocate resources
    session.start(columns = 80, rows = 24)

    // ... When resizing the window:
    session.resize(columns = 100, rows = 30)

    // ... When shutting down:
    session.close()
}
```
