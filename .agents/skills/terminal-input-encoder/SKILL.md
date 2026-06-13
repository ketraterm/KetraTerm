---
name: terminal-input-encoder
description: Input encoder guidance for this Kotlin terminal repository. Use when adding or changing terminal-input keyboard, paste, focus, mouse, host-output, or input mode-bit behavior.
---

# Terminal Input Encoder

Use this skill for work in `:terminal-input` or shared API needed by input.

## Workflow

1. Read root `AGENTS.md`, then `terminal-input/AGENTS.md`.
2. Follow `terminal-input/docs/terminal-input-implementation-plan.md`.
3. Keep `TerminalHostOutput` in `:terminal-protocol`.
4. Read packed mode bits once per event through core's input-readable API.
5. Add tests before or alongside each behavior slice.
6. Keep mouse deferred until keyboard, paste, and focus are implemented and
   passing.

## Boundaries

- Input encodes host-bound bytes; it does not parse terminal output.
- Input may depend on protocol vocabulary and core API mode reads.
- Input must not depend on parser, host, grid storage, cursor internals,
  renderer state, or UI toolkit types.
- Do not decode mode bit positions inside input. Add missing helper methods to
  the core API first.

## Hot Path Rules

- Use primitive bit masks for modifiers.
- Use reusable scratch buffers for dynamic CSI/SS3 sequences.
- Avoid `StringBuilder`, `sliceArray`, regex, or allocation-heavy helpers in
  encoder hot paths.
- `writeBytes(bytes, offset, length)` consumers must synchronously consume or
  copy the byte range because encoders may immediately reuse buffers.

## Testing

Assert exact bytes for every encoded event. Include validation failures,
modifier CSI parameters, UTF-8 printable codepoints, Ctrl/Alt combinations,
application cursor/keypad modes, bracketed paste wrappers, focus reporting, and
one real core mode-bit host case.
