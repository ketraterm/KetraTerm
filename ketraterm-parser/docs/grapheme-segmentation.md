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

* **Generated Break Tables**: Binary classifications are mapped against a compressed static classification table `GeneratedGraphemeBreakTable` for $O(1)$ property checks.
* **Complex Sequences**: Correctly handles Zero-Width Joiner (ZWJ) emoji sequences, combining mark characters, regional indicator (flag) pairs, and Hangul Jamo sequences.

---

## 3. The Grapheme Assembler Optimization (`GraphemeAssembler`)

Under standard UAX #29 rules, a cluster boundary cannot be verified until the *next* codepoint arrives. In a terminal emulator, waiting for the next keypress to display the previous character introduces visible echo latency.

To resolve this, the [GraphemeAssembler](../src/main/kotlin/io/github/ketraterm/parser/unicode/GraphemeAssembler.kt) provides a bifurcated emission model:

1. **`flushForRender`**: When the current read block ends, the assembler emits the current pending grapheme immediately to the command sink to allow the terminal UI to draw it.
2. **`appendToPreviousCluster`**: If subsequent bytes on a new read loop extend the recently flushed grapheme (e.g. a combining character), the assembler notifies the command sink to append this codepoint to the previous cell instead of creating a new cell.
