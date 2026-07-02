# Completion Module Architecture

`ketraterm-completion` is a dependency-free completion engine module. It owns
pure request/candidate contracts, static command specs, command-line parsing,
ranking, and bounded in-memory learning indexes. It does not own UI popup
behavior, persistence, shell processes, PTY/session lifecycle, or IntelliJ APIs.

## Public Surface

External modules should import only:

- `io.github.ketraterm.completion.api`
- `io.github.ketraterm.completion.model`

The `api` package exposes host-facing engines, source factories, request and
candidate contracts, and mutable learning-source interfaces. The `model` package
contains durable public data models that hosts may persist or construct, such as
command specs and compact completion-learning snapshots.

## Internal Implementation

These packages are implementation detail and must not be imported by app,
workspace, Swing UI, or future plugin code:

- `commandline`
- `engine`
- `internal`
- `ranking`
- `source`
- `spec`
- `stats`

Top-level declarations in those packages should be `internal` unless a product
decision explicitly promotes a type into `api` or `model`.

## Host Ownership

Hosts are responsible for privacy filtering and disk persistence. Completion
sources accept compact stats snapshots and live feedback events, but they never
read files, scan raw shell history, spawn shells, or talk to UI frameworks.

The standalone app and IntelliJ plugin should compose completion sources through
`TerminalCompletionSources` and `TerminalCompletionEngines`, then adapt returned
candidates to their own UI presentation.
