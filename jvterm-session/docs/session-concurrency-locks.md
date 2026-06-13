# Session Concurrency & Locking Invariants

The `jvterm-session` module acts as the synchronization pipeline coordinator that integrates the terminal core, host transport, and input encoding. It manages concurrency using a three-tier locking system:

---

## 1. The Locking Tiers

```
                 +-----------------------+
                 |    TerminalSession    |
                 +-----------+-----------+
                             |
         ┌───────────────────┼───────────────────┐
         ▼                   ▼                   ▼
  [inboundLock]       [mutationLock]     [outboundWriteLock]
  (PTY read/parse)   (Grid state updates)  (Keystroke writes)
```

### A. `inboundLock`
* **Purpose**: Serializes execution of parser byte ingestion.
* **Scope**: Guards the [onBytes](file:///c:/Users/gagik/IdeaProjects/terminal-buffer/jvterm-session/src/main/kotlin/io/github/jvterm/session/TerminalSession.kt#L65) callback thread. It ensures that only one byte block from the transport is processed by the parser FSM at a time, preventing split-escape corruptions.

### B. `mutationLock`
* **Purpose**: Serializes all terminal grid, pen, and mode state mutations.
* **Scope**: Guards the execution of [HostCommandAdapter](file:///c:/Users/gagik/IdeaProjects/terminal-buffer/jvterm-host/src/main/kotlin/io/github/jvterm/host/HostCommandAdapter.kt) commands, terminal resizes, theme palette changes, and cursor shape settings.
* **Deadlock Protection**: Implementations of `TerminalRenderFrameReader.readRenderFrame` acquire the `mutationLock` to provide a stable, thread-safe snapshot to the render consumer. Because of this, consumers **must not** block or call mutating APIs during a frame read callback.

### C. `outboundWriteLock`
* **Purpose**: Serializes all host-bound traffic.
* **Scope**: Guards the outbound `TerminalHostOutput` write channels (e.g. keyboard inputs, clipboard pastes, and query responses generated during parsing). It prevents concurrent responses and user typing events from interleaving raw bytes on the PTY stdin stream.

---

## 2. Inbound Query-Response Lock Flow

When the parser handles an escape-sequence query (e.g. Device Status Report `DSR`):

1. The PTY thread invokes `onBytes` under **`inboundLock`**.
2. The parser processes bytes and calls a query method on `HostCommandAdapter` under **`mutationLock`**.
3. The adapter generates a response sequence and writes it to the connector.
4. To prevent interleaving with concurrent UI input typing, the write acquires the **`outboundWriteLock`**.

**Lock Ordering Rule**: `inboundLock` → `mutationLock` → `outboundWriteLock` must always be acquired in this order. Never attempt to acquire `mutationLock` if `outboundWriteLock` is already held.
