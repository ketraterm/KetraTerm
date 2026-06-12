# Terminal Testkit Agent Guide

`terminal-testkit` owns reusable fakes and fixtures for terminal modules.

## Boundary

Testkit may depend on public APIs such as `terminal-transport-api`, but it must
not reach into module internals. Fakes should model real contracts, especially
synchronous byte consumption and lifecycle callbacks.

## Testing

Prefer explicit captured bytes and events. Do not make local `close()` pretend a
remote process exited; tests should call remote simulation helpers directly.
