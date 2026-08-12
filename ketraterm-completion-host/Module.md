# Module ketraterm-completion-host

## KetraTerm Completion Host Support (`:ketraterm-completion-host`)

This module provides structured asynchronous completion snapshots shared by standalone and IDE hosts. It owns one
optional active load job per provider, a shared suspending concurrency limit, immutable ready snapshots, local path
interpretation, and bounded interruptible directory scanning while keeping all command parsing and ranking in
`ketraterm-completion`.

