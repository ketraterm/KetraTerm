# Session concurrency and locking invariants

The session consumes borrowed inbound bytes and performs parser/core mutation synchronously. Ordinary input encoding, exact host-byte copying and core-response copying also run on the producer. Paste, text replacement and compound semantic input retain their source and admission-time modes/policy for background encoding. One session child coroutine performs all connector writes on the injected I/O dispatcher.

## Retained monitors

- `mutationLock` serializes parser/core mutation, resize and render extraction. A borrowed `TerminalRenderFrame` is valid only while its callback holds this monitor. Consumers must copy promptly and must not call a mutating session API from the callback.
- `outboundWriteLock` protects the ordinary encoder's scratch, input policy, startup state, and queue admission/draining. It never covers a connector write or bulk encoding. A complete input operation or response batch is admitted under one acquisition.
- `TerminalRenderPublisher` owns its lease lock. Promotion can proceed while readers pin an older front; a pinned cache cannot be recycled as a writer buffer. Session consumers borrow through `readPublishedFrame` and cannot retrieve the publisher.

When both session monitors are needed, acquire mutation before outbound. The writer copies queued bytes into its own scratch under outbound serialization, releases the monitor, and only then calls the connector. Producers cannot modify that scratch until the synchronous connector call returns.

There is no inbound monitor. `TerminalConnector` guarantees serial, ordered delivery of borrowed byte ranges, and `onBytes` consumes each range before returning. The retained monitor sections never suspend; do not replace them with coroutine mutexes.

## Admission and ordering

`submitBytes(bytes, offset, length)` copies the selected range before returning.
The caller must not mutate it during submission and may reuse it immediately
afterward. Offsets/counts are validated with overflow-safe arithmetic before
lifecycle checks; invalid slices throw `IllegalArgumentException` without changing
the session. The default length is the remaining range from `offset`. Exact bytes
are trusted host input, never decoded, sanitized or framed as paste. Keyboard,
mouse, IME and paste producers should continue to use semantic events.

`submitInput(event)` admits one existing immutable `TerminalInputEvent`.
`submitInput(events)` copies a stable caller-supplied list and reserves one bulk
operation for the complete sequence. All its events encode with one mode/policy
snapshot; their bytes cannot interleave with any later input or reply. Ordinary
admission does not validate shell editing state; use the conditional overload below.

Both APIs return `TerminalInputAdmission`: `ACCEPTED`, `NOT_RUNNING`, `CLOSED`, or
`CAPACITY_EXCEEDED`. The latter closes the session through its existing failure
channel and publishes none of the rejected operation. Empty and mode/policy-
suppressed input is accepted while running; acceptance does not promise bytes.
Existing `encode*` methods delegate to this admission path and discard the result.
Concurrent producers are ordered by admission under the outbound monitor, not
by wall-clock call start. Writer wake-up follows release of that monitor, even
with an eager I/O dispatcher.

1. The connector invokes `onBytes` in stream order; parsing runs under mutation serialization.
2. The session drains all available core-response bytes under mutation and outbound serialization into one queue transaction. The 1 KiB core-response scratch is copied before reuse.
3. Ready startup input is encoded and queued as one operation, including its final Enter, after replies.
4. Ordinary UI input uses the same byte transaction. A transaction that throws rolls back its staged bytes, preserving earlier committed operations.
5. Paste and text replacement reserve a position between committed byte transactions. Admission captures the packed input mode word and immutable input policy under outbound serialization. Later mode or policy changes affect later input only.
6. The worker writes preceding bytes, then streams each bulk operation to completion before consuming later bytes or bulk operations. One worker-owned encoder reuses the existing input implementation with the captured snapshot. Its mutable state and scratch are independent of the producer encoder. Delete, Backspace and bracketed-paste phases stay contiguous even across multiple native calls.
7. Byte transitions from empty to nonempty and bulk admissions wake the conflated writer. Empty byte transactions do not wake it. Render invalidation remains independent.

