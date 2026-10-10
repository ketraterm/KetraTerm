# Module ketraterm-session

The runtime boundary joining transport, parser, core, host mapping, input
encoding, and render publication. Public contracts live in
`io.github.ketraterm.session`; the [README](README.md) covers consumer assembly.

## Ownership

`TerminalSession` serializes parser/core mutation, owns connector lifecycle,
drains terminal responses, and observes one selected shell producer. The
connector owns inbound threads and ordered byte delivery. Session workers use
caller-supplied, non-owned dispatchers.

`OutboundWriter` manages transactional byte admission, bounded bulk/reply
reservations, and ordered connector writes. Ordinary events encode at admission;
paste, replacement, and compound events capture modes/policy and encode on the
writer. Connector writes run outside mutation and admission monitors.

Render invalidations coalesce in a session worker. `TerminalRenderPublisher`
owns cache storage and reader leases; consumers access it through the session.
One publication viewport is retained per session.

`TerminalShellIntegration` and its factory define the neutral producer boundary.
`TerminalShellIntegrationState` stores bounded metadata;
`TerminalShellCommandLineState` serializes host editing revisions with conditional
admission. Optional OSC interpretation and grid extraction belong to
`ketraterm-shell-integration`, which this module does not depend on.

`ClipboardReadHandler` owns one bounded OSC 52 request and reply lifetime.
Platform consent and native clipboard access stay in the supplied provider.
`ForegroundProcessTracker` shares best-effort connector queries only while
collected; it is independent of parsing and painting.

## Dependencies and contracts

The Gradle API surface exposes core, parser, host, input, render API/cache,
transport API, and coroutines. Shared protocol vocabulary is an implementation
dependency. PTY, Swing, completion, workspace, and product adapters compose this
module from outside it.

- [Concurrency and ownership](docs/session-concurrency-locks.md): admission,
  lock order, closure, borrowed reads, and shell/clipboard callbacks.
- [Render publication](docs/asynchronous-render-coalescing.md): conflation,
  interaction invalidation, and synchronized output.
- [Historical writer measurements](docs/outbound-writer-benchmarks.md): checkpoint
  data and reproduction scope, not current performance guarantees.
- [Core contract](../ketraterm-core/docs/terminal-core-contract.md) and
  [transport contract](../ketraterm-transport-api/README.md): collaborator behavior.
