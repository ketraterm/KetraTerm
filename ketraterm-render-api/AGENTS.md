# Terminal Render API Agent Guide

Read the [root guide](../AGENTS.md) first. This module owns dependency-free
renderer-facing frame, cursor, cluster, cell-flag, attribute, and palette contracts.

## Boundaries

- Do not depend on core, parser, host, session, transport, PTY, or UI modules.
- Expose primitive public encodings rather than core storage or mutable state.
- Keep fonts, glyph runs, paint caches, timers, and platform objects in renderers.
- Reader implementations own synchronization. Consumers must copy borrowed frame
  data before returning from callbacks.

## Compatibility and validation

Treat bit layouts, defaults, valid flag combinations, callback lifetimes, and
generation meanings as API contracts. Use conservative default methods when an
optional capability is unavailable. A nested read rejected by an implementation
must not disturb the enclosing borrowed frame.

Test packing/decoding boundaries, invalid inputs, defensive palette copies, and
reader fallback behavior. For behavior changes, run formatting and
`./gradlew :ketraterm-render-api:test`; verify affected core, render-cache, and
session consumers as needed.

[README](README.md) owns the consumer entry point. The
[frame lifecycle](docs/render-frame-lifecycle.md) and
[attribute packing](docs/attribute-packing.md) guides own detailed contracts.
