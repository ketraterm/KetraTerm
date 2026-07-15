# Session concurrency and locking invariants

`TerminalSession` keeps transport, parser, core, input, and rendering contracts synchronous where borrowed buffers or exact byte ordering require it. Coroutines orchestrate lifecycle, timeouts, and render publication; they do not run inside parser or core mutation loops.

## Retained monitors

- `mutationLock` serializes parser/core mutation, resize and render extraction. A borrowed `TerminalRenderFrame` is valid only while its callback holds this monitor. Consumers must copy promptly and must not call a mutating session API from the callback.
- `outboundWriteLock` serializes encoder scratch-buffer use, UI input, and parser-generated response bytes. It remains a reentrant JVM monitor because the call graph is synchronous and can re-enter host output without suspension.
- `TerminalRenderPublisher` owns its lease lock so the worker can promote a back cache only when no reader still leases the front cache.

There is no inbound monitor. `TerminalConnector` guarantees serial, ordered delivery of borrowed byte ranges, and `TerminalSession.onBytes` consumes each range synchronously before returning.

Do not replace either retained monitor with coroutine `Mutex`: these sections contain no suspension, and the outbound path depends on monitor reentrancy.

## Ordering

Inbound processing follows this order:

1. The connector invokes `onBytes` in stream order.
2. The session acquires `mutationLock`, parses the entire range, and mutates core.
3. The session drains core response bytes.
4. Each response acquires `outboundWriteLock` and is written synchronously.
5. Render invalidation wakes the conflated session render worker.

UI input acquires only `outboundWriteLock`. Render extraction acquires only `mutationLock`, releases it after the primitive cache copy, then promotes through the publisher lease lock.

## Lifecycle

`TerminalSession.state` is a retained `StateFlow` with `Created`, `Running`, and `Closed` states. Local close, remote close, and transport error race through one atomic state transition. The winner publishes `Closed` before cancelling session children and ending parser input.
