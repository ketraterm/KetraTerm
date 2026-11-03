# Completion learning persistence

**Experimental.** See the [completion stability contract](../docs/features/completion.md#stability-experimental).

This internal module saves completion learning for the standalone app and
IntelliJ plugin. It is outside the supported Maven publication, entry points,
and BOM. Library embedders choose their own storage for learning snapshots.

## Integration

`TerminalCompletionLearningCoordinator` connects one
`TerminalCompletionLearningStore` to a fixed local file. Pass that same store to
the completion engine and record commands and feedback through the coordinator.
Memory updates are immediate; a worker in the supplied coroutine scope performs
file I/O on `Dispatchers.IO` by default.

The host owns the scope, path, enablement, and diagnostics. Use
`currentFileName()` for the format-specific filename, and keep one coordinator
per store and one writer per file. See [storage locations, format, and privacy](../docs/reference/storage.md)
for the product storage contract.

## Lifecycle and failures

The first enable loads saved learning once and merges it with live activity.
Re-enabling does not reload it. Do not preload that same snapshot separately:
merging an aggregate twice would double-count its evidence.

Changes are checkpointed after 30 seconds; later activity does not postpone a
scheduled checkpoint. Disabling persistence keeps memory and cancels pending
checkpoints. An in-flight file operation can finish.

| Operation | Contract |
| --- | --- |
| `resetLearning()` | Clears memory immediately and replaces saved data with an empty snapshot, including while persistence is disabled. Await its `Deferred<Unit>` before reporting success; a write error fails it. Reset supersedes in-flight hydration. Overlapping resets share completion of the latest reset. |
| `closeAndFlush()` | Rejects new events and waits for required writes when persistence is enabled. Repeated calls are safe; an unrecovered final write failure is thrown. |
| `closeWithoutFlush()` | Rejects new events, cancels and joins the worker, and drops pending work. It cannot undo file I/O already in progress. |

A missing file is normal. Unreadable, malformed, oversized, or unsupported data
blocks ordinary writes for that coordinator's lifetime. The load-failure callback
runs once; errors from the callback are ignored. An explicit reset permits
replacement of the rejected file.

A failed checkpoint is retried after a newer mutation or once during flushing
shutdown. Write failures are not sent to the load-failure callback.

Cancelling a reset or close waiter leaves storage work running. The host must
bound its shutdown wait and cancel the worker's scope if that budget expires.
Blocking filesystem calls may still finish after coroutine cancellation.

For components and write ordering, see [Module.md](Module.md). Replay eligibility
and host filtering are defined by the [learning contract](../ketraterm-completion/docs/completion-architecture.md#learning-and-privacy).
