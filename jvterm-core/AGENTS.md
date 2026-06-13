# Terminal Core Agent Guide

`terminal-core` is the headless terminal state engine. It owns grid mutation,
cursor physics, scrollback, modes, tab stops, pen attributes, width policy, and
cluster storage.

It must not parse escape sequences, decode UTF-8, segment graphemes, encode
input events, or render UI.

## Core Boundary

Core receives semantic operations from parser/host-facing APIs such as
`writeCodepoint`, `writeCluster`, cursor movement, erase/edit commands, mode
setters, and pen setters.

Core owns:

- cursor bounds and clamping
- origin mode and margin-relative movement
- wrapping and pending-wrap behavior
- scroll regions and scrollback
- tab-stop state
- primary and alternate buffers
- cell width decisions
- durable terminal modes
- packed cell attributes and cluster handles

Parser owns what text/protocol was received. Core owns where that text lands and
how many cells it occupies.

## Data-Oriented Rules

- Keep grid storage flat and primitive.
- Preserve cache-friendly layouts: `IntArray` cells, packed attributes, bounded
  cluster stores, and explicit sentinel values.
- Do not introduce object-per-cell storage.
- Do not allocate in mutation hot paths unless the operation inherently stores a
  grapheme cluster or resizes/reflows.
- Keep complex cluster data tied to bounded screen/history lifecycles.

## Cell Invariants

Every grid mutation must preserve cell invariants:

- `0` means empty.
- Positive values represent direct scalar codepoints when locally encoded that
  way by the current storage model.
- Wide spacers and cluster handles must never be orphaned.
- Overwriting any cell in a wide or clustered span must clear the full previous
  span before writing the new content.

If a mutation touches wrapping, insert/delete, erase, scroll, resize, margins, or
wide clusters, add tests that prove no corrupted leaders, spacers, or stale
cluster references remain.

## Width Ownership

Width calculation belongs here, not in `terminal-parser`.

Core width policy must account for:

- East Asian wide/full-width codepoints.
- combining and zero-width codepoints.
- emoji presentation and ZWJ clusters.
- variation selectors.
- ambiguous-width policy from terminal mode/configuration.

Generated Unicode tables should eventually replace curated seed ranges. Keep the
API table-shaped now so generation is a mechanical upgrade later.

## Attribute Ownership

Core must represent pen attributes truthfully. If an attribute is not supported,
do not let host fake it.

High-priority gaps:

- inverse/reverse-video cell attribute
- 256-color indexed foreground/background
- RGB/truecolor foreground/background
- faint, blink, conceal, strikethrough
- richer underline styles and underline color

Document each gap in `docs/terminal-feature-gap-map.md` until implemented.

## Response Channel Security

When implementing or extending query/response features (such as `DECRQSS` or `XTGETTCAP`), or when creating new terminal features that can be queried, always update the security allowlist of queried settings or capabilities in `TerminalResponseChannel` (e.g. in `BufferResponseChannel.kt`), and reject unauthorized or unsupported queries with standard protocol-defined failure responses.

## Testing

Core tests should focus on invariants and terminal physics:

- cursor movement with margins, origin mode, and bounds
- wrap and pending-wrap behavior
- insert/delete/erase with wide and clustered cells
- scroll regions and scrollback retention
- alternate buffer behavior
- resize/reflow with cluster preservation
- tab stops
- mode snapshots
- pen attribute storage and reset behavior

Tests must verify expected terminal behavior, not current bugs. Prefer small
unit tests for exact mechanics and broader invariant tests around mutation
engines.
