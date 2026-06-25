# Bifurcated Text Rendering Pipeline & Font Fallbacks

Text rendering is the primary CPU hotspot in terminal emulators. To achieve high frame rates and support modern Unicode features, `jvterm-ui-swing` implements a bifurcated rendering engine.

---

## 1. The Rendering Pathways

```
                            Cell Characters
                                   │
                     Is ASCII and identical styling?
                     ├──► YES ──► [ASCII Fast Path]
                     │            (drawChars / drawGlyphVector)
                     │
                     └──► NO ───► Is Box-Drawing or Block?
                                   ├──► YES ──► [Pixel-Perfect Primitives]
                                   │            (Programmatic paint, zero gaps)
                                   │
                                   └──► NO ───► [Complex Shaped Path]
                                                (Shaped TextLayout / Fallbacks)
```

### A. The ASCII Fast Path
* **Batching**: Contiguous columns containing standard ASCII characters (`0x20..0x7E`) that share identical visual attributes (foreground/background, bold, italic) are grouped into a single text run.
* **Emission**: Drawn in a single operation using Java2D `Graphics2D.drawChars` or `drawGlyphVector`. This completely bypasses the heavy text-shaping and layout engines, optimizing rendering times for normal console outputs.

### B. Pixel-Perfect Primitives
* **The Problem**: Font glyphs for box-drawing characters (`U+2500..U+257F`) and block elements (`U+2580..U+259F`) often suffer from anti-aliasing artifacts or rounding gaps at fractional scale factors, causing faint grid lines.
* **The Solution**: The custom painters programmatically draw these elements using absolute pixel lines and fills inside the cell bounds, ensuring continuous lines and solid blocks at any high-DPI scaling factor.

### C. Complex Shaped & Emoji Path
* **`TextLayout` Caching**: Multi-code-unit grapheme clusters (such as combining accents or flag emojis) are shaped using Java2D `TextLayout` objects. The shaped layouts are cached inside a bounded size cache to avoid repeating layout calculations.

---

## 2. Prioritized Font Fallbacks & Emojis

* **Font Fallback Chain**: When the primary monospace font lacks a glyph for a specific codepoint, the engine walks a prioritized list of system fonts to locate a matching glyph.
* **JetBrains Runtime (JBR) Emojis**: The font fallback pipeline detects if it is running on a JetBrains Runtime. It prioritizes system emoji font chains (like *Apple Color Emoji*, *Segoe UI Emoji*, or *Noto Color Emoji*) to render colorful, modern emojis rather than falling back to monochrome system symbols.
