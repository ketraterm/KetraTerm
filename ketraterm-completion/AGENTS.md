# Terminal Completion Agent Guide

`ketraterm-completion` owns dependency-free command-line completion models,
tokenization, ranking, and static/spec-backed candidate evaluation.

## Boundary

This module may:

- define completion request, candidate, source, and spec vocabulary.
- tokenize command-line text for suggestion evaluation.
- evaluate in-process command specs and bounded indexes.
- expose deterministic pure APIs for Swing, standalone, and IntelliJ hosts.

This module must not:

- depend on Swing, IntelliJ Platform, PTY, session, parser, core, workspace, or app modules.
- spawn shells or run subprocesses.
- parse terminal output protocols.
- perform disk or network I/O.
- own UI popup behavior or host settings.

## Testing

Prefer pure unit tests for tokenization, replacement ranges, scoring, caps,
malformed quotes, and command/subcommand/option resolution. Keep expectations
explicit and deterministic.
