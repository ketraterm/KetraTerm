# Session concurrency and locking invariants

This guide defines session ordering, ownership, and callback contracts. For
construction and consumer entry points, start with the [README](../README.md).

Inbound byte consumption and parser/core mutation are synchronous. Ordinary
input encoding and response copying run on the submitting thread. Paste,
replacement, and compound events retain their source and admission-time
modes/policy for background encoding. One session coroutine performs all
ordered connector writes on the I/O dispatcher.

## Retained monitors

| Boundary | Protected work | Consumer obligation |
| --- | --- | --- |
| `mutationLock` | Parser/core mutation, resize, and live-frame reads | Copy borrowed frames inside the callback; do not reenter mutation. |
| `outboundWriteLock` | Admission encoder scratch, input policy, startup state, and queue admission/draining | Admit a complete input operation or reply batch atomically. |
| Publisher lease lock | Render-buffer ownership and recycling | Access published caches only through a scoped read. |

When both session monitors are needed, acquire mutation before outbound.
Conditional editing acquires mutation, the producer revision guard, then
outbound. A custom producer must never acquire terminal mutation while holding
an independent revision guard.

Connector writes and bulk encoding run outside these monitors. The writer copies
ring bytes into its own scratch under outbound serialization, then releases the
monitor before calling `write`. That scratch remains worker-owned until the
synchronous connector call returns.

There is no extra inbound monitor: `TerminalConnector` promises serial, ordered
delivery of borrowed ranges, and `onBytes` consumes each range before returning.
Monitor sections never suspend. Frame/cache callbacks must return promptly,
retain no borrowed storage, and neither close the session nor reenter mutation
or publication. Holding a published cache lease can delay recycling and closure.

## Admission and ordering

`submitBytes(bytes, offset, length)` copies exactly the selected range before
returning. Keep that range stable during the call; it may be reused afterward.
Overflow-safe slice validation runs before lifecycle checks and throws
`IllegalArgumentException` without changing the session. Default length is the
remaining range after `offset`. These bytes are trusted host input and receive
no decoding, sanitization, or paste framing.

`submitInput(event)` accepts an immutable semantic event. A list overload copies
a stable caller-supplied list and reserves one compound operation. All list events
use one mode/policy snapshot and cannot interleave with later input or replies.
Use the conditional overload for shell revision validation.

Ordinary admission returns `ACCEPTED`, `NOT_RUNNING`, `CLOSED`, or
`CAPACITY_EXCEEDED`. Empty and mode/policy-suppressed input is accepted while
running; it may emit no bytes. Capacity rejection admits no part of the operation
and closes the session through its failure channel. Existing `encode*` methods
use this path and discard the result. Encoder argument failures propagate with
staged ordinary bytes rolled back.

Concurrent producers are ordered by queue admission, not wall-clock call start:

1. Inbound parsing runs under mutation serialization.
2. Core replies are drained through reusable 1 KiB scratch into one transactional
   byte admission; scratch bytes are copied before reuse.
3. Ready startup input follows the current parser batch and replies as one
   transaction including its final Enter.
4. Ordinary events and exact bytes enter the same ring. Failed transactions
   preserve earlier committed operations.
5. Bulk operations reserve their position among ring transactions and capture
   input modes/policy once. Later changes affect later admissions.
6. The worker writes earlier ring bytes, streams each bulk operation completely,
   then consumes later work. A bulk operation may span multiple native calls,
   but its Delete, Backspace, and paste phases stay contiguous.

Writer signalling follows release of admission serialization, including with an
eager I/O dispatcher. Committed ring-byte counters locate bulk operations without
per-key markers; bulk bytes themselves do not enter those counters.

## Conditional command edits

Capture `captureCommandEdit()` before requesting or calculating asynchronous
suggestions. It returns null unless the session is running, the writer is idle,
an authoritative editing snapshot is available, and the selected producer supports
synchronized revision checks. Submit the resulting semantic events with
`submitInput(expected, events)` using that same context.

Final validation and queue reservation share mutation, producer, and outbound
serialization. Intervening input, output, resize, or host-model assignment makes
an older context stale. Equal assignments and changes away from and back to the
same snapshot still invalidate it. Semantic input revisions conservatively
advance even when modes suppress its bytes; empty input has the event-specific
rules described by the API.

