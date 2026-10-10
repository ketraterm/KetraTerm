# Terminal Input Agent Guide

Read the [root guide](../AGENTS.md) and the
[input contract](docs/terminal-input-contract.md) before changing input behavior.
This module owns normalized host-bound event vocabulary, encoding policy, and
byte generation. Session owns outbound ordering and transport lifetime.

## Boundaries

- Use `TerminalHostOutput` from `ketraterm-protocol` for host-bound bytes.
- Read only `TerminalInputState` for mode-dependent decisions. Capture one
  coherent word per event and interpret it through core helpers.
- Keep output parsing, grid mutation, pointer-coordinate conversion, toolkit
  events, clipboard access, and transport I/O outside this module.
- Do not depend on parser, host, session, or rendering modules. Protocol and
  core are the production dependencies.

## Implementation

- Keep specialized encoders stateless with respect to terminal modes; pass the
  captured mode word into each call. A text replacement uses the same snapshot
  for deletion and insertion.
- Serialize calls and policy updates on each encoder instance. Custom encoder
  factories must return independent instances for the session's admission and
  bulk paths; construction must not read modes or emit bytes.
- Reuse byte scratch for generated CSI/SS3, UTF-8 scalar, and mouse reports.
  Avoid per-event arrays, formatted strings, regex, and slicing.
- Sinks consume or copy borrowed ranges before returning. Do not retain sink
  buffers or depend on asynchronous consumption of encoder scratch.
- Validate events at construction and define suppression or fallback through
  explicit policy. Never infer lifecycle or layout metadata that a host cannot
  provide.
- Preserve bracketed-paste protection independently of optional control
  filtering. Reset buffered output on both success and failure.
- Clipboard reply preparation validates bounds before allocation. Keep its
  owned storage and cleanup separate from paste transformation and authorization.

## Verification

Tests assert exact bytes and visible suppression, not internal call structure.
Cover relevant validation, modifiers, mode changes, coordinate limits, paste
framing, and failure recovery. Use real core mode state for integration coverage.
Keep expected sequences readable in each test.

Run formatting and `./gradlew :ketraterm-input:test` for behavior changes. Capability status
and deferred work belong in the canonical feature and gap maps.