Produced and consumed byte counters locate bulk operations without per-key markers. They count committed ring bytes, excluding bulk bytes; their difference is bounded by the ring budget. A non-suspending drain keeps active bulk references out of the coroutine continuation while it waits for new work.

## Conditional command edits

`captureCommandEdit()` captures the selected producer's authoritative snapshot and
revision together with session input/output revisions. It returns null unless the
session is running, editing is available, the writer is idle, and the producer
supports synchronized revision checks. Capture before calculating or requesting
suggestions, then pass the same context to `submitInput(expected, events)`.

Final admission holds mutation serialization, the producer's revision guard, then
outbound serialization through validation and reservation. Intervening nonempty
input, output, resize, or any host-model assignment invalidates the context,
including equal assignments and changes away from and back to the same text.
Rejection returns `STALE_CONTEXT`, `UNSUPPORTED_CONTEXT`, `CANCELLED`, or the usual
lifecycle result without reserving capacity or sending an edit prefix. A valid
edit uses the same bounded compound queue as ordinary input. Revisions conservatively
invalidate on mode-suppressed semantic input too; there is no attempt to infer
whether input will affect the shell editor.

For a host-owned model, replace the StateFlow-only projection with
`TerminalShellCommandLineState` and pass it to `TerminalShellIntegrationFactory.host`.
Every `value` assignment advances its guarded revision. The legacy overload remains
readable but cannot provide conditional edit contexts. Custom producers may implement
`withCommandLine`; their guard must cover the entire callback, with notifications
outside the guard and no reverse acquisition of the session mutation monitor.
The optional OSC producer uses session mutation serialization for its checks.

The host still decides when its editing model is trustworthy: publish null while
input is pending in the host or shell, and publish authoritative snapshots after
the associated output. An idle local writer is not acknowledgement that a remote
shell processed its input. This contract cannot make an inaccurate model safe.
`expected.cancel()` permanently rejects subsequent checks, but a cancellation racing
final admission can lose and cannot retract accepted bytes. Observe closed session
state for failure; acceptance remains distinct from writing and shell execution.

The default session-backed Swing suggestion handler captures before provider work
and conditionally admits the validated grapheme replacement at acceptance. It emits
accepted feedback only on admission. Host-managed popups must capture their own
context and use the conditional API; directly invoking this default handler without
a captured Swing request throws `IllegalStateException`. Custom suggestion handlers
retain responsibility for their editing and feedback behavior.

## Bounds and backpressure

The ring starts at 16 KiB and grows on demand to an 8 MiB hard limit. The writer has one additional 16 KiB scratch buffer. Growth temporarily retains the old ring while copying; less than 16 MiB of ring storage is live during growth. No per-key payload objects or request list are retained. Coroutine wake-ups can allocate; rendering does not enqueue output merely because a frame is painted.

Bulk input has two shared limits: 16 outstanding operations and 16,777,216 work units, including active work. One unit is one retained UTF-16 code unit or one requested deletion action. Each compound contains at most 256 events and charges at least one unit per event, including otherwise empty/suppressed events; associated key text counts too. Accounting uses Long arithmetic before summing replacement/deletion counts. Text contributes at most 32 MiB of source character storage; encoded expansion is streamed through fixed encoder scratch, not materialized into a full byte array. The operation/event limits also bound retained reference overhead. Empty lists, standalone empty paste and replacement events without text or deletions need no reservation. These limits accommodate pastes larger than the ordinary byte queue while bounding retained data and pending encoding work.

A slow connector backpressures bulk encoding on the I/O worker. Producers can continue accepting input under their remaining budgets. Non-suspending input APIs fail the session if a reservation or complete byte transaction cannot fit; they never wait for queue capacity or silently drop an accepted operation. Rejected admission publishes none of that operation. A later transport failure or close can interrupt an already writing operation.

## Acceptance and lifecycle

