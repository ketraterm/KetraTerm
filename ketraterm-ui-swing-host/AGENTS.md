# Terminal UI Swing Host Agent Guide

`ketraterm-ui-swing-host` owns optional Swing host chrome and default host
action bindings for applications embedding `SwingTerminal`.

This module may provide reusable Swing panels, controllers, and shortcut
vocabulary for hosts. It must not become part of the core reusable terminal
component itself.

## Responsibilities

This module may:

- define host-owned terminal pane actions and platform-default shortcuts.
- provide optional reusable host chrome such as search bars.
- depend on `ketraterm-ui-swing` public APIs.

## Boundary

This module must not:

- parse terminal output protocols.
- mutate terminal core internals.
- start PTY or transport processes.
- depend on standalone app modules.
- depend on IntelliJ Platform APIs.
- install shortcuts or UI chrome automatically into `SwingTerminal`.

Hosts must explicitly instantiate and wire this module's helpers.
