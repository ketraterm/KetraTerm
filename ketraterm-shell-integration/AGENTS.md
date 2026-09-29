# Shell Integration Agent Guide

This module provides KetraTerm's optional OSC shell integration producer.
Session owns synchronization, connector coordination, input ordering, and the neutral shell
model contract. This module interprets accepted OSC 7/133 metadata, anchors
command records to terminal line identities, and reconstructs bounded command
text from render data.

- Access terminal frames only through `TerminalShellIntegrationContext`.
- Keep revision tracking cold and use reusable primitive fingerprint storage.
- Do not own a coroutine scope, transport, input encoder, UI policy, or PTY process.
- Report prompt readiness; session decides when and how to submit startup input.
- Keep host-provided integration independent of this module.

Use byte-stream session tests for marker ordering, command extraction, and
startup readiness. Use deterministic coroutine dispatchers for observation and
lifecycle tests.
