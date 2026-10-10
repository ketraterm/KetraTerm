# Stable Public Render Attribute Packing

Render attributes use primitive `Long` words. This public encoding is independent
of core's private storage layout. Consumers should use `TerminalRenderAttrs` and
`TerminalRenderExtraAttrs` helpers rather than copy bit arithmetic into renderers.

## 1. Primary Attribute Word (`TerminalRenderAttrs`)

### Bit Layout Mapping

| Bits | Meaning |
| --- | --- |
| 0..1 | Foreground color kind |
| 2..25 | Foreground color value |
| 26..27 | Background color kind |
| 28..51 | Background color value |
| 52 | Bold |
| 53 | Faint |
| 54 | Italic |
| 55..57 | Underline style |
| 58 | Blink |
| 59 | Inverse video |
| 60 | Invisible text |
| 61 | Strikethrough |
| 62..63 | Reserved; currently zero |

`TerminalRenderAttrs.DEFAULT` is zero: default colors and no styles.

### Color Encoding Rules

| `TerminalRenderColorKind` | Kind value | Valid color value |
| --- | --- | --- |
| `DEFAULT` | 0 | Exactly 0 |
| `INDEXED` | 1 | `0..255` |
| `RGB` | 2 | `0..0xFFFFFF`, encoded as `0xRRGGBB` |

Kind 3 is not defined. Pack helpers reject unsupported kinds and out-of-range
values with `IllegalArgumentException`. Decoder helpers extract fields without
validating an arbitrary input word; use pack helpers when constructing words.

### Underline Styles (3 bits)

| `TerminalRenderUnderline` | Value |
| --- | --- |
| `NONE` | 0 |
| `SINGLE` | 1 |
| `DOUBLE` | 2 |
| `CURLY` | 3 |
| `DOTTED` | 4 |
| `DASHED` | 5 |

`TerminalRenderAttrs.pack` rejects other underline-style values.

## 2. Extra Attribute Word (`TerminalRenderExtraAttrs`)

### Bit Layout Mapping

| Bits | Meaning |
| --- | --- |
| 0..1 | Underline color kind |
| 2..25 | Underline color value |
| 26 | Overline |
| 27..63 | Reserved; currently zero |

`TerminalRenderExtraAttrs.DEFAULT` is zero. Underline colors use the same kinds
and validation as primary colors. Consumers that do not need this channel can
omit `extraAttrWords` in `copyLine`.

## 3. Usage & Access

Construct an attribute word and resolve its foreground color:

```kotlin
import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.render.api.TerminalRenderAttrs
import io.github.ketraterm.render.api.TerminalRenderColorKind
import io.github.ketraterm.render.api.TerminalRenderUnderline

fun main() {
    val word = TerminalRenderAttrs.pack(
        foregroundKind = TerminalRenderColorKind.INDEXED,
        foregroundValue = 2,
        bold = true,
        underlineStyle = TerminalRenderUnderline.SINGLE,
    )
    val palette = TerminalColorPalette()
    check(TerminalRenderAttrs.isBold(word))
    check(palette.foreground(word) == palette.indexedColor(10))
}
```

`TerminalColorPalette.foreground` and `background` resolve default, indexed, and
RGB colors to ARGB. They apply inverse and invisible attributes. With
`boldAsBright`, bold indexed foregrounds `0..7` resolve through entries `8..15`.
Faint does not change the resolved palette color; renderers decide how to present
that style. Underline, overline, and blink presentation also belong to renderers.

A palette requires exactly 256 indexed colors, defensively copies the supplied
array, and offers caller-owned copies through `copyIndexedColorsInto` or
`toIndexedColorsArray`. Its `isDark` preference is host-declared, independent of
individual color values.

## Cell flags

`TerminalRenderCellFlags` is a separate `Int` bit set. The valid combinations are:

| Combination | Interpretation |
| --- | --- |
| `EMPTY` | No glyph; still has cell attributes. |
| `EMPTY or WRAP_PADDING` | Artificial final-column blank before a wide glyph wraps. |
| `CODEPOINT` | `codeWords` holds a Unicode scalar value. |
| `CODEPOINT or WIDE_LEADING` | Scalar glyph occupies this and the next column. |
| `CLUSTER` | Grapheme delivered through a cluster sink. |
| `CLUSTER or WIDE_LEADING` | Cluster occupies this and the next column. |
| `WIDE_TRAILING` | Continuation column; do not draw another glyph. |

`isValidCombination` validates the bit combination, not its position in a row.
`WRAP_PADDING` is valid only at the last column of a row whose `lineWrapped` is
true. Logical text extraction omits that padding when joining wrapped rows;
painting and rectangular selection preserve its physical geometry.

The bit values are `EMPTY=1`, `CODEPOINT=2`, `CLUSTER=4`, `WIDE_LEADING=8`,
`WIDE_TRAILING=16`, and `WRAP_PADDING=32`. Prefer the named constants.
