# Terminal Session Agent Guide

Read the root [AGENTS.md](../AGENTS.md) first. This module owns runtime
synchronization between transport, parser, core, host mapping, input encoding,
and render publication. See [Module.md](Module.md) for maintainer navigation and
the [concurrency contract](docs/session-concurrency-locks.md) before changing
ordering or lifecycle.

## Boundary

- Serialize parser/core mutation, resize, and live frame reads in the session.
  Transport owns inbound threads and must deliver borrowed bytes serially.
- Admit input and core replies through the same ordered writer. Use
  `ketraterm-input` for encoding; do not duplicate protocol encoding here.
- Keep connector writes and bulk encoding outside session mutation/admission
  monitors. If both monitors are needed, acquire mutation before outbound.
- Preserve exclusive runtime ownership of core/parser/connector collaborators
  and borrowed frame/cache lifetimes. No external mutable core or publisher
  access may bypass session serialization.
- Claim termination once. Release the connector before cleanup locks, finalize
  parser EOF, attempt final publication, then publish retained `Closed` state.
  Session owns its jobs, not dispatchers or consumer scopes.
- Own neutral shell contracts and subscriptions. Protocol interpretation and
  grid extraction belong in `ketraterm-shell-integration`; external producers
  remain host-owned. Do not introduce a dependency on that optional module.
- Keep consent and native clipboard access in the provider. Session owns
  bounded admission, deadlines, permission revalidation, and reply retirement.

## Testing and documentation

Use testkit connectors before PTY or UI fixtures. Cover exact bytes, admission
outcomes, first-event retention, and idempotent cleanup. Scheduling tests need a
controlled scheduler or entered/release/completed handshakes; do not use sleeps
or treat acceptance as write completion. Keep callback reentry and lock-order
coverage when changing producer or render contracts.

For behavior changes, format and run the narrowest relevant session tests, then
broaden validation when another module's contract changes. Keep README examples
consistent with public construction and ownership. Feature status and deferred
work belong only in the root feature/gap maps.
