# Module ketraterm-completion-persistence

Published optional storage for bounded completion-learning snapshots. Dependencies
are `ketraterm-completion` and coroutines; products own paths, settings, diagnostics,
and lifecycle scopes. The [README](README.md) covers integration and lifecycle.

## Components

| Component | Responsibility |
| --- | --- |
| `TerminalCompletionLearningCoordinator` | Synchronous store mutations and one lifecycle-bound worker for loading, checkpoints, reset, and shutdown. |
| `CompletionLearningFileStore` | Bounded file access, replay-policy checks, and same-directory temporary-file replacement. |
| `CompletionLearningSnapshotCodec` | Strict versioned encoding, decoding, and row bounds. |

The coordinator protects revisions with a state lock and wakes the worker through
a conflated signal. File I/O runs outside the lock. Dirty, attempted, persisted,
and reset revisions keep writes ordered without queueing a snapshot per event.
The file store cleans temporary files after replacement attempts and uses atomic
replacement where supported, with ordinary replacement as a fallback.

The [storage reference](../docs/reference/storage.md#file-format) owns the format
and privacy description. The engine's [learning contract](../ketraterm-completion/docs/completion-architecture.md#learning-and-privacy)
owns replay eligibility and host filtering; the file boundary rechecks replay
policy and successful ranking evidence before accepting stored command text.
