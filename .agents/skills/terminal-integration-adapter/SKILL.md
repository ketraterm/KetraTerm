---
name: terminal-host-adapter
description: Integration adapter guidance for mapping terminal-parser TerminalCommandSink semantics to terminal-core TerminalBufferApi. Use when changing CoreTerminalCommandSink, parser-to-core wiring, module dependencies, coordinate conversion, or explicit TODO gap handling.
---

# Terminal Integration Adapter

Use this skill when changing `terminal-host` or parser-to-core wiring.

## Boundary

Integration maps semantic sink calls to core APIs. It must stay thin.

It must not:

- parse bytes or escape sequences.
- duplicate CSI/OSC/SGR command logic.
- inspect or mutate core internals.
- silently clamp richer parser data into weaker core models.
- fake unsupported behavior.

## Mapping Rules

- Convert coordinate conventions explicitly and document the direction.
- Map only behavior that both parser and core can represent correctly.
- Keep host metadata temporary and obvious when core does not own it yet.
- Add explicit TODOs for missing parser/core/policy support.

## Watch Points

- parser zero-based coordinates versus DEC one-based APIs.
- DECSTBM inclusive margins.
- 256-color and RGB SGR until core stores them.
- inverse/faint/blink/conceal/strikethrough until core stores them.
- alternate-screen variants 47, 1047, 1048, 1049.
- DECSTR soft reset versus RIS full reset.
- title, hyperlink, bell, palette, clipboard, and notification host policy.

## Testing

Prefer parser-to-core tests using real byte streams, `TerminalOutputParser`,
`CoreTerminalCommandSink`, and public `TerminalBufferApi` assertions.

Adapter-only tests may assert honest documented no-op behavior for unsupported
features, but never assert degraded behavior as if it were terminal-correct.