Startup claims one attempt under the connector lifecycle monitor, independently
of observable readiness. State remains `Created` and input admission returns `NOT_RUNNING` until
`connector.start` returns successfully. Synchronous startup output is parsed and
its replies enter the bounded queue, but the writer remains unstarted. Session
then publishes `Running` before starting the writer, so even reentrant observers
can admit keys, paste, or replacement without reaching an unready connector.
Replies already queued precede that input. Startup failure or closure discards
the queue, cancels the unstarted writer, and never publishes `Running`. Reentrant
closure from a `Running` observer closes the already-started connector; it cannot
cause another start. Concurrent startup and connector disposal remain serialized.

Input methods return after admission, not transport completion. For ordinary input this includes encoding/copying; for paste and replacement it includes source retention and mode/policy capture. Startup `SUBMITTED` also means queue acceptance. Each connector `write` synchronously consumes or copies the supplied bytes. Custom encoder factories receive the same session-owned output paths and create independent admission and bulk instances; their calls and policy updates are serialized per instance, while the two instances may run concurrently. Rejected policy updates leave the session policy and reported Backarrow default unchanged.

`state` retains `Created`, `Running`, or `Closed`. Budget exhaustion and outbound worker failure use `Closed.event.failure`, close the connector, discard pending output, and cancel session children. A failed transport write or bulk encoder may already have sent a prefix; no bytes are retried.

Response-dependent hosts must observe `state` and cancel their request when it
becomes `Closed`, including when closure occurs immediately after acceptance.
They must not start waiting after a rejected admission. Cancellation of a host
wait does not retract accepted output; this API has no per-operation cancellation
or write-completion receipt. Local/remote closure discards pending work rather
than draining it. Admission overlapping shutdown may return `ACCEPTED` even
though its bytes are discarded; calls that observe shutdown return `CLOSED`.

Resize follows mutation serialization and calls the connector synchronously.
It has no position in the outbound byte queue and provides no flush barrier:
an already accepted input operation can be written after a later resize returns.
Shutdown may interrupt active output; only an already entered connector call can
finish. Closed-session presentation remains a separate contract.

The writer is essential even though session children are supervised. A connector or bulk encoder throwing `CancellationException` while the session remains open triggers the same failure shutdown, retaining that exception before it is rethrown. Cancellation after a termination event has already been claimed preserves that first event, even while connector cleanup is still running and the session job remains active. Cleanup failures are suppressed on the original failure; no replacement writer is started.

Local close claims termination and calls `connector.close` before taking cleanup locks. It does not join the writer while a native call is blocked. Remote close cancels pending writes too. A connector must tolerate concurrent close; session cancellation alone cannot interrupt an arbitrary native call. The ring is cleared/released and pending bulk references are dropped on cleanup. While open, active bulk work remains charged to the budgets until its callback returns. The bulk sink checks closure/cancellation before every chunk; a racing native call already entered can finish, and pure encoding between writes is bounded by admitted work. Writer scratch is cleared when its call returns and the coroutine unwinds. `Closed` is published after cleanup and final frame publication have been attempted.

## Closed-session presentation

Closure freezes terminal state after admitted mutation and parser EOF.
`resize` and `resizeViewport` still reject closure.
`tryResizeViewport` returns null when closure wins admission; invalid dimensions and collaborator failures still throw.
An admitted core reflow can finish during closure.
The connector resize lock serializes connector resize with disposal.
Close takes that lock before cleanup, without holding the mutation lock.
Resize takes mutation, then the connector resize lock.
It never acquires the startup lifecycle lock while holding mutation.
An active connector resize must return before connector disposal can proceed.

