# Terminal Transport API Agent Guide

`ketraterm-transport-api` owns the transport-neutral connector contract between
terminal sessions and host byte streams.

## Boundary

This module owns:

- connector lifecycle callbacks.
- host-bound byte writes.
- terminal resize notifications to the transport.
- optional foreground-process metadata queries.

It must not depend on parser, core, host, input, PTY, SSH, UI, or test
modules. Connectors own their transport threads; sessions own parser/core
synchronization and terminal-to-host ordering.

## Contract invariants

- Accept one start attempt; reject restart and startup after local closure.
- Preserve stream order and serialize byte callbacks. Borrowed inbound ranges
  expire when the callback returns; outbound ranges must be consumed or copied
  before `write` returns.
- Deliver final byte callbacks before remote closure. Treat byte-consumer failure
  as terminal, preserve its cause, and never retry the failed range.
- Keep local close idempotent and safe from lifecycle callbacks. Do not make
  cleanup depend on the callback returning first.
- Keep metadata queries bounded, safe during concurrent close, and free of
  command execution. Their callers must stay off UI and byte-processing threads.

The API KDoc is the contract authority. The
[lifecycle guide](docs/connector-lifecycle.md) explains ownership and implementer
pitfalls; it must not invent guarantees absent from the interface.

## Testing

Keep contract tests in connector implementations or `ketraterm-testkit`. This
module should stay dependency-light and vocabulary-only.

For contract changes, update affected implementation tests, including restart,
close-before-start, final-byte ordering, consumer failure, and reentrant cleanup.
Run the narrowest affected module tests after `./gradlew spotlessApply` from the
repository root.
