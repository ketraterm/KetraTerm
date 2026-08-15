# Module ketraterm-completion-persistence

## KetraTerm Completion Persistence (`:ketraterm-completion-persistence`)

The `ketraterm-completion-persistence` module provides the optional local-file store for compact completion-learning
snapshots. It sanitizes snapshots through the shared completion privacy policy, enforces byte/line/row bounds, uses a
versioned codec, replaces files atomically when supported, and serializes operations through one suspending repository.

`TerminalCompletionLearningCoordinator` is the optional caller-scope adapter shared by product hosts. It launches the
repository's suspending initialization, mutation, and settings operations in a lifecycle scope supplied and owned by
the host; it creates no executor or independent scope.

Hosts own the destination directory, persistence enablement, and store lifecycle. The module does not rank suggestions,
parse command lines, inspect shell history, or depend on workspace and UI modules.