Swing waits for completed `Closed` state before projecting retained frames.
Its view preserves terminal columns, row identities, attributes, palette, and the final active buffer.
Width changes clip columns; they never reflow terminal content.
Height changes show a bottom-anchored window across retained history and grid rows.
Presentation offsets count rows above that window, including hidden final grid rows.
A scrolled view preserves its top row where the new bounds permit.
Font changes preserve cell coordinates and selection.
Mouse input selects and scrolls locally, even when the final terminal modes request application tracking.
An alternate buffer retains only its final grid; closure does not restore the primary buffer.
View changes use synchronized retained reads and leave session publication generation unchanged.
Disposal cancels view work and releases the binding without restarting or closing the transport.

The focused JMH benchmark measures frozen ASCII projection and cache updates.
It excludes session creation, Swing dispatch, painting, and platform costs.
On Windows with Temurin 25.0.3, two forks used three one-second warmups and five one-second measurements.
Normalized allocation was 0.005 B/update unchanged and 0.243 B/update while scrolling.
Allocation rates stayed at 0.006–0.007 MB/s with no GC events.
These small measurements include JVM/harness overhead; they do not establish exact zero allocation or a complete Swing frame budget.
Run `RetainedFrameViewportBenchmark` with `-prof gc` to reproduce this scope.

## Selected shell integration

Session selects one `TerminalShellIntegrationFactory` before starting output.
`create` dispatches parsed shell events to that producer; low-level construction
leaves custom-parser event dispatch to the caller.
With no factory it installs no recorder or command extractor. Factory creation
must not launch work. An implementation deriving metadata from output uses
`TerminalShellIntegrationContext` for serialized frame reads and read-only leases
of published caches; its synchronous protocol callbacks run under mutation
serialization. The optional OSC producer uses cold primitive fingerprint tracking.

`TerminalShellIntegrationFactory.host` reads host-owned immutable editing snapshots
without the mutation lock. The supplied bounded `TerminalShellIntegrationState`
is the only terminal-facing timeline and directory projection; OSC never
supplements it. Hosts serialize their own semantic events and publish updates
after corresponding terminal output, capturing stable line IDs before delivering
later bytes. They also refresh live-grid editing anchors after geometry changes.

One session child shares active-edit revision observation while collectors exist.
Equal values and intermediate updates may conflate; removing the final collector
stops observation and clears the retained revision. A later collector samples the
current source. Metadata `revision` is likewise conflated state, while synchronous
command-finished listeners deliver each completed record outside the model lock.
Directory listeners synchronously report future changed URIs without initial
replay; same-URI reports are suppressed. Workspace uses these semantic callbacks
so final directory and completion updates survive immediate transport closure.
Observers must return promptly; their registrations belong to the consumer.

Startup input requires a selected producer's explicit prompt readiness. Readiness
can arrive without transport output. Submission still waits until transport start
has completed and the current parser batch and its responses have been processed;
the session rechecks readiness and primary-buffer state before queue admission.
User input, closure, or submission ends readiness observation. Host flows and
metadata remain host-owned after session closure; active editing becomes
unavailable through the closed session.

## Ordered custom OSC handling

Install `TerminalCustomOscHandler` through `parserFactory` and pass its supplied sink and clipboard budget to `TerminalParsers.create`.
The parser completes preceding output before the callback and consumes later bytes after it returns.
The callback can read the session frame and publish host metadata with the matching line identity.
The host owns protocol interpretation, permissions, retained copies, and handler cleanup.
This path works with a host-owned shell model or with no shell producer.

Built-in OSC commands keep their existing host services and policy gates.
They never fall through to the custom handler, including malformed and denied requests.
See the handler KDoc for the borrowed payload, collection bound, and string recovery contract.
Custom callbacks must not wait for UI work, mutate the session, or close it.
Parser reentry and recursive session output throw before mutation.
Unhandled parser or callback failures stop the input call and propagate unchanged, including cancellation.
The connector must stop delivery and report the failure through its listener's `onError` callback.
The session then closes and retains the original cause. PTY connectors provide this failure routing.
A concurrent close waits for admitted parsing; EOF discards any incomplete OSC.

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
