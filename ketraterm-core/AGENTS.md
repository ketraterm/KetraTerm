# Terminal Core Agent Guide

`ketraterm-core` owns headless grid mutation, cursor physics, margins, scrollback,
tab stops, pen attributes, durable modes, width policy, and cluster storage. Read
[the root guide](../AGENTS.md) first and use the
[core contract](docs/terminal-core-contract.md) for integration semantics.

## Core Boundary

Accept semantic writer, cursor, and mode operations. Parsing, UTF-8 decoding,
grapheme segmentation, charset translation, input encoding, and UI policy belong
to their owning modules. Width calculation belongs here; parser-generated
clusters must not arrive with a parser-assigned cell width.

The standard buffer has no synchronization boundary. Preserve caller-serialized
mutation and borrowed reads, callback-scoped non-reentrant render frames, and
the documented atomic mode snapshots. Do not expose mutable storage through
public interfaces.

## Data-Oriented Rules

- Keep primitive cell storage and allocation-light scalar/ASCII writes; avoid
  object-per-cell models and new allocations in steady-state mutation paths.
- Preserve lazy extended-attribute storage and batched history-row allocation.
- A screen's ring and cluster store are co-owned. Transfer cluster handles only
  within that arena; deep-copy payloads when moving to a new store.
- Release cluster handles before dropping or recycling live rows. Preserve row
  identities, wrap provenance, and generation invalidation when content moves.

## Cell Invariants

Follow the [cell storage invariants](docs/grid-storage-layout.md#invariants)
when changing row or cluster operations.

Every write, erase, shift, scroll, and reflow must preserve complete occupied
spans. Touching a wide spacer must not leave its leader or a stale cluster
reference behind. Partial-width operations must preserve cells outside the
margins except occupants crossing a boundary.

## Width and attribute ownership

Use generated, pinned Unicode tables. Do not derive width from JDK assignment,
locale, fonts, regex, ICU, or `BreakIterator`. Keep scalar validation before
mutation and keep segmentation in the parser.

Represent pen and stored attributes without degrading values in the host
adapter. The response channel owns capability allowlisting; host security
permission and global response rules remain defined by the root guide.

## Testing

For changed behavior, add focused tests for exact grid/cursor results and storage
invariants. Cover margins, pending wrap, wide and clustered spans, protection,
history, alternate-buffer lifecycle, reset, and resize where affected. Include
invalid inputs and overflow boundaries relevant to the operation.

Run `./gradlew spotlessApply`, then `./gradlew :ketraterm-core:test`. Changes to
parser-to-core mapping also require the appropriate host byte-stream tests.
Feature scope and deferred work belong only in the canonical feature and gap
maps linked by the root guide.
