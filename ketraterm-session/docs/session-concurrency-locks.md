# Session concurrency and locking invariants

The session consumes borrowed inbound bytes and performs parser/core mutation synchronously. Input encoding also remains synchronous, but standard input and core responses are copied into an owned queue before returning. One session child coroutine performs connector writes on the injected I/O dispatcher.

## Retained monitors

- `mutationLock` serializes parser/core mutation, resize and render extraction. A borrowed `TerminalRenderFrame` is valid only while its callback holds this monitor. Consumers must copy promptly and must not call a mutating session API from the callback.
- `outboundWriteLock` protects encoder scratch, input policy, startup state, and queue admission/draining. It never covers a connector write. A complete input operation or response batch is admitted under one acquisition.
- `TerminalRenderPublisher` owns its lease lock so the worker can promote a back cache only when no reader still leases the front cache.

When both session monitors are needed, acquire mutation before outbound. The writer copies queued bytes into its own scratch under outbound serialization, releases the monitor, and only then calls the connector. Producers cannot modify that scratch until the synchronous connector call returns.

There is no inbound monitor. `TerminalConnector` guarantees serial, ordered delivery of borrowed byte ranges, and `onBytes` consumes each range before returning. The retained monitor sections never suspend; do not replace them with coroutine mutexes.

## Admission and ordering

1. The connector invokes `onBytes` in stream order; parsing runs under mutation serialization.
2. The session drains all available core-response bytes under mutation and outbound serialization into one queue transaction. The 1 KiB core-response scratch is copied before reuse.
3. Ready startup input is encoded and queued as one operation, including its final Enter, after replies.
4. UI input uses the same queue transaction. Bracketed paste and text replacement cannot interleave with other operations, even when the writer divides their bytes into several native calls.
5. Queue transitions from empty to nonempty wake the conflated writer. Empty operations do not wake it. Render invalidation remains independent.

A transaction that throws rolls back all its staged bytes. Existing committed bytes retain their order. The ring starts at 16 KiB and grows on demand to an 8 MiB hard limit. The writer has one additional 16 KiB scratch buffer. Growth temporarily retains the old ring while copying; less than 16 MiB of ring storage is live during growth. No per-key payload objects or request list are retained. Coroutine wake-ups can allocate; rendering does not enqueue output merely because a frame is painted.

The current non-suspending input APIs fail the session if a complete operation cannot fit. They never wait for the transport, silently drop input, or publish a partial paste delimiter. Encoding work still runs on the producer thread. Capability status and the remaining bulk-input work are tracked in the canonical feature/gap maps.

## Acceptance and lifecycle

Input methods return after encoding and copying, not after transport completion. Startup `SUBMITTED` also means queue acceptance. The connector contract is unchanged: each `write` synchronously consumes or copies the supplied bytes. Embedders supplying a custom input encoder own that encoder's output sink; `TerminalSession.create` wires the standard shared writer.

`state` retains `Created`, `Running`, or `Closed`. Queue exhaustion and native write failure use `Closed.event.failure`, close the connector, discard pending bytes, and cancel session children. A failed transport write may already have sent a prefix; no bytes are retried.

Local close publishes the closed state and calls `connector.close` before taking cleanup locks. It does not join the writer while a native call is blocked. Remote close cancels pending writes too. A connector must tolerate concurrent close; session cancellation alone cannot interrupt an arbitrary native call. The ring is cleared/released on cleanup, and writer scratch is cleared when its call returns and the coroutine unwinds.

## Tests

Byte-encoding fixtures may inject an eager test I/O dispatcher. Output scheduling tests instead use one controlled test scheduler or real threads with entered/release/completed handshakes. A returned input call or an exited fake process is not proof that queued output was written. PTY reply tests keep the child alive until the expected write completes.
