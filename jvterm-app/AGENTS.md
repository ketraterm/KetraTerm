# Terminal Standalone App Agent Guide

`jvterm-app` owns the product standalone Swing host for Lattice.
It wires reusable terminal UI, local PTY sessions, app chrome, host services,
and process lifecycle.

## Responsibilities

This module may:

- create standalone application windows and menus.
- start and close local PTY-backed terminal sessions.
- bind `TerminalSwingTerminal` to `TerminalSession`.
- provide native Swing/AWT host services such as clipboard, dispatch, hyperlink
  policy, theme settings, and scrollbars.
- adapt external Swing scrollbars through `TerminalSwingTerminal` viewport APIs.

## Boundary

This module must not:

- add reusable rendering logic that belongs in `jvterm-ui-swing`.
- parse terminal output protocols.
- mutate terminal core internals.
- encode input bytes directly.
- introduce IntelliJ Platform dependencies.

Reusable UI fixes discovered while building the standalone app belong in
`jvterm-ui-swing`.
