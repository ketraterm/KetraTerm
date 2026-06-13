---
name: jvterm-parser-fsm
description: Parser FSM and protocol dispatch guidance for this terminal emulator. Use when touching ByteClass, AnsiStateMachine, ActionEngine, ParserState, CSI/ESC/OSC/DCS handling, SGR dispatch, charset mapping, UTF-8 recovery, or TerminalParser full byte-stream behavior.
---

# Terminal Parser FSM

Use this skill when changing parser state machines, action engines, protocol
dispatch, or byte-stream recovery behavior.

## Boundaries

- Parser owns UTF-8 decoding, ANSI state transitions, string termination,
  parameter assembly, charset shifts, grapheme segmentation, and semantic sink
  calls.
- Parser does not own terminal width, grid bounds, cursor clamping, cell width,
  scrollback, rendering, or durable core state.

## FSM Rules

- Keep byte classification, matrix transitions, actions, and command dispatch
  separate.
- CSI dispatch should use structural signatures, not final-byte-only switches.
- String states need string-local control handling; do not use global execute
  semantics inside OSC/DCS/SOS/PM/APC bodies when real terminal rules differ.
- Bound OSC/DCS payloads. Ignore overflow-sensitive semantic commands unless a
  clear policy allows dispatch.
- Unknown or malformed sequences must recover according to terminal semantics,
  not accidentally print or dispatch as another command.

## Test Checklist

Add tests at the narrowest useful level and full parser level when observable:

- byte class and state table coverage.
- CAN/SUB aborts.
- ESC `\` ST termination from string states.
- BEL OSC termination.
- omitted, empty, colon, and overflowing params.
- max params, intermediates, payloads, and cluster length.
- malformed UTF-8 followed by ASCII, ESC, CSI, and string terminators.
- chunk boundaries around every structural byte.

Tests must assert real terminal semantics, not current implementation quirks.
