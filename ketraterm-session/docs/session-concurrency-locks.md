# Session concurrency and locking invariants

The session consumes borrowed inbound bytes and performs parser/core mutation synchronously. Ordinary input encoding and core-response copying also run on the producer. Paste and text replacement retain their source and admission-time modes/policy for background encoding. One session child coroutine performs all connector writes on the injected I/O dispatcher.

## Retained monitors

- `mutationLock` serializes parser/core mutation, resize and render extraction. A borrowed `TerminalRenderFrame` is valid only while its callback holds this monitor. Consumers must copy promptly and must not call a mutating session API from the callback.
- `outboundWriteLock` protects the ordinary encoder's scratch, input policy, startup state, and queue admission/draining. It never covers a connector write or bulk encoding. A complete input operation or response batch is admitted under one acquisition.
- `TerminalRenderPublisher` owns its lease lock so the worker can promote a back cache only when no reader still leases the front cache.

When both session monitors are needed, acquire mutation before outbound. The writer copies queued bytes into its own scratch under outbound serialization, releases the monitor, and only then calls the connector. Producers cannot modify that scratch until the synchronous connector call returns.

There is no inbound monitor. `TerminalConnector` guarantees serial, ordered delivery of borrowed byte ranges, and `onBytes` consumes each range before returning. The retained monitor sections never suspend; do not replace them with coroutine mutexes.

## Admission and ordering

1. The connector invokes `onBytes` in stream order; parsing runs under mutation serialization.
2. The session drains all available core-response bytes under mutation and outbound serialization into one queue transaction. The 1 KiB core-response scratch is copied before reuse.
3. Ready startup input is encoded and queued as one operation, including its final Enter, after replies.
4. Ordinary UI input uses the same byte transaction. A transaction that throws rolls back its staged bytes, preserving earlier committed operations.
5. Paste and text replacement reserve a position between committed byte transactions. Admission captures the packed input mode word and immutable input policy under outbound serialization. Later mode or policy changes affect later input only.
6. The worker writes preceding bytes, then streams each bulk operation to completion before consuming later bytes or bulk operations. One worker-owned encoder reuses the existing input implementation with the captured snapshot. Its mutable state and scratch are independent of the producer encoder. Delete, Backspace and bracketed-paste phases stay contiguous even across multiple native calls.
7. Byte transitions from empty to nonempty and bulk admissions wake the conflated writer. Empty byte transactions do not wake it. Render invalidation remains independent.

Produced and consumed byte counters locate bulk operations without per-key markers. They count committed ring bytes, excluding bulk bytes; their difference is bounded by the ring budget. A non-suspending drain keeps active bulk references out of the coroutine continuation while it waits for new work.

## Bounds and backpressure

The ring starts at 16 KiB and grows on demand to an 8 MiB hard limit. The writer has one additional 16 KiB scratch buffer. Growth temporarily retains the old ring while copying; less than 16 MiB of ring storage is live during growth. No per-key payload objects or request list are retained. Coroutine wake-ups can allocate; rendering does not enqueue output merely because a frame is painted.

Bulk input has two shared limits: 16 outstanding operations and 16,777,216 work units, including active work. One unit is one retained UTF-16 code unit or one requested deletion action. Accounting uses Long arithmetic before summing replacement/deletion counts. Text contributes at most 32 MiB of source character storage; encoded expansion is streamed through fixed encoder scratch, not materialized into a full byte array. The operation limit also bounds per-request overhead. Empty paste and replacement events without text or deletions need no reservation. These limits accommodate pastes larger than the ordinary byte queue while bounding retained data and pending encoding work.

A slow connector backpressures bulk encoding on the I/O worker. Producers can continue accepting input under their remaining budgets. Non-suspending input APIs fail the session if a reservation or complete byte transaction cannot fit; they never wait for queue capacity or silently drop an accepted operation. Rejected admission publishes none of that operation. A later transport failure or close can interrupt an already writing operation.

## Acceptance and lifecycle

