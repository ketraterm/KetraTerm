# Module ketraterm-completion

## KetraTerm Completion (`:ketraterm-completion`)

The `ketraterm-completion` module defines the dependency-free command-line
completion engine foundation shared by standalone and IDE hosts.

It owns pure request/candidate/spec models, command-line tokenization, and
bounded in-process spec evaluation. It does not spawn shells, perform I/O,
depend on UI frameworks, or parse terminal output.

Completion sources such as curated command specs, Fig-style spec importers,
session MRU, profile/directory history indexes, path providers, and IDE context
providers should adapt into this module's stable model rather than leaking their
source-specific representation into terminal UI code.
