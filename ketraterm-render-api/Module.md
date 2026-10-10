# Module ketraterm-render-api

Primitive renderer-facing contracts in `io.github.ketraterm.render.api`.
Its only production dependency is the Kotlin standard library.

## Ownership

- `TerminalRenderFrameReader`, `TerminalRenderFrame`, and callback sinks define
  synchronous borrowed reads and primitive row/cluster/cursor copying.
- `TerminalRenderAttrs`, `TerminalRenderExtraAttrs`, and
  `TerminalRenderCellFlags` define the public cell encoding, independently of
  core storage.
- `TerminalColorPalette` resolves primary attributes to packed ARGB values and
  protects its indexed color storage with defensive copies.

Reader implementations own consistency and synchronization. Render cache owns
retained copies; UI modules own fonts, layout, shaping, painting, and blink timers.

## Further reading

- [Consumer guide](README.md)
- [Frame lifecycle](docs/render-frame-lifecycle.md)
- [Attribute packing](docs/attribute-packing.md)
