# Terminal Standalone App Agent Guide

`ketraterm-app` owns the standalone desktop product: window chrome, tabs and
splits, persisted preferences, local PTY launch policy, and host services.
Read the [root guide](../AGENTS.md) first. Consumer setup belongs in
[README.md](README.md); dependencies and components are in [Module.md](Module.md).
The persisted format belongs in the
[configuration guide](docs/profile-config-toml.md).

## Responsibilities

This module may:

- create standalone application windows and menus.
- start and close local PTY-backed terminal sessions.
- bind `SwingTerminal` to `TerminalSession`.
- provide native Swing/AWT host services such as clipboard, dispatch, hyperlink
  policy, theme settings, and scrollbars.
- adapt external Swing scrollbars through `SwingTerminal` viewport APIs.
- compose standalone completion sources, persistence settings, and lifecycle.
- prepare standalone shell metadata, configuration paths, and companion commands.
- own standalone preferences, TOML persistence, and settings publication.

## Boundary

This module must not:

- add reusable rendering logic that belongs in `ketraterm-ui-swing`.
- parse terminal output protocols.
- mutate terminal core internals.
- encode input bytes directly.
- introduce IntelliJ Platform dependencies.
- move reusable completion providers or Swing vocabulary adapters into the app.

Reusable UI fixes discovered while building the standalone app belong in
`ketraterm-ui-swing`.

## Lifecycle and threading

- Create, update, and dispose Swing components on the EDT. Dispatch workspace
  callbacks before accessing window or pane state.
- `TabManager` coordinates product lifecycle; `TerminalWorkspace` owns sessions.
  `TerminalPane.close()` releases view resources without closing the session.
  Release both sides when a pane fails to initialize or a tab closes.
- Cleanup must attempt all owned resources even if an earlier step fails.
  Preserve the original failure and attach subsequent cleanup failures.
- Save validated settings off the EDT before publishing the immutable snapshot
  and notifying consumers on the EDT. A failed save must leave active settings
  and the previous file unchanged.
- Keep configuration bounds in `KetraTermConfig` and the shared host-preference
  bounds. Keep serialization, settings UI, and runtime conversion consistent.
- Terminal-originated actions use the owning session's permissions. Do not infer
  clipboard or title trust from process names, remote-host metadata, or tab focus.

## Validation

Run formatting and focused `:ketraterm-app:test` checks for behavior changes.
Use configuration round-trip tests for persisted preferences, lifecycle tests
for cleanup, and geometry tests for window changes. Test native PTY behavior in
its owning module.
