# Module ketraterm-render-cache

Renderer-owned primitive storage and leased publication of copied terminal
frames. Its sole production dependency is `ketraterm-render-api`; synchronization
with terminal mutation belongs to the source reader.

The public types in `io.github.ketraterm.render.cache` have distinct roles:

- `TerminalRenderCache` is a mutable, caller-owned frame copy. It retains cell
  planes, packed cluster codepoints, row metadata, cursor primitives, and source
  generations. Reader identity qualifies incremental copies.
- `TerminalRenderPublisher` owns three caches. A serialized writer publishes a
  copied frame; readers borrow the latest cache through `readCurrent`.
- `TerminalRenderRangeCopy` makes bounded retained-range copies for worker-side
  analysis, with explicit absolute bounds for the sliced origin.

Backend-specific layout, glyph runs, selection, and painting belong to renderer
modules. Arrays expose cache-owned storage for reading; capacity may exceed the
logical frame shape. Copying is allocation-conscious, with allocations permitted
for construction, capacity growth, and shape changes.

See the [consumer guide](README.md) and
[publication contract](docs/triple-buffering-concurrency.md) for ownership,
lifetime, failure, and concurrency rules.
