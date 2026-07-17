# Terminal UI Swing Host Agent Guide

`ketraterm-ui-swing-host` owns optional Swing host chrome, default host action bindings, and host-neutral
completion-to-Swing adapters for applications embedding `SwingTerminal`.

This module may provide reusable Swing panels, controllers, and shortcut
vocabulary for hosts. It must not become part of the core reusable terminal
component itself.

## Responsibilities

This module may:

- define host-owned terminal pane actions and platform-default shortcuts.
- provide optional reusable host chrome such as search bars.
- adapt pure completion requests, candidates, and feedback to Swing vocabulary.
- depend on `ketraterm-ui-swing` public APIs.

## Boundary

This module must not:

- parse terminal output protocols.
- mutate terminal core internals.
- start PTY or transport processes.
- depend on standalone app modules.
- depend on IntelliJ Platform APIs.
- choose completion sources, priorities, persistence, or product keymaps.
- install shortcuts or UI chrome automatically into `SwingTerminal`.

Hosts must explicitly instantiate and wire this module's helpers.
