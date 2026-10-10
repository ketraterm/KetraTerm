# Module ketraterm-transport-api

Transport-neutral byte-stream contracts. The only production dependency is the
Kotlin standard library. The package `io.github.ketraterm.transport` contains
`TerminalConnector`, `TerminalConnectorListener`, and byte-range validation.

## Architectural Role & Core Interfaces

The connector owns host I/O resources and any transport workers. The listener
consumes borrowed inbound byte ranges synchronously. Outbound byte ranges must
be consumed or copied before `write` returns.

Sessions own parser/core synchronization and outbound ordering. Transport APIs
must not acquire protocol, grid, input-encoding, rendering, or product behavior.
The optional foreground-process query supplies bounded, best-effort metadata;
it does not execute commands.

## Sub-Documentation

- [Consumer and implementer entry point](README.md).
- [Lifecycle and thread contract](docs/connector-lifecycle.md).
