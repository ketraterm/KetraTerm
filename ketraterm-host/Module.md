# Module ketraterm-host

## KetraTerm Host (`:ketraterm-host`)

The `ketraterm-host` module serves as the production bridge and adapter layer between the byte-stream parser (`ketraterm-parser`) and the headless state machine/grid engine (`ketraterm-core`).

It acts as the single, thin, and explicit translation point where abstract semantic ANSI/DEC protocols become concrete terminal grid mutations, mode changes, and host-facing events.

---

## Upstream Dependencies
- **`:ketraterm-protocol`** (vocabulary, mode IDs, enums)
- **`:ketraterm-parser`** (FSM, UTF-8, semantic command sinks)
- **`:ketraterm-core`** (grid representation, text buffer, cell attributes, modes)

---

## Architectural Role & Pipeline Flow

The terminal pipeline operates in strict, unidirectional layers to preserve a Strong Single Responsibility Principle (SRP). `ketraterm-host` sits at the center of this pipeline:

```mermaid
graph TD
    HostBytes[Raw Byte Stream] -->|UTF-8 / Escape Sequences| Parser[ketraterm-parser]
    Parser -->|TerminalCommandSink semantic calls| Integration[ketraterm-host: HostCommandAdapter]
    Integration -->|TerminalBuffer mutations| Core[ketraterm-core]
    Integration -->|HostEventSink callbacks| HostApp[Host UI Application]
```

### What the Host Adapter Owns:
1. **Semantic Translation**: Mapping high-level `TerminalCommandSink` callbacks into atomic `TerminalBuffer` calls.
2. **Coordinate Normalization**: Converting zero-based parser indices into DEC-compatible, one-based inclusive coordinates expected by core APIs.
3. **Safety Policies**: Intercepting and clamping unbound protocol payloads (like OSC 8 hyperlink URLs) to prevent memory exhaustion.
4. **Host Metadata Event Forwarding**: Dispatching non-grid events (like the terminal bell or window/icon title changes) to the host environment.

---

## Sub-Documentation

For details on the hyperlink registry and mapping rules:
* [hyperlink-registry.md](docs/hyperlink-registry.md) - OSC 8 URI mapping, numeric cell keys, and LRU eviction thresholds.
* [command-adapter-mapping.md](docs/command-adapter-mapping.md) - Coordinate systems, screen buffer swapping logic, and soft/hard resets.

---

## How to Use

The following example shows how to instantiate the adapter and wire the parser to the core buffer:

```kotlin
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.parser.TerminalParser
import io.github.ketraterm.host.HostCommandAdapter
import io.github.ketraterm.host.HostEventSink
import io.github.ketraterm.host.HostPolicy

fun main() {
    // 1. Create the backend core buffer
    val buffer: TerminalBuffer = TerminalBuffers.create(width = 80, height = 24)

    // 2. Create a host event sink to handle non-grid metadata events (e.g. system bell)
    val eventSink = object : HostEventSink {
        override fun bell() {
            println("[Visual Bell Triggered]")
        }
        override fun iconTitleChanged(title: String) {}
        override fun windowTitleChanged(title: String) {}
    }

    // 3. Instantiate the adapter, wiring it to the buffer and event sink
    val adapter = HostCommandAdapter(
        terminal = buffer,
        hostEvents = eventSink,
        hostPolicy = HostPolicy()
    )

    // 4. Wire the parser to use this adapter as its Command Sink
    val parser = TerminalParser(sink = adapter)

    // 5. Feed raw bytes (e.g., cursor up sequence "CSI A")
    val data = "\u001B[A".toByteArray(Charsets.US_ASCII)
    parser.accept(data, 0, data.size)
}
```

---

## How to Extend: Custom Event Sinks

To react to non-grid events in a custom application UI (for example, to display window titles in an OS frame header), implement the [HostEventSink](src/main/kotlin/io/github/ketraterm/host/HostEventSink.kt) interface and pass it to the `HostCommandAdapter` constructor during startup.
