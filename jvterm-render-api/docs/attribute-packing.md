# Stable Public Render Attribute Packing

To minimize allocation overhead and maintain a JIT-friendly performance profile, cell attributes in the **JvTerm Terminal** are packed into 64-bit primitive `Long` values.

---

## 1. Primary Attribute Word (`TerminalRenderAttrs`)

The primary `Long` attribute word stores the foreground/background color kinds and values, along with standard text decorations and intensity styles.

### Bit Layout Mapping

```
+-------------------------------------------------------+
| Bit Range   | Usage                                   |
+-------------+-----------------------------------------+
| bits 0..1   | Foreground Color Kind                   |
| bits 2..25  | Foreground Color Value                  |
| bits 26..27 | Background Color Kind                   |
| bits 28..51 | Background Color Value                  |
| bit 52      | Bold intensity flag                     |
| bit 53      | Faint intensity flag                    |
| bit 54      | Italic style flag                       |
| bits 55..57 | Underline style enum                    |
| bit 58      | Blink text flag                         |
| bit 59      | Inverse video flag                      |
| bit 60      | Invisible text flag                     |
| bit 61      | Strikethrough decoration flag           |
| bits 62..63 | Reserved (currently zero)               |
+-------------------------------------------------------+
```

### Color Encoding Rules
* **Color Kind (2 bits)**:
  * `0`: Default color (underlying theme default).
  * `1`: 8-bit indexed color (`0..255`).
  * `2`: 24-bit TrueColor RGB.
* **Color Value (24 bits)**:
  * For Default color: set to `0`.
  * For Indexed color: values `0..255` are mapped into the lower 8 bits.
  * For RGB color: packed as `0xRRGGBB`.

### Underline Styles (3 bits)
Underline styles map to the following integer values defined in `TerminalRenderUnderline`:
* `0`: `NONE`
* `1`: `SINGLE`
* `2`: `DOUBLE`
* `3`: `CURLY`
* `4`: `DOTTED`
* `5`: `DASHED`

---

## 2. Extra Attribute Word (`TerminalRenderExtraAttrs`)

For less common attributes, an optional `Long` extra-attribute word is used. Renderers that do not require these decorations can pass `null` or omit the extra array allocations.

### Bit Layout Mapping

```
+-------------------------------------------------------+
| Bit Range   | Usage                                   |
+-------------+-----------------------------------------+
| bits 0..1   | Underline Color Kind                    |
| bits 2..25  | Underline Color Value                   |
| bit 26      | Overline decoration flag                |
| bits 27..63 | Reserved (currently zero)               |
+-------------------------------------------------------+
```

---

## 3. Usage & Access

Instead of writing manual bit shifts, consumers should always use the decoder helpers:

```kotlin
import io.github.jvterm.render.api.TerminalRenderAttrs

fun drawCell(x: Int, y: Int, attrWord: Long) {
    val fgKind = TerminalRenderAttrs.foregroundKind(attrWord)
    val fgVal = TerminalRenderAttrs.foregroundValue(attrWord)
    
    val isBold = TerminalRenderAttrs.isBold(attrWord)
    val underline = TerminalRenderAttrs.underlineStyle(attrWord)
    // Render using properties...
}
```
