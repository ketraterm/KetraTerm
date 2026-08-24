# Module ketraterm-completion-persistence

## KetraTerm Completion Persistence (`:ketraterm-completion-persistence`)

The `ketraterm-completion-persistence` module provides optional local-file
storage for compact exact-command completion-learning snapshots. It sanitizes snapshots
through the shared completion persistence policy, enforces byte/line/row
bounds, uses a versioned codec, and replaces files atomically when supported.

`TerminalCompletionLearningCoordinator` is the runtime owner shared by product
hosts. It applies each bounded learning mutation synchronously and sends only a
conflated dirty generation to the latest-snapshot writer. One small bounded
worker handles hydration, enablement, and flush controls in the host-owned
lifecycle scope. The writer materializes immutable state only at the I/O
boundary. Ranking observes in-memory learning without waiting for disk, bursts
do not trigger a snapshot or write per event, and hosts can await a flush
barrier at shutdown. The internal repository is a passive load/write boundary
and does not own a mutex, queue, cache, or scope.

Each host supplies one fixed product path at construction and may only enable or
disable persistence for that path. The module deliberately has no runtime path
switching or cross-file import contract. It does not rank suggestions, parse
command lines, inspect shell history, or depend on workspace and UI modules.

The coordinator hydrates that fixed file and merges it with live rows in the
supplied store. Callers must not separately preload the same aggregate file.
