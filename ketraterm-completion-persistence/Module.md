# Module ketraterm-completion-persistence

## KetraTerm Completion Persistence (`:ketraterm-completion-persistence`)

The `ketraterm-completion-persistence` module provides the optional local-file store for compact completion-learning
snapshots. It sanitizes snapshots through the shared completion privacy policy, enforces byte/line/row bounds, uses a
versioned codec, replaces files atomically when supported, and coalesces writes on one daemon worker.

Hosts own the destination directory, persistence enablement, and store lifecycle. The module does not rank suggestions,
parse command lines, inspect shell history, or depend on workspace and UI modules.
