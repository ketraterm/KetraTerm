# Module ketraterm-completion-persistence

Shared product implementation for bounded local-file completion learning. This
module depends on `ketraterm-completion` and coroutines; it has no dependency on
session, workspace, Swing, PTY, or IntelliJ. It is outside the supported library
publication and ABI boundary. [README.md](README.md) covers product integration,
lifecycle, failures, and privacy.

## Components

| Component | Responsibility |
| --- | --- |
| `TerminalCompletionLearningCoordinator` | Synchronous learning mutations and one lifecycle-bound persistence worker. |
| `CompletionLearningFileStore` | Internal fixed-path, bounded file reads and replacement, with replay-policy filtering. |
| `CompletionLearningSnapshotCodec` | Internal strict versioned line encoding and decoding. |

The coordinator serializes recording, reset, and lifecycle transitions around
one state lock. Its conflated wakeup carries no snapshots or per-event controls.
The worker loads once, observes current enablement, and snapshots the latest
state for a checkpoint. Revision tracking distinguishes dirty, attempted, and
successfully persisted state, so sustained recording does not postpone a
checkpoint and failed writes do not spin.

Reset has a separate revision: it supersedes hydration and writes an empty
snapshot promptly, including when ordinary persistence is disabled. A flushing
close awaits the required final write; a non-flushing close cancels the worker.
The caller owns the scope and any bounded shutdown wait.

## File boundary

Version 3 uses a header followed by ranking rows and then replay rows. Text
fields use URL-safe Base64 with strict UTF-8 decoding. Base64 is an encoding,
not encryption. Codec details are internal, not a snapshot interchange API.

Bounds are defined by the codec and file store.

Loading rejects unsupported schemas, malformed rows, invalid row ordering, and
exceeded bounds. Saving retains rows within the storage bounds. Both directions
recheck replay policy and successful evidence; hydration additionally passes
through the learning store's host filter. The temporary file is created beside
the target and cleaned after the replacement attempt.
