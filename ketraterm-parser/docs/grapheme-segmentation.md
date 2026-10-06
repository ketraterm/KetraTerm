# Streaming UTF-8 Decoding & UAX #29 Grapheme Segmentation

The `ketraterm-parser` module handles UTF-8 byte stream decoding and Unicode grapheme cluster assembly using a custom, allocation-free streaming pipeline.

---

## 1. Streaming UTF-8 Decoder (`Utf8Decoder`)

The [Utf8Decoder](../src/main/kotlin/io/github/ketraterm/parser/utf8/Utf8Decoder.kt) accepts raw bytes one at a time and reconstructs Unicode codepoints without heap allocations.

```
       Raw Byte Stream
             │
             ▼
      [Utf8Decoder]
             │
             ├─► Valid codepoint ──────────► GraphemeAssembler
             │
             └─► Malformed sequence
                   ├─► Emit U+FFFD (Replacement char)
                   └─► Reprocess current byte through FSM
```

### UTF-8 Validation Invariants:
* **Overlong & Surrogate Rejection**: Immediately rejects overlong UTF-8 representations and UTF-16 surrogates (`U+D800..U+DFFF`), converting them to `U+FFFD`.
* **Reprocess Current Byte**: When a pending multi-byte UTF-8 sequence receives a non-continuation byte (e.g. an ASCII character or `ESC`), the decoder cancels the sequence, emits `U+FFFD`, and triggers a reprocess code. The `TerminalParser` then processes the non-continuation byte (such as `ESC`) through the normal FSM instead of dropping it, preventing protocol escape sequence loss.

---

## 2. Grapheme Segmentation (Unicode UAX #29)

To support modern TUI layouts (which can include emojis, zero-width joiners, and combining accents), the parser uses the [GraphemeSegmenter](../src/main/kotlin/io/github/ketraterm/parser/unicode/GraphemeSegmenter.kt) to detect grapheme boundaries based on the **Unicode Standard Annex #29 (UAX #29)**.

* **Generated Break Tables**: After charset mapping, `UnicodeClass.properties` returns one packed integer: the low four bits hold the grapheme-break class and bit four holds `Extended_Pictographic`. The assembler passes that value through boundary decisions and context updates, so an emoji flag never requires a second lookup. Printable ASCII bypasses the table. Other codepoints use two indexed reads from `GeneratedGraphemeBreakTable`, independent of the number of Unicode ranges.

* **Complex Sequences**: Correctly handles Zero-Width Joiner (ZWJ) emoji sequences, combining mark characters, regional indicator (flag) pairs, and Hangul Jamo sequences.

Consecutive ordinary bases take an early boundary path when the previous class is
`Other` and the incoming packed properties are `Other` without
`Extended_Pictographic`. This avoids irrelevant continuation checks while retaining
the same context updates and read-boundary publication. Combining, control, prepend,
Hangul, regional-indicator, and pictographic inputs use the existing rule checks.

The generated table shares identical 128-codepoint blocks. Its 8,704 block indices
and 20,992 property values are Latin-1 string constants, offset by `0x40` in source.
On the supported JVM with compact strings enabled, these use immutable byte storage;
there is no runtime decompression, temporary array construction, or per-codepoint
allocation. Disabling compact strings increases storage without changing behavior.
The data is shared per class loader and initialized only when classification needs
the non-ASCII/control table, rather than allocated for every terminal.

On Temurin 25.0.3 with compact strings and compressed references, the table retains
29,792 bytes versus 12,576 bytes for the previous range arrays (including table
objects, excluding class metadata). This trades about 16.8 KiB shared heap for
bounded lookup work. `TerminalParserBenchmark` measures warmed parsing with a
prebuilt input and no-op sink; `TerminalParserFirstInputBenchmark` measures the
first input in a fresh JVM, with parser construction outside timing. The normal
Gradle suite keeps its global warmup defaults and excludes that cold benchmark;
run it explicitly from the JMH JAR with `-wi 0 -i 1 -f 15 -bm ss -tu us`. Cold-run
GC-profiler totals also contain harness and class-loading activity and are not
an allocation count for the property table alone.

