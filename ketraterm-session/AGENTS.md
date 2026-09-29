# Terminal Session Agent Guide

`ketraterm-session` owns the runtime synchronization point that connects a
transport connector, parser, core buffer, response queue, and input encoder.

## Boundary

Session owns:

- serializing parser/core mutations.
- draining core response bytes after parser input.
- serializing keyboard, paste, focus, mouse, and core response writes through one
  outbound lock.
- idempotent local close, remote close, and parser cleanup.
- neutral shell-model contracts, selected-producer observation, and ordered
  startup submission from that producer's readiness.

It must not own transport threads, parse bytes itself, mutate core internals, or
encode input outside `ketraterm-input`. Optional shell protocol interpretation
and grid-based command extraction belong in `ketraterm-shell-integration`;
session must remain usable without that dependency. Hosts own external model
producers, while session owns only its subscriptions.

## Testing

Use `ketraterm-testkit` connectors for lifecycle and ordering tests before PTY or
UI tests. Tests should assert exact bytes and idempotent cleanup behavior.