Input methods return after admission, not transport completion. For ordinary input this includes encoding/copying; for paste and replacement it includes source retention and mode/policy capture. Startup `SUBMITTED` also means queue acceptance. The connector contract is unchanged: each `write` synchronously consumes or copies the supplied bytes. Embedders supplying a custom input encoder retain synchronous invocation and own its output sink; `TerminalSession.create` wires the standard shared writer.

`state` retains `Created`, `Running`, or `Closed`. Budget exhaustion and native write failure use `Closed.event.failure`, close the connector, discard pending output, and cancel session children. A failed transport write may already have sent a prefix; no bytes are retried.

Local close publishes the closed state and calls `connector.close` before taking cleanup locks. It does not join the writer while a native call is blocked. Remote close cancels pending writes too. A connector must tolerate concurrent close; session cancellation alone cannot interrupt an arbitrary native call. The ring is cleared/released and pending bulk references are dropped on cleanup. While open, active bulk work remains charged to the budgets until its callback returns. The bulk sink checks closure/cancellation before every chunk; a racing native call already entered can finish, and pure encoding between writes is bounded by admitted work. Writer scratch is cleared when its call returns and the coroutine unwinds.

## Clipboard read lifetime and output commitment

The host validates selectors before admission. The session drains previously
generated core replies before admitting a clipboard query, including queries
in the same parser chunk. A session-bound suspending provider runs on the I/O
dispatcher. Consent and platform clipboard access belong to that provider;
normal input and other replies continue while it waits.

One request occupies the session slot until its queued reply is retired and
its provider job has actually completed. Cancellation of non-cooperative native
work does not free the slot or start a replacement worker. Product adapters
must additionally bound native work across their sessions and retain platform
client context. The headless session cannot bound allocations inside providers.

Admission starts an eight-second deadline on a monotonic time source. A timer
runs on the session worker dispatcher, independent of blocking I/O. Access and
output commitment also check elapsed time, so a delayed timer cannot permit
late clipboard access or data. At expiry, an empty denial is admitted only when
the outbound writer is idle; busy output causes silence. No timeout denial is
queued behind older output. The writer must commit it before 8.1 seconds from
admission: the 100 ms scheduling window is anchored to the original deadline,
not timer execution or queue admission. A delayed I/O dispatcher or timer
cannot extend it. Later timeout replies and expired queued data are discarded.

Owned clipboard replies have a separate, single reservation capped at 8 MiB of
complete wire bytes, in addition to the ordinary byte ring and bulk-source
budgets. Validation derives a raw-byte ceiling from both this bound and the
host's maxDecodedBytes policy before allocating. The default raw limit is 1 MiB.
Only the Base64 payload is retained; temporary UTF-8 storage is cleared. The
writer consumes the owned payload directly in bounded ranges without copying
it through the ring. Queued cancellation clears/releases payload references;
active writing retains ownership until its release callback. The writer calls
that callback exactly once after output or discard.

Host policy publication and clipboard commitment share outbound serialization
(with mutation acquired first when both are needed). Read denial, Allow-to-Ask,
response-family denial, and a lowered byte limit permanently retire pending
work. A later Allow cannot revive it. Immediately before the first native write,
the writer rechecks current permissions and elapsed time, then commits the
whole frame under the outbound lock. After that point, policy changes cannot
retract bytes: the frame finishes or transport failure/close aborts it. Native
writes still run outside the lock. Close can interrupt further chunks.

Execution audits contain only validated selectors and an outcome. They may run
on session workers; callbacks must be thread-safe, prompt, and must not reenter
session mutation. Provider exceptions become content-free failure outcomes,
without logging their messages. Do not infer execution from admission audits.

## Tests

Byte-encoding fixtures may inject an eager test I/O dispatcher. Output scheduling tests instead use one controlled test scheduler or real threads with entered/release/completed handshakes. A returned input call or an exited fake process is not proof that queued output was written. PTY reply tests keep the child alive until the expected write completes.
