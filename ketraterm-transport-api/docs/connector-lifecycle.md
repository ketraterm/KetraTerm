# Transport Connector Lifecycle & Thread Invariants

This guide explains the
[`TerminalConnector`](../src/main/kotlin/io/github/ketraterm/transport/TerminalConnector.kt)
and
[`TerminalConnectorListener`](../src/main/kotlin/io/github/ketraterm/transport/TerminalConnectorListener.kt)
contracts. Their KDoc is authoritative. Transport implementations document their
own blocking, timeout, and platform behavior.

## 1. Lifecycle Phases

### A. Created

A connector may already own a process, socket, or other resources before
`start(listener)` is called. The owner must close those resources even if startup
never succeeds. Local `close()` may precede startup and prevents a later start.

### B. Active / Running

A connector accepts at most one start attempt. A repeated attempt, including after
failure or closure, throws `IllegalStateException` without replacing the first
listener or starting more workers. There is no restart operation.

Install the listener before workers can deliver bytes. Callbacks may occur before
`start` returns, and the API does not promise a particular callback thread.
Connectors own any reader, watcher, or writer workers; callers must not assume
that `start` starts a specific number of threads.

### C. Closed

Remote closure and local shutdown are distinct:

- `onClosed(exitCode)` reports remote closure. All final byte callbacks must finish
  first, and no bytes may follow it. The exit code is `null` when unavailable or
  when the transport has no process exit code.
- `onError(error)` is terminal for the session. A listener may close the connector
  from this callback to release resources.
- `close()` requests local shutdown. It is idempotent and may be called before
  startup or from a lifecycle callback. It does not promise a remote-close event
  or delivery of bytes still buffered in the transport.

Do not use observation of process exit as a substitute for output completion.
For example, a PTY process can exit while its final output remains unread; the
connector must finish delivery before reporting `onClosed`.

The API does not promise that `onError` and `onClosed` are mutually exclusive.
The [PTY connector](../../ketraterm-pty/src/main/kotlin/io/github/ketraterm/pty/PtyConnector.kt)
reports a reader failure through `onError`, releases resources, and then reports
`onClosed(null)`. A `TerminalSession` retains the first termination event.

## 2. Thread Safety Constraints

### Outbound Write Threading

The session serializes host-bound writes and determines their order, including
encoded user input and permitted terminal replies. UI callers submit intent
through the session rather than writing directly to its connector.

The connector interface does not define a universal concurrent-write policy.
Direct users must establish ordering themselves or rely on an explicitly
documented implementation guarantee. A lock can prevent interleaved writes, but
it cannot infer the intended order of independently submitted terminal events.

`write`, `resize`, `start`, and `close` are ordinary synchronous methods. Depending
on the implementation, they can perform blocking I/O or wait for worker cleanup.
Synchronous buffer consumption is a lifetime rule, not a nonblocking guarantee.

### Inbound Read Threading

Invoke `onBytes` serially and in stream order for the started listener. Arbitrary
chunk boundaries may split characters and control sequences; neither the
connector nor its listener should treat a chunk as a complete terminal message.

If `onBytes` throws, stop byte delivery and report the original exception through
`onError`, including cancellation exceptions. Local closure may suppress that
report. Never retry the failed range: the consumer may have already processed a
prefix of it.

Lifecycle listeners can close the connector reentrantly. Cleanup must not depend
on the callback returning first. In particular, avoid joining a worker that is
waiting for the current callback, or holding a lock while invoking a callback
that needs the same lock for shutdown.

### Foreground-process metadata

`foregroundProcessName()` returns an executable basename, without arguments or a
path, or `null` when unavailable, ambiguous, or idle. It is optional metadata and
does not establish terminal state.

Queries must be bounded, tolerate concurrent close, and never launch commands.
Call them off UI and byte-processing threads because implementations may query
the operating system. Platform detection limits belong in the connector's own
documentation.

## 3. Synchronous Byte-Consumption Invariants

### outbound: `TerminalConnector.write(...)`

The caller owns the array and may overwrite it immediately after `write` returns.
Consume the supplied range synchronously or copy it before returning. Queuing the
caller's array for later I/O violates the contract. Completion does not guarantee
that the remote process has read or acted on the data.

The default range is the remaining suffix: `offset = 0` and
`length = bytes.size - offset`. Implementations can use the public
`ByteArray.checkBounds(offset, length)` extension to validate offsets and lengths
without an overflowing addition. An empty range at the end of an array is valid.

### inbound: `TerminalConnectorListener.onBytes(...)`

The connector owns the array and may reuse it as soon as the callback returns.
The listener must process the range during the callback or copy it for later use.
Retaining the array reference, including through a queued lambda, is unsafe.

This borrowing contract allows buffer reuse. It does not guarantee that a
connector or consumer performs no allocations: asynchronous handoff, transport
libraries, and downstream processing can require copies or other storage.
