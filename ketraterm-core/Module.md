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
composes the public writer, cursor, mode, response, graphics, reader, and inspector roles
through focused adapters. `MutationEngine` owns cell mutation;
`CursorEngine` owns cursor mechanics; `TerminalResizer` handles primary retention
and reflow. The facade coordinates resize, RIS, and DECSTR across both buffers.
`BufferResponseChannel` queues terminal replies; `XtgettcapResponder` owns
its immutable capability allowlist, batch validation, and reply-size preflight.

`TerminalState` holds shared modes, pen, palette, response queue, and generation
counters, and selects the active `ScreenBuffer`. Each screen owns its cursor,
margins, saved cursor, Kitty keyboard stack, `HistoryRing`, and self-contained rows with lazy cluster payload storage.
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

## Image state

`TerminalGraphics` admits complete raw transfers and ordinary placements.
`GraphicsEngine` validates geometry and applies cursor/reply semantics;
`GraphicsStore` owns bounded immutable resources, primitive placement records and
screen/history lifecycle. The existing mutation engine moves image placements
with scrolling and clears them at the corresponding screen operations. Render
frames expose immutable resources and borrowed placement metadata through
`TerminalRenderImageSink`, without adding image data to text cells.
The [core contract](docs/terminal-core-contract.md#image-state) defines serialized
access and lifecycle; the [render contract](../ketraterm-render-api/docs/render-frame-lifecycle.md#critical-lifespan-rule)
defines the resource lifetime exception.
