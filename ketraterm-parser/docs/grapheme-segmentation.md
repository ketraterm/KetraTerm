# Streaming UTF-8 Decoding & UAX #29 Grapheme Segmentation

The parser decodes printable UTF-8, applies charset mapping, and assembles
graphemes before invoking the command sink. Core owns cell width, storage,
wrapping, and cursor effects. This document describes that text handoff; the
[feature map](../../docs/terminal-feature-map.md) and
[gap map](../../docs/terminal-feature-gap-map.md) define supported scope.

## 1. Streaming UTF-8 Decoder (`Utf8Decoder`)

[Utf8Decoder](../src/main/kotlin/io/github/ketraterm/parser/utf8/Utf8Decoder.kt)
stores incomplete scalar state in primitive fields and returns a packed integer
containing output/replay flags and the scalar. It rejects invalid leading bytes,
overlong encodings, surrogates, and values above `U+10FFFF`.

Malformed input emits `U+FFFD`. If a pending scalar receives a byte outside the
continuation range, the decoder resets and asks `TerminalParser` to route that
byte again once. Thus `C3 ESC [ A` produces replacement text followed by a
cursor-up command. A continuation-range byte violating that scalar's bounds is
consumed as part of the malformed subsequence. An incomplete printable scalar
at EOF also produces replacement text; reset discards it.

Printable decoding occurs in `GROUND`. OSC/DCS collection preserves raw payload
bytes, with decoding or validation performed when a complete command dispatches.
Custom OSC handlers receive undecoded bytes. Raw C1 bytes are not treated as
8-bit control introducers by the UTF-8 parser.

## 2. Grapheme Segmentation (Unicode UAX #29)

After charset mapping, [UnicodeClass](../src/main/kotlin/io/github/ketraterm/parser/unicode/UnicodeClass.kt)
returns packed grapheme-break and `Extended_Pictographic` properties.
[GraphemeSegmenter](../src/main/kotlin/io/github/ketraterm/parser/unicode/GraphemeSegmenter.kt)
uses these properties plus bounded context for controls, combining marks,
spacing marks, prepend characters, Hangul, regional-indicator pairs, and emoji
ZWJ continuations. Ordinary adjacent bases take a short boundary path.

Classification data is generated from Unicode **17.0.0**. Printable ASCII bypasses
the table; other values use a two-stage indexed lookup with shared 128-codepoint
blocks. The immutable Latin-1 string representation avoids per-parser table
copies and per-codepoint allocation. JVM compact-string settings affect storage,
not classification. A property-data version is not a claim of complete conformance
to every UAX #29 segmentation rule.

Regenerate using `tools/generate-unicode-tables.ps1` from the repository root,
then run formatting and parser tests. `UnicodeClassTest` compares every Unicode
codepoint against independent, pinned `GraphemeBreakProperty.txt` and
`emoji-data.txt` resources. Upgrade those resources deliberately; the generator
does not rewrite this oracle. `GraphemeSegmenterTest` checks implemented boundary
rules separately from property classification.

<a id="3-the-grapheme-assembler-optimization-graphemeassembler"></a>

## 3. Grapheme publication

[GraphemeAssembler](../src/main/kotlin/io/github/ketraterm/parser/unicode/GraphemeAssembler.kt)
publishes decoded text promptly without waiting for the next grapheme boundary:

1. The first publication uses `writeCodepoint` for one scalar or `writeCluster`
   for a longer retained prefix.
2. A continuation published later uses `updatePreviousCluster` with the complete
   retained sequence, including the earlier prefix. The sink applies it to the
   same text target and preserves the original attributes.

Both `accept` and `acceptByte` publish at their call boundary when the parser is
in `GROUND`. `flushForRender` keeps segmentation context; unchanged prefixes are
not published again. A pending UTF-8 scalar does not delay an already decoded
prefix. The next grapheme or a structural action publishes any pending update
before clearing context. Reset discards pending context.

Publication commits downstream grid effects. Retaining segmentation across calls
does not guarantee identical placement for different chunkings of a grapheme
whose width changes after publication. See the
[streaming placement policy](../../docs/terminal-feature-map.md#streaming-grapheme-placement).

`writeCluster` and `updatePreviousCluster` borrow the reusable `IntArray` only for
the synchronous call. The sink must consume or copy its valid prefix before
returning. It must not reconstruct a continuation from partial text or retain
the parser's array. Optional `TerminalAsciiCommandSink` batching likewise borrows
an ASCII slice, with the final base retained for a possible later continuation.

### Bounded retention

Factory-created parsers retain the first **32 Unicode codepoints** of each
grapheme, including its base, in one reusable array. This is a terminal resource
policy, not a grapheme boundary or a byte/UTF-16 limit.

Once full, the parser discards further continuations without sink writes while
still advancing segmentation context. Filling the buffer never ends the active
grapheme. The next actual boundary starts a new retained cluster; controls and
UTF-8 recovery continue through their normal paths.

Only retained text reaches core and contributes to width, rendering, and copied
text. A base plus 40 combining marks retains the base and first 31 marks; the
remaining marks create no additional cells. A discarded variation selector
cannot change the retained prefix's width. This factory policy imposes no new
32-codepoint restriction on direct core cluster writes.

## Charset save/restore ownership

Live G0-G3 designations and GL/GR shifts are parser-owned. DEC/SCO and 1048
save/restore use separate primary/alternate charset slots selected from the
sink's effective screen. Effective 1049 entry saves primary state; its exit
restores it. Clearing alternate entry through 1047/1049 resets that screen's
saved slot. Repeated or rejected screen requests do not alter these slots.

Restore clears transient single shifts. Unsaved slots, RIS, and DECSTR use ASCII
designations, GL=G0, and GR=G2. A sink must expose effective screen selection
synchronously before its mode callback returns. Direct host resets of core
should be paired with parser reset to keep parser-owned state aligned.

## Validation

`Utf8DecoderTest` covers malformed input and bounded replay.
`GraphemeSegmenterTest` and `UnicodeClassTest` separate boundary rules from data
classification. `TerminalParserTest` exercises publication, retained-prefix
updates, charset saves, overflow, and chunk boundaries through byte streams.
Cross-layer placement and storage behavior are verified in core/host tests.
