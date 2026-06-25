# Transport Connector Lifecycle & Thread Invariants

This document details the lifecycle phases, thread safety rules, and memory consumption rules required by the [TerminalConnector](file:///c:/Users/gagik/IdeaProjects/terminal-buffer/jvterm-transport-api/src/main/kotlin/io/github/jvterm/transport/TerminalConnector.kt) contract.

---

## 1. Lifecycle Phases

A transport connector progresses through three distinct states:

```
    [Created] ──► start(listener) ──► [Active / Running] ──► close() ──► [Closed]
```

### A. Created
* The connector is initialized and configures any native process handles or network connection sockets.
* **Invariant**: No reading thread is active, and no events are dispatched.

### B. Active / Running
* Toggled by calling `start(listener: TerminalConnectorListener)`.
* The connector spawns its background reader/watcher threads and begins emitting events via `onBytes`, `onError`, and `onClosed`.
* **Constraint**: `start()` must be idempotent. Calling it multiple times on an already active connector should have no effect.

### C. Closed
* Toggled by calling `close()` (which implements `AutoCloseable`).
* Native processes are terminated, sockets/streams are closed, and background threads are interrupted/joined.
* Once closed, the connector transitions to a terminal state; it cannot be restarted.

---

## 2. Thread Safety Constraints

### Outbound Write Threading
* The `write(bytes, offset, length)` method can be called concurrently from multiple threads (e.g. keyboard inputs from a Swing UI thread, pasted text from a system clipboard executor, or escape-sequence query responses from the session thread).
* **Rule**: Implementation classes must serialize outbound writes using synchronized blocks, locks, or write queues to prevent data corruption.

### Inbound Read Threading
* The connector's reader loop typically runs on a dedicated background socket/process thread.
* **Rule**: `onBytes` callbacks must be serialized and called sequentially. The connector must not dispatch overlapping `onBytes` events to a listener from separate threads concurrently.

---

## 3. Synchronous Byte-Consumption Invariants

To keep memory allocation at zero on hot I/O paths, both incoming and outgoing byte transfers are governed by synchronous consumption contracts:

### outbound: `TerminalConnector.write(...)`
```kotlin
fun write(bytes: ByteArray, offset: Int, length: Int)
```
* **Invariant**: The caller retains ownership of the `bytes` array and may mutate or reuse it immediately after `write` returns.
* **Rule**: The connector **must synchronously consume** (e.g., write to OS buffers, network sockets) or make a defensive copy of the byte range before returning from the function.

### inbound: `TerminalConnectorListener.onBytes(...)`
```kotlin
fun onBytes(bytes: ByteArray, offset: Int, length: Int)
```
* **Invariant**: The connector retains ownership of the `bytes` array and may overwrite it on the next read cycle.
* **Rule**: The listener **must synchronously process** (e.g., parse, store) the byte range before returning. It must not cache references to the raw `bytes` array or access it asynchronously.
