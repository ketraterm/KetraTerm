# Terminal Testkit Agent Guide

`ketraterm-testkit` owns repository-only fakes, replay fixtures, independent
conformance checks, and external-consumer verification. Read the
[root guide](../AGENTS.md) first.

## Boundary

- Compose public production APIs; never reach into another module's internals.
- Keep transport simulation byte-oriented. Consume borrowed callback slices
  synchronously or copy them before retention.
- Preserve exact parser chunk boundaries, resize ordering, and end-of-input
  placement in replay artifacts.
- Keep independent grid models and process oracles in test support; production
  behavior remains in the owning modules.
- Testkit is outside the supported publication boundary. External consumer
  fixtures must resolve staged artifacts without testkit or project substitution.

## Testing

Assert captured bytes, lifecycle events, and observable snapshots. Local `close`
records a local request; simulate remote closure or failure explicitly. Serialize
access to helpers that do not provide concurrency control.

Generated campaigns need deterministic seeds/shards, bounded diagnostics, and
reduced replayable failures. An intentional oracle disagreement requires a
rationale and exact mismatch paths; do not loosen assertions around a regression.

Retained clients must run without recompilation or current fixture classes.
Baseline replacement is an explicit compatibility decision, with source,
provenance, artifact, and ABI review; it is never routine failure repair.

## Validation

Run `./gradlew :ketraterm-testkit:test` for helper and corpus changes. Add the
relevant opt-in campaign for model/oracle changes. Run
`:ketraterm-testkit:publishedConsumerTest` for consumer or exported-contract
changes and `:ketraterm-testkit:publicationVerificationTest` for packaging changes.
The [README](README.md#running-testkit-tests) and
[consumer guide](src/consumerTest/README.md) own the detailed task instructions.
