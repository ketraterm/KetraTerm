# Module ketraterm-completion-host

## KetraTerm Completion Host Support (`:ketraterm-completion-host`)

This module provides bounded, asynchronous completion snapshot infrastructure shared by standalone and IDE hosts. It
owns generation-safe ready snapshots, local path interpretation, and bounded local directory scanning while keeping all
command parsing and ranking in `ketraterm-completion`.

