# KetraTerm Render Cache (`:ketraterm-render-cache`)

Reusable primitive copies of terminal render frames, plus leased publication
between a render worker and its consumers. The module depends only on
[`ketraterm-render-api`](../ketraterm-render-api/README.md).

Use `TerminalRenderCache` when one worker owns the copy. Use
`TerminalRenderPublisher` when a writer and readers need separate storage. A
`TerminalSession` already owns a publisher; session consumers should use
`readPublishedFrame` rather than create another publication pipeline.

## How to Use

For a custom renderer, the following owner publishes on a serialized render
worker and copies the latest frame on a serialized drawing thread. The drawing
callback runs after the publication lease has been released.

```kotlin
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.render.cache.TerminalRenderPublisher

class RendererFrames(columns: Int, rows: Int) {
    private val publisher = TerminalRenderPublisher(columns, rows)
    private val drawingFrame = TerminalRenderCache(columns, rows)

    fun update(reader: TerminalRenderFrameReader) {
        publisher.updateAndPublish(reader)
    }

    fun draw(paint: (TerminalRenderCache) -> Unit): Boolean {
        val copied = publisher.readCurrent { source ->
            drawingFrame.updateFrom(source)
            true
        } ?: false
        if (copied) paint(drawingFrame)
        return copied
    }
}
```

The drawing cache belongs to this renderer. Do not mutate its exposed arrays or
retain them across later draws. A renderer may instead paint directly inside
`readCurrent`; all access must then finish before that callback returns. Before
the first publication, `readCurrent` returns `null` without invoking its callback.

For direct reads, reuse a `TerminalRenderCache` and call `updateFrom(reader)`.
Its overloads request a scrollback offset and optional render-only overscan;
the reader determines the resolved shape. Constructing with
`rowCapacityReserve` can absorb row-count changes without replacing cell planes.
Only `columns * rows` cells and `rows` row entries belong to the current frame.
Use `rowOffset(row)` and the reported dimensions, not array capacity, for indexing.
Interpret `codeWords` together with `TerminalRenderCellFlags`; clustered cells
use `clusterRefs` and `clusterCodepoints`, and wide trailing cells carry no glyph.

## Ownership and validity

A cache is mutable and requires thread confinement or external serialization.
Publisher leases make a published cache read-only for the duration of
`readCurrent`. Do not retain a borrowed cache or its arrays, update it, or reenter
the publisher from that callback. The publisher uses three caches and may wait
for readers when no non-front buffer is available for writing.

`hasFrame` is false initially, after `reset`, and after an incomplete copy. Reset
clears source data and metadata while retaining dimensions and storage capacity.
Reader-based updates detect replacement readers and force a complete copy;
direct `accept(frame)` callers must reset when changing sources. Replacing the
content behind the same reader also requires an explicit reset.

## Retained ranges

`TerminalRenderRangeCopy` copies the retained intersection of an inclusive
absolute row range. Reuse it on one worker; its cache and bounds remain valid
until the next read. The default limits are 64 rows and 4096 cells per read, with
one physical row allowed even when wider than the cell budget. Cluster payloads
accompany their row and are not bounded by the cell count.

Use `firstAbsoluteRow` and `lastAbsoluteRow` for the sliced origin; the cache's
history metadata still describes the source. A `false` result means no requested
row remains and `cache.hasFrame` is false. Assemble strings and perform analysis
after `read` returns. The optional cancellation check runs during source copying
and must be cheap and non-suspending.

## Allocation behavior

Primitive planes and cluster buffers are reused, and unchanged rows skip source
cell copying. Construction, capacity growth, and some shape changes allocate;
this is not a guarantee of allocation-free frame updates. The `cursor` convenience
property and `clusterText` produce value objects or strings. Render hot paths
should use the primitive cursor fields and packed cluster storage directly.

## Sub-Documentation

- [Publication and cache concurrency](docs/triple-buffering-concurrency.md)
- [Render contracts](../ketraterm-render-api/README.md)
- [Session lifecycle and synchronized access](../ketraterm-session/README.md)