Rejection returns `STALE_CONTEXT`, `UNSUPPORTED_CONTEXT`, `CANCELLED`, or the
usual lifecycle result, with no edit prefix or capacity reservation. Valid edits
use the ordinary bounded compound queue. Callers still own editor semantics:
UTF-16 text offsets do not determine the shell's deletion units.

Host-owned projections should use `TerminalShellCommandLineState`. Its assignments
and session checks share a guard. The StateFlow-only host factory remains readable
but cannot provide conditional contexts. A custom producer may implement
`withCommandLine`; hold its guard for the entire callback, publish notifications
outside it, and obey the lock order above.

Publish null whenever pending host/shell input makes the model untrustworthy.
An idle writer does not acknowledge remote processing. `expected.cancel()` rejects
future checks, but cancellation racing admission can lose and cannot retract
accepted bytes. Response-dependent callers must stop waiting on session closure.

The default session-backed Swing suggestion path captures before provider work
and emits accepted feedback only after conditional admission of a valid grapheme
replacement. A host-managed popup must capture its own context. Calling the
default handler without its captured Swing request throws `IllegalStateException`;
custom handlers own their editing and feedback contract.

## Bounds and backpressure

| Resource | Bound | Accounting |
| --- | --- | --- |
| Ordinary byte ring | 16 KiB initially; 8 MiB maximum | Committed input/reply bytes waiting for the writer |
| Writer scratch | 16 KiB | Reused for synchronous connector writes |
| Bulk operations | 16 outstanding | Includes active encoding/writing |
| Bulk work | 16,777,216 units | Retained UTF-16 source units and deletion actions, with at least one unit per compound event |
| Compound event count | 256 per operation | Shares bulk operation/work budgets |
| Owned clipboard reply | One reservation, at most 8 MiB | Complete wire-byte size; separate from ring and bulk budgets |

Ring growth temporarily retains the old array while copying. Bulk source text
can occupy at most 32 MiB of character storage; encoded expansion streams through
bounded encoder scratch instead of a full output array. Event/operation bounds
also limit retained references. Standalone empty paste, empty replacement with
no deletions, and empty lists need no bulk reservation.

A slow connector backpressures the worker, while producers may admit more work
within remaining budgets. Non-suspending input APIs never wait for capacity.
Exhaustion fails closed rather than dropping accepted input or admitting a prefix.
A later transport failure or close can still interrupt active output. Payload
storage is reused, but bulk requests and coroutine/channel scheduling can allocate;
the complete input path is not allocation-free.

## Acceptance and lifecycle

`start` claims one attempt under the connector lifecycle monitor. State remains
`Created` and input admission returns `NOT_RUNNING` until `connector.start`
returns successfully. Synchronous startup output is parsed and replies are queued,
but the writer has not started. `Running` is then published before writer startup;
reentrant observers can admit input behind already queued replies. Failure or
closure during startup discards pending work and does not publish `Running`.

`ACCEPTED` and startup `SUBMITTED` mean admission, not write completion. A
connector `write` synchronously consumes or copies its supplied range. Custom
encoder factories create independent admission and bulk instances bound to
session-owned modes/output. Calls and policy updates serialize per instance;
the two instances may execute concurrently. Rejected admission-encoder policy
updates leave the session policy and reported Backarrow default unchanged.

Capacity exhaustion, transport failure, and unexpected writer cancellation close
the session, retain the first termination event, and discard pending output.
A failed write or bulk encoder may already have sent a prefix; it is never retried.
Cancellation after shutdown was claimed preserves the earlier event. Observe
`Closed.event.failure` for the initiating failure; cleanup failures are suppressed
on that cause. Local cleanup failures propagate from `close` after cleanup attempts.

Response-dependent hosts must not wait after rejected admission and must cancel
waiting on closure, including closure immediately after acceptance. Cancelling a
host wait does not retract queued output. There is no per-operation cancellation
or write-completion receipt. Admission racing shutdown may return `ACCEPTED`
although its work is discarded; calls observing shutdown return `CLOSED`.

Resize synchronously follows mutation serialization and may block in the
connector. It has no queue position and is not a flush barrier: accepted input
may be written after a later resize returns. Connector resize and disposal share
a separate lock, so an entered resize must finish before disposal proceeds.
Resize never takes the startup lifecycle lock while holding mutation.

