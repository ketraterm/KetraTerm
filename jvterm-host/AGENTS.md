# Terminal Integration Agent Guide

`terminal-integration` is the bridge between `terminal-parser` and
`terminal-core`.

It maps `TerminalCommandSink` calls to `TerminalBufferApi` calls and host-facing
metadata. It must stay thin, explicit, and honest about unsupported behavior.

## Integration Boundary

Integration owns:

- mapping parser semantic commands to core APIs.
- converting parser coordinate conventions to core API conventions.
- holding temporary host metadata when core does not own it yet, such as title
  and hyperlink fields.
- explicit TODOs for parser/core gaps.

Integration must not:

- parse bytes or escape sequences.
- duplicate CSI/OSC/SGR logic.
- inspect or mutate core internals.
- fake unsupported core features.
- silently clamp richer parser data into weaker core models.

Example: if parser emits 256-color SGR and core only supports ANSI 16, the
adapter must ignore or TODO the unsupported value. It must not clamp color 196
to color 15 and pretend that is correct.

## TODO Discipline

Every intentionally unwired feature should say why and where the real work
belongs:

- `TODO(core-gap)`: core API/storage does not exist yet.
- `TODO(parser-gap)`: parser does not emit the semantic command yet.
- `TODO(integration)`: both sides exist, adapter mapping is missing.
- `TODO(policy)`: security or compatibility policy is required.

Also update `docs/terminal-feature-gap-map.md` when a gap is discovered,
closed, or reclassified.

## Testing

Integration tests should prove real parser-to-core behavior, not just adapter
method calls.

Prefer tests that:

- feed real bytes through `TerminalOutputParser`.
- use `CoreTerminalCommandSink`.
- assert public `TerminalBufferApi` state.
- include mode-dependent behavior such as origin mode, auto-wrap, newline mode,
  alternate screen, mouse/focus/bracketed paste flags, and SGR attributes once
  core supports them.

For adapter-only gaps, tests may assert honest no-op behavior when the feature is
documented as unsupported. Never assert a fake degraded behavior as if it were
correct terminal semantics.
