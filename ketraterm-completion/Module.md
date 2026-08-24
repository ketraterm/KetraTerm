# Module ketraterm-completion

## KetraTerm Completion (`:ketraterm-completion`)

The `ketraterm-completion` module defines the dependency-free command-line
completion engine foundation shared by standalone and IDE hosts.

It owns pure request/candidate/spec models, command-line tokenization, bounded
in-process spec evaluation, ranking, and bounded exact-command learning. The
built-in command catalog is shared and immutable rather than reconstructed for
each consumer.

The module does not spawn shells, perform I/O, depend on UI frameworks, or parse
terminal output.

Its public persistence policy evaluates and sanitizes host-owned learning
snapshots without performing I/O. Optional local-file storage and write
scheduling are supplied by the separate
`ketraterm-completion-persistence` module, while asynchronous provider
infrastructure belongs to `ketraterm-completion-host`.

Completion sources such as curated command specs, Fig-style spec importers,
session MRU, profile/directory history indexes, path providers, and IDE context
providers should adapt into this module's stable model rather than leaking their
source-specific representation into terminal UI code.