Close claims termination, calls the connector before taking cleanup monitors,
drops pending output, cancels session jobs, finalizes parser EOF, and attempts
final publication. It does not join a writer blocked in a native call. Connectors
must tolerate concurrent close; coroutine cancellation alone cannot interrupt
arbitrary native work. Bulk output checks cancellation/closure before each chunk,
but a native call already entered can finish. Scratch is cleared when the worker
unwinds; active source ownership lasts until its callback returns.

`isClosed` reports the start of shutdown. `state` retains `Closed` after cleanup
and final publication have been attempted. A repeated or concurrent close can
return before that first shutdown completes. Session flows do not complete on
closure; their collectors retain responsibility for their own lifetime.

## Local buffer clear

`clearBuffer()` synchronously clears the active screen and history under mutation
serialization. It returns true before start or while running, and false after
closure wins admission. An admitted clear can finish during closure. Calls follow
lock admission order rather than call start time.

Cursor position, modes, pen, margins, tab stops, saved cursor, and inactive buffer
remain unchanged. Pending wrap is cancelled; blank cells use current erase
attributes. This local library operation sends no connector input and does not
reset the parser, so incomplete escape/UTF-8 sequences continue with later bytes.
It has no outbound queue position or flush effect. Ctrl+L remains application input.

`TerminalWriter.eraseBuffer` reuses built-in core storage and releases erased
clusters. New line identities and history generation invalidate old ranges and
conditional edit contexts. Swing invalidates affected selection/search when the
changed frame applies, preserving the search query and rejecting scans across
history replacement. Normal synchronized-output rules still govern publication.

The selected producer receives `bufferCleared` after mutation and before later
output, under mutation serialization and outside the frame lease. Its failure
propagates after the committed clear; render invalidation still runs. The OSC
producer drops erased primary anchors/editing extraction while preserving the
directory; alternate clearing leaves primary metadata intact.

Host-owned metadata remains authoritative. Hosts must clear or reanchor erased
terminal identities, optionally through a custom producer's ordered callback.
Editor text can remain valid because clearing does not change shell input.

## Closed-session presentation

Closure freezes terminal state after admitted mutation and parser EOF.
`resize` and `resizeViewport` reject closure; `tryResizeViewport` returns null
when closure wins admission. Invalid dimensions are validated first, and other
collaborator failures still throw. Admitted core reflow can finish during closure.

After completed `Closed` state, Swing projects retained frames without terminal
or connector resize. Width clips columns without reflow. Height shows a
bottom-anchored window across retained history and grid rows; offsets count all
rows above that window, including hidden final grid rows. A scrolled view keeps
its top row where new bounds permit.

Columns, line identities, attributes, palette, and final active buffer remain
frozen. Font changes preserve cell coordinates and selection. Mouse input routes
locally despite retained application-tracking modes. An alternate buffer retains
only its final grid; closure does not restore primary content. View changes use
synchronized retained reads and do not advance publication generation. Disposal
releases view work and binding without restarting or closing transport again.

## Selected shell integration

Session selects one `TerminalShellIntegrationFactory` before output starts.
Standard `create` dispatches parsed shell events to it. Low-level construction
leaves custom-parser wiring to the caller. No factory means no recorder/extractor.
Factory creation must not start jobs, I/O, or subscriptions.

Protocol producers use `TerminalShellIntegrationContext` for serialized terminal
reads and scoped published-cache access. Synchronous protocol callbacks execute
under mutation serialization. Host adapters normally use
`TerminalShellIntegrationFactory.host`; their supplied view is the only
terminal-facing timeline/directory authority and is not supplemented by OSC.

Hosts serialize semantic publications after matching terminal output, capture
stable line IDs before later bytes, and refresh live-grid editing anchors after
geometry changes. Thread-safe model storage alone does not order notifications
from concurrent producers. Use distinct projections per session.

Metadata `revision` is conflated invalidation state. Command-finished listeners
deliver completed records synchronously outside the model lock. Directory
listeners report future changed URIs without replay and suppress equal URIs;
read `currentWorkingDirectoryUri()` for the initial value. Return promptly and
close listener registrations with the consumer. Synchronous final updates can
arrive before immediate transport closure cancels session workers.

