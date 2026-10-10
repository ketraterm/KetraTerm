# KetraTerm Render API

`ketraterm-render-api` defines the primitive render contracts used by core,
sessions, render caches, and UI implementations. It has no dependency on another
KetraTerm module or UI toolkit. All public types are in
`io.github.ketraterm.render.api`.

## Architectural Role

A `TerminalRenderFrameReader` supplies a borrowed `TerminalRenderFrame` inside a
synchronous callback. Consumers copy the data they need before returning. The
reader implementation owns synchronization; the interfaces do not provide a lock
or a thread scheduler.

The contract includes viewport dimensions, row identities and generations, cursor
state, packed cell attributes, color palettes, and grapheme cluster callbacks.
Font selection, glyph shaping, painting, and blink timing belong to renderers.
For a ready-made copied frame, use
[`ketraterm-render-cache`](../ketraterm-render-cache/README.md).

## How to Use

This example copies the base channels of one row into reusable caller-owned
arrays. The resulting arrays remain usable after the frame callback returns;
calling `update` again replaces their contents. Use the instance on one thread or
serialize access to it.

```kotlin
import io.github.ketraterm.render.api.TerminalRenderFrameReader

class RenderRowCopy {
    var codeWords = IntArray(0)
        private set
    var attributes = LongArray(0)
        private set
    var flags = IntArray(0)
        private set

    fun update(reader: TerminalRenderFrameReader, row: Int) {
        reader.readRenderFrame { frame ->
            require(row in 0 until frame.rows)
            if (codeWords.size != frame.columns) {
                codeWords = IntArray(frame.columns)
                attributes = LongArray(frame.columns)
                flags = IntArray(frame.columns)
            }
            frame.copyLine(
                row = row,
                codeWords = codeWords,
                attrWords = attributes,
                flags = flags,
            )
        }
    }
}
```

`codeWords` contains Unicode scalar values only for `CODEPOINT` cells. A complete
text renderer must also handle `CLUSTER` callbacks and wide-cell flags; a zero
code word alone does not identify a blank. Request `extraAttrWords` and
`hyperlinkIds` when those channels are needed. The
[frame lifecycle guide](docs/render-frame-lifecycle.md) describes those handoffs.

Use the frame's `palette.foreground(word)` and `palette.background(word)` to
resolve primary attributes to packed ARGB colors. A host can supply its own
immutable `TerminalColorPalette`, including all 256 indexed colors, selection and
cursor colors, bold-as-bright behavior, and an explicit dark/light preference.

## How to Extend: Custom State Provider

Implement `TerminalRenderFrameReader` and `TerminalRenderFrame` to expose another
state source. Deliver a consistent frame during the callback, implement the
required row/cursor methods, and provide public attribute words rather than a
private storage encoding. Optional metadata and viewport overloads have
conservative defaults; document which ones your reader implements.

Keep a reader associated with one content source. Replacing it with unrelated
content requires a new reader or an explicit consumer-cache reset. A reader may
reject nested reads with `IllegalStateException`, but must leave the enclosing
frame valid. For full lifetime, generation, and range rules, see the guide below.

## Sub-Documentation

- [Render frame lifecycle and concurrency](docs/render-frame-lifecycle.md)
- [Stable attribute and cell encodings](docs/attribute-packing.md)
- [Module ownership](Module.md)
