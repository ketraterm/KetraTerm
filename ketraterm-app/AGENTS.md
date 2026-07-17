# Terminal Standalone App Agent Guide

`ketraterm-app` owns the product standalone Swing host for KetraTerm.
It wires reusable terminal UI, local PTY sessions, app chrome, host services,
and process lifecycle.

## Responsibilities

This module may:

- create standalone application windows and menus.
- start and close local PTY-backed terminal sessions.
- bind `SwingTerminal` to `TerminalSession`.
- provide native Swing/AWT host services such as clipboard, dispatch, hyperlink
  policy, theme settings, and scrollbars.
- adapt external Swing scrollbars through `SwingTerminal` viewport APIs.
- compose standalone completion sources, persistence settings, and lifecycle.

## Boundary

This module must not:

- add reusable rendering logic that belongs in `ketraterm-ui-swing`.
- parse terminal output protocols.
- mutate terminal core internals.
- encode input bytes directly.
- introduce IntelliJ Platform dependencies.
- move reusable completion snapshots or Swing vocabulary adapters into the app.

Reusable UI fixes discovered while building the standalone app belong in
`ketraterm-ui-swing`.