Regenerate with `tools/generate-unicode-tables.ps1`, then run `spotlessApply` and
the parser tests. `UnicodeClassTest` compares every codepoint against unmodified
Unicode 17 `GraphemeBreakProperty.txt` and `emoji-data.txt` test resources. Update
those pinned resources deliberately when upgrading Unicode; the generator does
not rewrite the test oracle. This verifies classification, not additional UAX #29
segmentation rules beyond those implemented by the segmenter.

---

## 3. The Grapheme Assembler Optimization (`GraphemeAssembler`)

Under standard UAX #29 rules, a cluster boundary cannot be verified until the *next* codepoint arrives. In a terminal emulator, waiting for the next keypress to display the previous character introduces visible echo latency.

The [GraphemeAssembler](../src/main/kotlin/io/github/ketraterm/parser/unicode/GraphemeAssembler.kt) owns assembly for every publication:

1. **Initial publication** uses `writeCodepoint` for a scalar or `writeCluster` for a longer prefix.
2. **Subsequent publication** uses `updatePreviousCluster(codepoints, length)` with the entire retained sequence, including the previously published prefix. Core applies this text to the same cell, preserving its original attributes and owning width, occupied-span, and cursor changes.

`flushForRender` publishes at the end of a read without ending segmentation. Newly
retained continuations are batched until that publication, the next grapheme, or a
structural command. An unfinished UTF-8 scalar does not delay publication of an
already decoded prefix. Unchanged prefixes are never published again. Normal flush
publishes any pending update before clearing context; reset discards pending state.

Both `accept` and `acceptByte` publish at their call boundary. Publication commits
core grid effects; preserving segmentation across calls does not promise identical
placement across calls. See the [streaming placement policy](../../docs/terminal-feature-map.md#streaming-grapheme-placement)
for width changes after publication.

Both array-based calls borrow the parser's reusable buffer only for the synchronous
call. Sinks must consume or copy the used prefix before returning. Core stores its
own copy and never retains the parser array. It does not assemble continuations from
stored text; its separate scratch buffer serves grid-copy operations only.

### Bounded retention

The production parser retains the first **32 Unicode codepoints** of each grapheme,
including its base, in one reusable `IntArray`. This is a terminal resource policy,
not a Unicode grapheme boundary or a byte/UTF-16 limit.

Once the buffer is full, continuing codepoints are discarded without sink writes.
They still update segmentation context, including ZWJ, Hangul, and regional-indicator
state. The next actual boundary starts a new cluster. Filling the buffer never
flushes or resets the active grapheme. Read-boundary publication also preserves
context; explicit termination and reset clear it.

Only retained codepoints reach core and influence width, rendering, and copied text.
For example, a base plus 40 combining marks retains the base and first 31 marks;
the remaining marks create no cells. A variation selector beyond the limit cannot
change the retained prefix's width. UTF-8 recovery and structural controls continue
through their normal parser paths after overflow.

The host adapter forwards these operations. Core owns cluster storage, width, cursor,
and wrapping; its direct cluster-writing API has no new 32-codepoint restriction.
The renderer consumes the retained text through its existing cache and shaping
contracts. No grapheme policy is duplicated in those modules.

## Charset save/restore ownership

Live G0�G3 designations and GL/GR shifts remain parser-owned. DEC/SCO and 1048
save/restore use separate primary/alternate charset slots, selected from the sink�s
actual screen. An effective 1049 entry saves primary state; its exit restores it.
Clearing alternate entry (1047/1049) resets that screen�s saved slot; repeated or
rejected screen requests do not alter slots. Single shifts are transient and are
cleared on restore. Unsaved slots, RIS and DECSTR use ASCII, GL=G0 and GR=G2.

The sink must expose effective screen selection synchronously. Direct host resets
of core should be paired with parser reset, as with other parser-owned state.
