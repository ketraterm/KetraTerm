---
name: jvterm-core-grid-physics
description: Core terminal grid/state guidance for this repository. Use when changing jvterm-core grid mutation, cursor movement, scrollback, resize/reflow, clusters, tab stops, margins, modes, width policy, pen attributes, or TerminalBuffer behavior.
---

# Terminal Core Grid Physics

Use this skill when touching `jvterm-core`.

## Boundary

Core owns grid mutation, cursor physics, scrollback, tab stops, modes, pen
attributes, cluster storage, and width policy. It must not parse escape
sequences, decode UTF-8, segment graphemes, encode input, or render UI.

## Data Rules

- Keep grid storage flat and primitive.
- Avoid object-per-cell designs.
- Avoid allocation in mutation hot paths.
- Store complex clusters in bounded lifecycle stores tied to screen/history.
- Width calculation belongs in core and must be mode/policy-aware.

## Invariants

Every mutation must preserve:

- no orphaned wide spacers.
- no stale cluster handles after erase, overwrite, scroll, resize, or eviction.
- no corrupted leaders after partial overwrite.
- correct wrapping and pending-wrap state.
- correct margin and origin-mode behavior.

## Test Checklist

Cover wide cells and clusters in:

- overwrite, erase, insert/delete, scroll, resize, and wrap.
- primary and alternate screen transitions.
- scrollback retention and ED 3 clearing.
- tab stops and margins.
- mode snapshots.
- pen attribute reset and storage.

If an attribute or operation is unsupported, document it as a core gap. Do not
let host fake it.
