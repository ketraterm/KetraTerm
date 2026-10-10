# Coroutine render publication

`TerminalSession` publishes copied render data for consumers that do not need
synchronous access to the live grid. Read a publication with `readPublishedFrame`
and observe `renderGeneration` for invalidation. Both are described in the
[session README](../README.md#rendering-and-local-operations).

## Invalidation and conflation

One session worker uses a conflated channel to process render invalidations.
The latest scrollback offset and requested row count share an atomic packed
request; a generation counter identifies newer invalidations. No request object
or queue entry is created for each viewport change.

The worker extracts the requested viewport under mutation serialization and
copies it into `TerminalRenderPublisher`. It advances `renderGeneration` only
after successful promotion. Generations are opaque invalidation tokens:
intermediate generations may be skipped, and the flow is not a log of output.

The first publication after an idle period is immediate. During continuous host
output, the worker waits up to a 16 ms display interval before sampling the newest
request again, including a trailing frame for the final invalidation. Explicit
viewport, resize, palette, and cursor invalidations interrupt that wait. These
are scheduling rules, not a frame-latency guarantee; copying, dispatcher load,
and reader leases can delay publication.

Incoming output preserves the current requested scrollback offset. A new
`requestRender` replaces it. One session has one published viewport; unrelated
scroll positions must not compete for it. Direct `readRenderFrame` calls can
read a requested range without changing the publication request.

## Failure and synchronized output

A failed cache copy leaves the previous successful publication readable.
The worker returns rather than retrying in a loop; a later invalidation can
trigger another attempt. Cancellation propagates.

Synchronized-output mode defers normal publication. A worker-dispatcher timer
turns that mode off after the 100 ms safety timeout and invalidates rendering.
Normal mode disable cancels the timer. Timer dispatch can be delayed by scheduler
load, so this is not a real-time deadline.

Closure cancels render work, finalizes parser EOF under mutation serialization,
and attempts synchronous publication of the final requested viewport even when
synchronized output was active. `Closed` is retained after those attempts.
Consumers may continue reading a successful final cache but cannot request new
session publications. See [closed-session presentation](session-concurrency-locks.md#closed-session-presentation).

## Ownership and allocation boundary

The worker dispatcher belongs to the caller. Closing the session cancels its
jobs without disposing that dispatcher or consumer scopes. `renderGeneration`
is a retained state flow and does not complete on closure.

Published caches and arrays are borrowed only inside `readPublishedFrame`.
A reader lease prevents recycling of its cache while another frame is promoted.
Copy promptly and do not mutate or retain borrowed storage, close the session,
or reenter session mutation/publication from the callback. Long-lived leases
can delay further copying and closure.

Invalidation avoids per-request objects. Parser/core mutation and cell copying
do not dispatch a coroutine per cell. Coroutine/channel scheduling, cache growth,
and consumer work have separate allocation costs; this is not an allocation-free
claim for an entire frame or session. Input output uses the independent writer,
not the render worker.
