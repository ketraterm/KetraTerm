# Terminal text rendering and font fallback

`TerminalTextPainter` consumes logical cells from the copied render cache.
`TerminalBidiLayout` places those cells in the same visual columns used by
backgrounds, overlays, cursor geometry, and hit testing. Bidi affects placement
and shaping direction without changing a cell's rendering policy. These types
are internal; hosts configure fonts through `SwingSettings` and `SwingHostServices`.

## Cell and run dispatch

| Content | Rendering path |
| --- | --- |
| Compatible LTR ASCII | `drawChars` when font advances match the grid; otherwise a cached vector with explicit cell positions |
| Contextual scripts / ordinary text in RTL runs | Contextually shaped vectors positioned by terminal-cell ownership |
| Supported box, block, and geometric characters | Programmatic cell primitives |
| Emoji presentation sequences | Available platform rasterizer, then Java2D fallback |
| Other scalars and clusters | Bounded cached `TextLayout` fallback |

`columnSpacing` changes the cell advance rather than the font size. Negative
spacing retains the uncondensed glyph-fitting budget while primitives,
backgrounds, cursor, and hit testing use the adjusted grid. Text can overlap
neighboring cells within a run; existing paint-span clipping still applies.

ASCII batching compares foreground, font style, visibility, decorations, and
hyperlink state. Backgrounds are separate runs. ASCII does not invoke contextual
shaping or fallback-font searches. Its ink is clipped to the run's cell span;
matching advances alone do not prevent italic or antialiased overhang. When the
caller clip is already contained, painting leaves it untouched. Otherwise it
intersects and restores the exact clip, including nonrectangular shapes.

Ordinary cells and block-cursor foreground share primitive, emoji, and fallback
dispatch. A directional character elsewhere in a row does not disable primitives
or emoji. Classification precedes access to the lazy emoji rasterizer.

## Native emoji image caching

`TerminalEmojiImageCache` retains up to 1,024 images or unsupported results for one
rasterizer. Identity is code point content plus raster pixel size; equivalent
scalar and single-code-point cluster requests share an entry. Position, color,
and cell span do not affect identity when raster size is unchanged. Lookups use
reusable keys and borrowed cluster slices. Misses snapshot the content and create
text for rasterization. Borrowed slices are released after lookup, and negative
results share the same bounded LRU as images.

The current platform rasterizer reads installed Segoe UI Emoji COLR/CPAL data on
Windows. Other platforms use Java2D fallback. Native painting does not depend on
JetBrains Runtime detection. Initialization and misses are synchronous: the first
qualifying emoji may load and parse the font on the painting thread, normally
the EDT. Classification avoids this work for ordinary text but does not move it
off-thread. Cache-hit measurements exclude initialization and rasterization.

## Contextual shaping and terminal geometry

Shaping spans are constrained by direction, font style, Unicode script, and cell
category. Foreground colors, decorations, hover, and concealment determine paint
spans without breaking neighboring shaping context.

The run buffer records a terminal-cell owner for each UTF-16 unit, including
surrogate pairs and combining sequences. `Font.layoutGlyphVector` provides
contextual shaping. The cache maps glyph indices back to those owners and positions
complete glyph groups at their assigned visual cell spans. Combining marks retain
their base-relative offsets; ligatures own the union of consumed cells. Oversized
groups are fitted using each group's glyph-size budget, which retains the original
font width when spacing is condensed. The entire line is not proportionally fitted.

Large vectors retain bounded glyph batches. Uniform paint spans submit the whole
vector; partial styles and cursor clips submit only intersecting batches. Batches
preserve glyph codes, positions, and transforms and are built on cache insertion.
The block cursor paints the same positioned context under its cell clip instead
of shaping an isolated character. Concealed or hidden blinking cells still
suppress foreground painting.

A shaping window admits at most 2,048 code points / 4,096 UTF-16 units and stops
before splitting a terminal cluster. Long spans continue through windows with
shaped-cluster lookahead and lookbehind. A cluster occupying the whole budget is
consumed; lookbehind is dropped when it would prevent progress. These bounds
preserve coverage but cannot promise unbounded contextual equivalence for every
font. Oversized individual clusters have a separate fallback bound.

Cached vectors are read-only after positioning. Identity includes text, cell
ownership/span, style, direction, cell advance, and glyph-fitting width.
Font-source, font-generation, and font-render-context changes invalidate relevant entries. Lookup scratch
buffers are reused and belong to the calling painter.

## Font resolution

Ordinary text tries the primary font, optional host resolver, configured fallback
fonts, then enabled system fallbacks. Emoji presentation prefers the host
resolver and configured/system emoji fonts before the ordinary pipeline. Resolved
fonts use the configured size. Fallback and unsupported lookups use bounded
caches. System scanning is outside the ASCII path.

Native dispatch and font preference share `TerminalEmojiPresentation` for
scalars, cluster slices, and UTF-16 text. An emoji base is required; a joiner or
variation selector alone does not classify ordinary script text as emoji. VS15
retains text presentation. Platform font substitution must not give an emoji
font precedence over a capable primary font for ordinary text.

## Allocation measurement boundaries

Component painters and scratch buffers belong to the EDT. Unit tests assert
cache reuse/invalidation, glyph geometry, pixels, and publication ordering.
Allocation measurements use JMH warmup, forks, and a GC profiler, rather than
exact-byte unit-test assertions. Internal helper benchmarks have private frozen
frames and graphics; component/controller benchmarks dispatch batches to the EDT
and include amortized dispatch cost.

| Benchmark | Boundary |
| --- | --- |
| `TerminalTextRenderingBenchmark` | Warm style scanning, shaped lookup, contained-clip ASCII, and clipped glyph batches; direct Java2D controls |
| `TerminalFontConfigurationBenchmark` | Unchanged configuration and retained unsupported-glyph lookup |
| `TerminalEmojiBenchmark` | Image/negative cache hits using a recording rasterizer; excludes OS font loading |
| `TerminalBidiBenchmark` | Cached mapping/projection and overscan frame acceptance |
| `TerminalRepaintBenchmark` | Search projection and damage planning |
| `TerminalScrollbarBenchmark` | Overlay painting with direct drawing controls |
| `TerminalViewportPublicationBenchmark` | EDT primitive publication and callbacks; excludes public snapshot construction |
| `TerminalSearchRefreshBenchmark` | Unchanged active search refresh with retained history |
| `SwingPaintBenchmark` | Complete static component paint in EDT batches |

See the [benchmark guide](../../ketraterm-benchmarks/README.md) for commands and
measurement requirements. Use matching glyphs, positions, transforms, clips, and
font-render contexts when comparing painter operations.

Complete component painting creates a `Graphics2D` copy and may project a
selection. Java2D clipping, transforms, and drawing can allocate. Cold font
loading, layout construction, rasterization, cache growth, session publication,
and host callbacks have separate costs. Helper cache-hit measurements do not
establish zero allocation for a complete frame or live-window input latency.

Raster comparisons must preserve antialiased coverage and exact shaping input.
Erase the old cursor region before comparing a repaint; repeated `SrcOver`
drawing is not idempotent. Keep joiners in Arabic reference text: removing a ZWJ
can change ligature formation. Compare caches with direct shaping of the same
text and painter output with the complete contextual input.
