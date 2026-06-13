# Terminal Input Agent Guide

`jvterm-input` owns host-to-terminal input encoding. It converts UI-level key,
paste, focus, and mouse events into bytes written to the terminal host input
stream.

Read `docs/terminal-input-contract.md` before changing public input behavior.

Keyboard, paste, focus, and cell-coordinate mouse encoding are implemented.
Richer keyboard protocols and pixel-coordinate mouse reporting remain future
work.

## Boundary

Input owns:

- keyboard event vocabulary and encoding.
- paste and focus report encoding.
- mouse report encoding.
- allocation-conscious scratch buffers for generated input sequences.

Input must not:

- parse terminal output bytes or escape sequences.
- mutate terminal grid, cursor, scrollback, or pen state.
- depend on `jvterm-parser` or `jvterm-host`.
- read renderer state, grid arrays, cursor internals, or parser state.
- invent terminal mode semantics outside core/protocol vocabulary.

The intended dependency shape is:

```text
UI adapter -> terminal actor -> terminal-input -> TerminalHostOutput -> PTY stdin
parser/core responses -> same terminal actor -> TerminalHostOutput -> PTY stdin
```

UI adapters should not call the default encoder concurrently with parser/core
response writers. The default encoder intentionally owns a reusable scratch
buffer and is serialized-use only; the terminal actor owns host-bound byte
ordering across keyboard, mouse, paste, focus, DSR/CPR/DA, and future OSC/DCS
responses.

For mode-dependent behavior, input should read packed mode bits once per event
from core's input-readable API and then encode from that stable value.

## Current Dependency Note

The target plan refers to a future `:jvterm-core-api` module. This repository
currently exposes core API types from `:jvterm-core`, so the scaffold depends
on `:jvterm-core` until that API split exists.

## Implementation Rules

- Add `TerminalHostOutput` to `:terminal-protocol` before adding encoders.
- Keep `KeyboardEncoder` stateless with respect to modes; pass packed mode bits
  into each encode call.
- Do not add a `TerminalInputModeSnapshot` data class.
- Do not decode mode bit positions in `:jvterm-input`; use core API helpers.
- Do not allocate arrays or strings for generated CSI/SS3 sequences on the hot
  path.
- Keep new protocol work layered behind explicit event vocabulary, policy, and
  core mode helpers.

## Testing

Input tests should assert exact bytes. Cover validation errors, modifier
translation, printable UTF-8, Ctrl/Alt handling, special keys, keypad modes,
bracketed paste, focus reporting, and a real core mode-bit host case.

Do not hide expected byte sequences behind broad helpers. Fixtures may record
bytes, but each test should make the terminal semantics obvious.
