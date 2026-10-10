# Shell Integration Agent Guide

This module provides KetraTerm's optional OSC shell integration producer.
Session owns synchronization, connector coordination, input ordering, and the neutral shell
model contract. This module interprets accepted OSC 7/133 metadata, anchors
command records to terminal line identities, and reconstructs bounded command
text from render data.

## Invariants

- Access terminal frames only through `TerminalShellIntegrationContext`. Frames
  and published caches are borrowed within callbacks; do not retain or mutate them.
- Keep protocol callbacks synchronous and serialized by session. Do not block on
  UI work, invoke mutating session APIs, or close the session from them.
- Use stable line identities for marker anchors; viewport rows are transient.
  Invalidate primary anchors and editing context on a local primary-buffer clear.
- Preserve bounded extraction and conservative failure: unavailable or ambiguous
  text stays unknown. Keep text extraction and fingerprint semantics consistent,
  including hard breaks, soft wraps, padding, and cluster data.
- Keep revision tracking cold with reusable primitive scratch storage. Construction
  must not subscribe, perform I/O, or start background jobs.
- Report prompt readiness independently of the immutable launch expectation.
  Session decides when and how to submit startup input.
- Own no coroutine scope, transport, input encoder, UI policy, shell-hook
  installation, or PTY process. Keep host-provided integration independent of
  this module and its protocol interpretation.

## Validation

Use real byte-stream session tests for marker ordering, command extraction, and
startup readiness. Cover chunk boundaries, missing or orphan markers, bounds,
wraps, wide cells and grapheme clusters, scrollback, clear, and resize as relevant.
Use deterministic coroutine dispatchers for subscription and lifecycle tests;
assert shared tracking stops after the last observer and at session closure.

For behavior changes, run `./gradlew spotlessApply`, then
`./gradlew :ketraterm-shell-integration:test`.
