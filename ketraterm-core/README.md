# KetraTerm Core (`:ketraterm-core`)

Headless terminal state for Kotlin/JVM 25. Core owns the grid, cursor, margins,
scrollback, cell attributes, terminal modes, and Unicode cell-width policy. It
accepts semantic operations; escape-sequence parsing belongs to
[`ketraterm-parser`](../ketraterm-parser/README.md) and
[`ketraterm-host`](../ketraterm-host/README.md).

## How to Use

```kotlin
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.model.CellColor

fun main() {
    val buffer = TerminalBuffers.create(width = 80, height = 24, maxHistory = 1000)
    buffer.writeText("Hello, KetraTerm!")
    buffer.carriageReturn()
    buffer.newLine()
    buffer.setPenColors(
        foreground = CellColor.indexed(2),
        background = CellColor.DEFAULT,
        bold = true,
    )
    buffer.writeText("Green, bold text")
    buffer.positionCursor(col = 10, row = 5)
    buffer.writeCodepoint('X'.code)

    check(buffer.getCodepointAt(col = 10, row = 5) == 'X'.code)
    println(buffer.getScreenAsString())
}
```

`writeText` writes literal scalar values. It does not interpret `\n`, `\r`, `\t`,
escape sequences, or grapheme boundaries. Use explicit control operations as
above, or connect a parser through the host adapter for terminal output.
`writeCluster` accepts a pre-segmented grapheme and copies its codepoints before
returning.

Cursor and reader coordinates are zero-based. Margin and rectangle methods use
the DEC coordinate conventions documented on those methods. Dimensions must be
positive; `maxHistory` is nonnegative, and zero disables primary scrollback.

## Reading and synchronization

`TerminalBuffers.create` returns a `TerminalRenderBuffer`: the complete
`TerminalBuffer` API plus `TerminalRenderFrameReader`. The factory installs no
lock. Serialize mutations, cursor/grid reads, response draining, and frame reads
with each other. A borrowed `TerminalLine` or render frame must stay within that
serialized read; copy data before releasing it. Frame callbacks cannot nest on
the same buffer.

Mode snapshots from the standard buffer are atomic. Capture one packed snapshot
when decoding several input fields; reading a mode snapshot does not make a
separate cursor or grid read coherent.

`getScreenAsString` and `getAttrAt` are allocating inspection conveniences for
tests and debugging. Renderers should copy the primitive render frame or use
[`ketraterm-render-cache`](../ketraterm-render-cache/README.md). A
[`TerminalSession`](../ketraterm-session/README.md) supplies the synchronization
and outbound-write boundary when assembling a complete terminal pipeline.

## Terminal responses

`TerminalResponseChannel` is part of `TerminalBuffer`. Query methods enqueue
terminal-to-host responses; `readResponseBytes` drains them into a caller-owned
array. These bytes go to the process or connector's **input** side. A session
performs this drain and serialized write automatically.

The host adapter applies terminal-response security permission before invoking
queries, and core limits responses to its capability allowlist. Direct embedders
must enforce the same permission before issuing query operations.

## Sub-Documentation

- [Core contract](docs/terminal-core-contract.md): coordinates, writes, borrowed reads, reset, and resize semantics.
- [Grid storage layout](docs/grid-storage-layout.md): internal arrays, row recycling, and cluster ownership.
- [Module guide](Module.md): maintainer boundaries and data flow.
- [Feature map](../docs/terminal-feature-map.md) and [gap map](../docs/terminal-feature-gap-map.md): supported behavior and deferred work.
