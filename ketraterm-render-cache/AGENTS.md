# Terminal Render Cache Agent Guide

`ketraterm-render-cache` owns renderer-side copies of primitive render frames,
bounded retained-range copying, and leased publication between writers and
readers. Its only production dependency is `ketraterm-render-api`.

## Boundary

Keep parser, core, host, session, transport, PTY, and UI dependencies out of this
module. Source readers own terminal synchronization. Font selection, glyph runs,
selection state, painting, and repaint scheduling belong to renderers.

## Invariants

- `TerminalRenderCache` is mutable and requires confinement or external
  serialization. Published caches and their arrays are borrowed read-only until
  the corresponding reader lease ends.
- Source reader identity qualifies generation comparisons. Reset ends a source
  lifetime; direct frame consumers must reset before replacing their source.
- `hasFrame` becomes true only after a complete copy. Failed copies must not
  leave usable partial frames or skip required rows on retry.
- Logical dimensions define the active array prefix. Preserve capacity reuse
  without treating spare rows or stale cluster payloads as visible content.
- Keep cell and cluster copying primitive. Growth may allocate; avoid per-cell
  objects or string assembly in frame-copy loops.
- Lease bookkeeping must prevent reuse of pinned or writer-owned buffers.
  Release leases on callback failure and non-local returns.

## Testing

Use fake `TerminalRenderFrameReader` instances rather than live sessions. Assert
source replacement, generation-based row copies, resize and reserve reuse,
cluster clearing, cursor changes, range bounds, copy failures, and pinned-buffer
stability. Coordinate concurrent tests with explicit handshakes and bounded waits.

Run `./gradlew :ketraterm-render-cache:test` for implementation changes.
