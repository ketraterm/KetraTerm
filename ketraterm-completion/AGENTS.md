# Terminal Completion Agent Guide

`ketraterm-completion` owns suspending command-line completion models,
tokenization, structured source evaluation, ranking, and bounded learning.

## Public API Surface

External modules must import completion through:

- `io.github.ketraterm.completion.api`
- `io.github.ketraterm.completion.model`

Implementation packages are not product API. Keep top-level declarations in
`commandline`, `engine`, `history`, `internal`, `ranking`, `source`, `spec`, and `stats`
`internal` unless a deliberate design change promotes the type into `api` or
`model`. Update `docs/completion-architecture.md` and structural architecture
tests when changing this boundary. Do not maintain a second declaration-name
allowlist that mirrors the source API.

## Boundary

This module may:

- define completion request, candidate, source, and spec vocabulary.
- tokenize command-line text for suggestion evaluation.
- evaluate in-process command specs and bounded indexes.
- evaluate independent suspending sources in one structured child scope.
- expose deterministic APIs for Swing, standalone, and IntelliJ hosts.

This module must not:

- depend on Swing, IntelliJ Platform, PTY, session, parser, core, workspace, or app modules.
- spawn shells or run subprocesses.
- parse terminal output protocols.
- perform disk or network I/O.
- create unowned scopes or launch work inside individual sources.
- own UI popup behavior or host settings.

## Testing

Prefer pure unit tests for tokenization, replacement ranges, scoring, caps,
malformed quotes, and command/subcommand/option resolution. Keep expectations
explicit and deterministic.
