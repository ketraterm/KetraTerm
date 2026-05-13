# Terminal UI Swing Demo Agent Guide

`terminal-ui-swing-demo` owns standalone desktop demo wiring for the reusable
Swing terminal component.

## Responsibilities

This module may:

- create Swing windows and demo chrome.
- start local PTY-backed terminal sessions.
- bind `TerminalSwingTerminal` to a running `TerminalSession`.
- adapt window resize events to terminal cell dimensions.
- expose a local development entrypoint for manual UI testing.

## Boundary

This module is host/demo code. It must not:

- add reusable rendering logic that belongs in `terminal-ui-swing`.
- parse terminal output protocols.
- mutate core internals.
- encode input bytes directly.
- introduce IntelliJ Platform dependencies.

Reusable UI fixes discovered while testing the demo should be made in
`terminal-ui-swing`.
