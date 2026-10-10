# Module ketraterm-core

The headless state engine. Semantic writer, cursor, and mode operations mutate
primary or alternate screen state; readers expose that state through public core
roles and primitive render frames.

## Ownership and dependencies

Core owns cell placement, wide-cell overwrite rules, wrapping, scroll regions,
scrollback retention, tab stops, packed attributes, cluster storage, width policy,
and durable terminal modes. It depends on `ketraterm-protocol` for shared
vocabulary and `ketraterm-render-api` for the render projection.

The parser decodes bytes and segments graphemes. The host adapter translates
parser commands and applies host security policy. Input encoding, transport,
thread scheduling, font measurement, and painting remain outside core.

## Architectural Role & Grid Storage

`TerminalBuffers.create` constructs the internal `DefaultTerminalBuffer`. It
composes the public writer, cursor, mode, response, reader, and inspector roles
through focused adapters. `MutationEngine` owns cell mutation;
`CursorEngine` owns cursor mechanics; `TerminalResizer` handles primary retention
and reflow. The facade coordinates resize, RIS, and DECSTR across both buffers.

`TerminalState` holds shared modes, pen, palette, response queue, and generation
counters, and selects the active `ScreenBuffer`. Each screen owns its cursor,
margins, saved cursor, Kitty keyboard stack, `HistoryRing`, and `ClusterStore`.
Rows hold primitive cell arrays and handles into their own screen's cluster
store. The alternate screen has no history.

`CoreTerminalRenderFrame` translates this storage into public render encodings.
The borrowed frame is callback-scoped and non-reentrant. Core adds no
synchronization; direct callers serialize access, while `ketraterm-session` owns
that boundary in the assembled pipeline. Atomic mode snapshots are the narrower
concurrency exception.

## Sub-Documentation

- [README](README.md): consumer entry point and a minimal example.
- [Core contract](docs/terminal-core-contract.md): behavioral guarantees and integration rules.
- [Grid storage layout](docs/grid-storage-layout.md): internal allocation and ownership rules.
