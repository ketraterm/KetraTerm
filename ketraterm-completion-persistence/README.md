# Completion learning persistence

`ketraterm-completion-persistence` stores bounded completion-learning snapshots
for the standalone application and IntelliJ plugin. It is a shared product
implementation module, outside the supported Maven publication and public ABI
boundary. It is not included in the library entry points or BOM.

`TerminalCompletionLearningCoordinator` connects a
`TerminalCompletionLearningStore` to one fixed local file. Recording changes
memory synchronously; a worker in the host's coroutine scope handles file I/O.
The completion engine continues to read the same store without waiting for disk.

## Integration

The product chooses the data directory, persistence enablement, diagnostics, and
lifecycle scope. Use `currentFileName()` to resolve the format-specific filename.
Supply the same learning store to the completion engine and coordinator, and
record mutations through the coordinator so they are marked dirty for storage.

The coordinator runs in the supplied scope, using `Dispatchers.IO` by default.
Keep one coordinator per learning store and one writer per destination file.

## Lifecycle and failures

When persistence is first enabled, the worker loads the fixed file once and
merges it with learning already recorded in memory. Disabling and re-enabling
does not reload it. Do not separately preload the same aggregate snapshot;
merging it twice would double-count its evidence.

Changed snapshots are checkpointed after a 30-second interval. Later recording
events do not postpone an already scheduled checkpoint. Disabling persistence
keeps in-memory learning and cancels pending checkpoints; file I/O already in
progress can finish.

- `closeAndFlush()` stops accepting events, waits for the worker, and writes
  outstanding changes when persistence is enabled. Repeated calls are safe.
- `closeWithoutFlush()` stops accepting events and cancels and joins the worker.
  It drops pending work, but cannot undo file I/O already in progress.
- `resetLearning()` clears memory immediately and schedules an empty snapshot
  **even when persistence is disabled**. It supersedes an in-flight load and
  allows replacement of a previously rejected file.

A missing file is normal. An unreadable, malformed, oversized, or unsupported
file blocks ordinary writes for that coordinator's lifetime. The load-failure
callback runs once, with the original read exception when available; exceptions
from the callback are ignored. An explicit reset can clear this block.

A failed checkpoint is retried after a newer mutation or once during flushing
shutdown. An unrecovered final write failure is thrown by `closeAndFlush()`.
The load-failure callback does not report write failures.

Product shutdown should bound its final flush wait and then cancel the worker's
scope if that budget expires. Cancelling a close waiter does not cancel the
worker. Blocking filesystem calls may finish after coroutine cancellation;
shutdown must not rely on immediate interruption of those calls.

## Storage and privacy

Snapshots use a versioned format with opaque ranking rows and optional plaintext
replay rows. Unsupported schemas are rejected.

The store's `replayFilter` also applies when hydration merges imported replay
rows. At the file boundary, replay is checked again against the built-in
`TerminalCompletionReplayPolicy` and matching successful ranking evidence.
See the completion engine's [learning and privacy contract](../ketraterm-completion/docs/completion-architecture.md#learning-and-privacy)
for eligibility, context matching, and host-filter requirements.

Writes use a unique temporary file in the destination directory and replace the
target atomically when the filesystem supports it, with ordinary replacement as
the fallback. File and row bounds are enforced on both read and write. This is
bounded snapshot persistence, without a cross-process coordination or crash
durability guarantee. Products should assign one writer to a destination.

For implementation details, see [Module.md](Module.md).
