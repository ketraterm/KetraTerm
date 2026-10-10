# Module ketraterm-testkit

Repository-only helpers for deterministic tests of transport and terminal
semantics. This module is excluded from the supported Maven, ABI, and aggregated
public API documentation boundary.

## Upstream Dependencies

Production composition uses public contracts from core, host, parser, and
transport API. Jackson encodes and decodes the independent oracle's JSON process
protocol. These are implementation dependencies. Consumers are test source sets,
not production modules.

## Public API Surface

| Helper | Responsibility |
| --- | --- |
| `MockConnector` | Synchronous host-byte delivery, copied outbound capture, and explicit lifecycle simulation. |
| `TerminalReplayEvent` / `TerminalReplayTranscript` | Detached input chunks and ordered input, resize, and end-of-input events. |
| `TerminalConformanceHarness` | Stateful replay through the production parser-to-core pipeline and response collection. |
| `TerminalConformanceSnapshot` | Detached observable grid, cursor, modes, metadata, and response values. |
| `TerminalReplayChunkings` | Deterministic partitions for bounded chunk-invariance fixtures. |
| `TerminalConformanceDiffer` | Bounded structural snapshot diagnostics. |
| `TerminalDifferentialOracle` / `TerminalDifferentialComparator` | Independent emulator execution and comparison of explicitly shared observations. |

## Architectural Role

The conformance harness owns its parser, adapter, core buffer, and copied snapshot
values. It is stateful and must be recreated for independent runs. The connector
fake forwards a borrowed host slice synchronously; it retains copies of writes.
Neither helper adds a synchronization boundary.

`TerminalProcessOracle` launches one bounded process per replay.
`TerminalPersistentProcessOracle` keeps a JSON-lines worker for repeated requests;
its owner must close it. The repository's xterm adapter creates fresh emulator
state per request. Oracle output describes a comparison scope, not an alternative
source of truth for KetraTerm's semantics.

<a id="how-to-use-in-tests"></a>

## Source sets

See the [README](README.md) for dependency setup and complete usage examples.
Reusable helpers belong in `src/main`; test corpora, generated campaigns, and
publication verification belong in `src/test`. Isolated external-consumer sources
and retained binaries live in [src/consumerTest](src/consumerTest/README.md).

## Running Testkit Tests

The [README task reference](README.md#running-testkit-tests) separates ordinary
tests, optional campaigns, and publication checks. The
[consumer guide](src/consumerTest/README.md) describes the staged Maven and
compiled-client workflows.