Startup requires explicit prompt readiness. It can arrive without output, but
submission waits for successful transport startup and the current parser batch
and replies. Admission rechecks readiness and primary-buffer state. Key presses,
nonempty paste, replacement, closure, or successful submission end the startup
attempt. Readiness observation stops when submission or cancellation finishes.
Closing the session stops its observations without clearing metadata or
cancelling host flows.

### Terminal command-line concurrency scope

`activeShellCommandLine` reads the selected authoritative snapshot; host snapshots
need no terminal mutation lock, while protocol producers may use serialized grid
reads. Null is authoritative. Host text bounds and remote acknowledgement remain
host responsibilities. Every versioned host assignment advances its guarded
revision, even if equal; intermediate observed revisions may conflate.

`activeShellCommandLineRevision` shares one session-worker subscription while
collected. The last collector leaving stops observation and resets the retained
revision; a later collector samples the current source. Reading `value` alone
does not start tracking. Closure makes active editing unavailable and cancels
session tracking, but consumer state-flow collection does not complete.
Conditional edit admission uses the guard described above, independently of
render scheduling or debouncing.

## Ordered custom OSC handling

Install `TerminalCustomOscHandler` through `parserFactory` and forward its
supplied sink and clipboard budget to `TerminalParsers.create`. Preceding output
is complete before the callback; later bytes are consumed only after it returns.
The callback may read a session frame and publish metadata at that line identity.
The host owns interpretation, permissions, retained payload copies, and handler
cleanup. This works with host-owned shell metadata or without a shell producer.

Built-in OSC commands retain their services and gates and never fall through,
including malformed or denied requests. Follow the handler KDoc for borrowed
payload limits and recovery. Callbacks must not wait for UI work, mutate/close
the session, or recursively feed output. Parser/session output reentry throws.

Parser or callback failures propagate unchanged from the input call, including
cancellation. The connector must stop delivery and report `onError`; session
then closes with that cause. PTY connectors provide this routing. Concurrent
close waits for admitted parsing; EOF discards incomplete OSC.

## Clipboard read lifetime and output commitment

The host validates selectors before admission. Session drains prior core replies
before admitting a clipboard query, including queries in the same parser chunk.
A session-bound suspending provider runs on the I/O dispatcher outside parser/input
monitors. The provider owns consent, selector resolution, host readiness, and
native access. Input and unrelated replies continue while it waits.

One request occupies the slot until its reply is retired and its provider job
actually completes. Cancelling non-cooperative native work does not free that
slot. Product adapters must also bound native work across sessions and retain
platform context; session cannot bound provider-internal allocations.

Admission starts an eight-second monotonic deadline. The timer uses the worker
dispatcher independently of blocking I/O; access and commitment also check elapsed
time. At expiry, an empty denial is admitted only if the writer is idle. Busy
output causes silence. Its commitment deadline is 8.1 seconds after admission;
delayed timers/dispatchers do not extend that 100 ms window. Expired queued data
and late timeout replies are discarded.

A single owned reply reservation is capped at 8 MiB of complete wire bytes,
separate from ring and bulk budgets. Validation combines that cap with the
host's `maxDecodedBytes` before allocation; the default raw limit is 1 MiB.
Only Base64 payload storage is retained; temporary UTF-8 storage is cleared.
The writer consumes the reply directly in bounded ranges rather than through
the ring. Cancellation releases queued payload; active writing retains ownership
until its exactly-once release callback.

Host policy publication and reply commitment share outbound serialization,
with mutation first when both are required. Read denial, Allow-to-Ask changes,
response-family denial, and lowered byte limits retire pending work permanently.
A later Allow cannot revive it. Before the first native write, commitment checks
permissions and elapsed time. Thereafter policy changes cannot retract framing:
the reply finishes unless transport failure or closure aborts it. Native writes
remain outside the monitor, and closure prevents later chunks.

Execution audits contain validated selectors and outcome, never clipboard text.
Callbacks may run on workers and must be thread-safe, prompt, and non-reentrant.
Provider exceptions become content-free failure outcomes without logging their
messages. An admission audit does not prove provider execution or written output.

## Tests

Use testkit connectors with controlled schedulers for ordering and lifetime tests.
An eager I/O dispatcher is suitable for byte fixtures, but scheduling tests need
one controlled scheduler or explicit entered/release/completed handshakes.
Submission return and fake-process exit do not prove queued output was written.
PTY reply tests must keep the child alive until expected output completes.
