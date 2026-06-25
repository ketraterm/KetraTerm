# Terminal Session Agent Guide

`jvterm-session` owns the runtime synchronization point that connects a
transport connector, parser, core buffer, response queue, and input encoder.

## Boundary

Session owns:

- serializing parser/core mutations.
- draining core response bytes after parser input.
- serializing keyboard, paste, focus, mouse, and core response writes through one
  outbound lock.
- idempotent local close, remote close, and parser cleanup.

It must not own transport threads, parse bytes itself, mutate core internals, or
encode input outside `jvterm-input`.

## Testing

Use `jvterm-testkit` connectors for lifecycle and ordering tests before PTY or
UI tests. Tests should assert exact bytes and idempotent cleanup behavior.
