# Terminal UI Swing Host Agent Guide

`ketraterm-ui-swing-host` owns optional Swing host chrome, action vocabulary,
completion adapters, and clipboard-consent helpers for applications embedding
`SwingTerminal`. Read the root [AGENTS.md](../AGENTS.md) first.

## Responsibilities

- Keep helpers explicitly installed and owned by the embedding host.
- Adapt completion requests, candidates, and feedback through public contracts;
  retain source identity, request context, replacement ranges, and opaque tokens.
- Let `SwingTerminal` own search scanning and highlights. This module owns
  visible search controls, overlay layout, and prepared chrome colors.
- Keep localized strings behind `SwingHostMessages`; resolve static component
  text at construction and dynamic text on UI changes, outside painting.

## Boundary

Do not parse terminal output, mutate core internals, start transports, depend on
product modules or IntelliJ APIs, or choose completion sources, ranking,
persistence, or product keymaps. Use `ketraterm-ui-swing` and completion public
APIs. Directory I/O belongs in host completion sources, outside the engine and
Swing request adaptation.

## Threading and lifecycle

- Create and manipulate Swing components on the EDT. Preserve explicitly
  documented off-EDT enqueue behavior for search operations.
- Completion `open` captures host metadata on the EDT before collection. Keep
  it cheap and preserve caller-owned cancellation and dispatcher selection.
  Feedback sinks run synchronously; host storage owns its synchronization.
- Keep clipboard consent pane-owned and reader admission window-owned. Native
  access runs off the EDT with bounded global admission. Session owns request
  expiry, permission revocation, and replies.
- Preserve cancellation cleanup, stale-dialog protection, and the native read
  slot until a blocking provider actually returns. Never include clipboard
  payloads in consent messages.
- Closing host chrome must not close a terminal, session, or completion engine.

## Validation

Run `./gradlew spotlessApply`, then `./gradlew :ketraterm-ui-swing-host:test` for
behavior changes. Use deterministic EDT/coroutine tests for lifecycle, concurrent
requests, and cancellation; coordinate blocked native calls with explicit gates.
Check localization by decision identity and argument meaning, rather than
hard-coded translated wording.

The [README](README.md) owns consumer examples; [Module.md](Module.md) summarizes
package ownership. Keep feature scope and TODO inventories in the canonical root
feature and gap maps.
