# Terminal Transport API Agent Guide

`terminal-transport-api` owns the transport-neutral connector contract between
terminal sessions and host byte streams.

## Boundary

Transport API owns:

- connector lifecycle callbacks.
- host-bound byte writes.
- terminal resize notifications to the transport.

It must not depend on parser, core, integration, input, PTY, SSH, UI, or test
modules. Connectors own their transport threads; sessions own parser/core
synchronization and terminal-to-host ordering.

## Testing

Keep contract tests in connector implementations or `terminal-testkit`. This
module should stay dependency-light and vocabulary-only.
