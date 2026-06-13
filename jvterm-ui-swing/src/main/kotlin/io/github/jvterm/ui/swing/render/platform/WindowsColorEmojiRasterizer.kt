/*
 * Copyright 2026 Gagik Sargsyan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.jvterm.ui.swing.render.platform

import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path

/**
 * Rasterizes Windows emoji from the installed Segoe UI Emoji COLR/CPAL tables.
 */
internal class WindowsColorEmojiRasterizer private constructor(
    private val font: Font,
    private val colorFont: WindowsColorEmojiFont,
) : TerminalPlatformEmojiRasterizer {
    override val available: Boolean = true

    override fun rasterize(
        text: String,
        pixelSize: Int,
    ): BufferedImage? {
        if (text.isEmpty() || pixelSize <= 0) return null

        val sizedFont = font.deriveFont(pixelSize.toFloat())
        val chars = text.toCharArray()
        val baseVector =
            sizedFont.layoutGlyphVector(
                FONT_RENDER_CONTEXT,
                chars,
                0,
                chars.size,
                Font.LAYOUT_LEFT_TO_RIGHT,
            )
        if (baseVector.numGlyphs == 0) return null

        val bounds = baseVector.visualBounds
        if (bounds.isEmpty) return null

        val image = BufferedImage(pixelSize, pixelSize, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        return try {
            configureGraphics(g)
            val originX = ((pixelSize - bounds.width) / 2.0 - bounds.x).toFloat()
            val originY = ((pixelSize - bounds.height) / 2.0 - bounds.y).toFloat()
            var painted = false
            var glyphIndex = 0
            while (glyphIndex < baseVector.numGlyphs) {
                val glyphCode = baseVector.getGlyphCode(glyphIndex)
                val layers = colorFont.layers(glyphCode)
                if (layers != null) {
                    val position = baseVector.getGlyphPosition(glyphIndex)
                    var layerIndex = 0
                    while (layerIndex < layers.size) {
                        val layer = layers[layerIndex]
                        val layerVector = sizedFont.createGlyphVector(FONT_RENDER_CONTEXT, intArrayOf(layer.glyphId))
                        g.color = Color(layer.argb, true)
                        g.drawGlyphVector(
                            layerVector,
                            originX + position.x.toFloat(),
                            originY + position.y.toFloat(),
                        )
                        painted = true
                        layerIndex++
                    }
                }
                glyphIndex++
            }
            if (painted && image.hasVisiblePixel()) image else null
        } finally {
            g.dispose()
        }
    }

    companion object {
        fun create(): TerminalPlatformEmojiRasterizer {
            return try {
                val fontPath = segoeEmojiFontPath() ?: return Unavailable
                val font = Font.createFont(Font.TRUETYPE_FONT, fontPath.toFile())
                val colorFont = WindowsColorEmojiFont.load(fontPath) ?: return Unavailable
                WindowsColorEmojiRasterizer(font, colorFont)
            } catch (_: Throwable) {
                Unavailable
            }
        }

        private fun segoeEmojiFontPath(): Path? {
            val windowsDirectory =
                System.getenv("SystemRoot")
                    ?: System.getenv("windir")
                    ?: "C:\\Windows"
            val path = Path.of(windowsDirectory, "Fonts", "seguiemj.ttf")
            return if (Files.isRegularFile(path)) path else null
        }

        private fun configureGraphics(g: Graphics2D) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        }

        private object Unavailable : TerminalPlatformEmojiRasterizer {
            override val available: Boolean = false

            override fun rasterize(
                text: String,
                pixelSize: Int,
            ): BufferedImage? = null
        }

        private val FONT_RENDER_CONTEXT = FontRenderContext(null, true, true)
    }
}

private class WindowsColorEmojiFont(
    private val layerMap: Map<Int, IntArray>,
) {
    fun layers(glyphId: Int): Array<ColorGlyphLayer>? {
        val encoded = layerMap[glyphId] ?: return null
        return Array(encoded.size / LAYER_WORDS) { index ->
            val offset = index * LAYER_WORDS
            ColorGlyphLayer(glyphId = encoded[offset], argb = encoded[offset + 1])
        }
    }

    companion object {
        fun load(fontPath: Path): WindowsColorEmojiFont? {
            val reader = OpenTypeReader(Files.readAllBytes(fontPath))
            val colr = reader.table("COLR") ?: return null
            val cpal = reader.table("CPAL") ?: return null
            val colors = parsePalette(reader, cpal) ?: return null
            val layers = parseLayers(reader, colr, colors)
            return if (layers.isEmpty()) null else WindowsColorEmojiFont(layers)
        }

        private fun parsePalette(
            reader: OpenTypeReader,
            cpal: TableRange,
        ): IntArray? {
            if (cpal.length < CPAL_MIN_LENGTH) return null
            val numPaletteEntries = reader.u16(cpal.offset + 2)
            val numPalettes = reader.u16(cpal.offset + 4)
            val numColorRecords = reader.u16(cpal.offset + 6)
            val colorRecordsOffset = reader.u32(cpal.offset + 8)
            if (numPaletteEntries == 0 || numPalettes == 0) return null
            val paletteIndexOffset = cpal.offset + 12
            val firstPaletteColorIndex = reader.u16(paletteIndexOffset)
            if (firstPaletteColorIndex + numPaletteEntries > numColorRecords) return null

            val colors = IntArray(numPaletteEntries)
            var index = 0
            while (index < numPaletteEntries) {
                val recordOffset = cpal.offset + colorRecordsOffset + (firstPaletteColorIndex + index) * COLOR_RECORD_SIZE
                if (!reader.hasRange(recordOffset, COLOR_RECORD_SIZE)) return null
                val blue = reader.u8(recordOffset)
                val green = reader.u8(recordOffset + 1)
                val red = reader.u8(recordOffset + 2)
                val alpha = reader.u8(recordOffset + 3)
                colors[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                index++
            }
            return colors
        }

        private fun parseLayers(
            reader: OpenTypeReader,
            colr: TableRange,
            colors: IntArray,
        ): Map<Int, IntArray> {
            if (colr.length < COLR_V0_HEADER_LENGTH) return emptyMap()
            val numBaseGlyphRecords = reader.u16(colr.offset + 2)
            val baseGlyphRecordsOffset = reader.u32(colr.offset + 4)
            val layerRecordsOffset = reader.u32(colr.offset + 8)
            val numLayerRecords = reader.u16(colr.offset + 12)
            val baseRecordsStart = colr.offset + baseGlyphRecordsOffset
            val layerRecordsStart = colr.offset + layerRecordsOffset
            if (!reader.hasRange(baseRecordsStart, numBaseGlyphRecords * BASE_GLYPH_RECORD_SIZE)) return emptyMap()
            if (!reader.hasRange(layerRecordsStart, numLayerRecords * LAYER_RECORD_SIZE)) return emptyMap()

            val result = HashMap<Int, IntArray>(numBaseGlyphRecords)
            var baseIndex = 0
            while (baseIndex < numBaseGlyphRecords) {
                val recordOffset = baseRecordsStart + baseIndex * BASE_GLYPH_RECORD_SIZE
                val glyphId = reader.u16(recordOffset)
                val firstLayerIndex = reader.u16(recordOffset + 2)
                val layerCount = reader.u16(recordOffset + 4)
                if (firstLayerIndex + layerCount <= numLayerRecords) {
                    val encodedLayers = IntArray(layerCount * LAYER_WORDS)
                    var layerIndex = 0
                    while (layerIndex < layerCount) {
                        val layerRecordOffset = layerRecordsStart + (firstLayerIndex + layerIndex) * LAYER_RECORD_SIZE
                        val layerGlyphId = reader.u16(layerRecordOffset)
                        val paletteIndex = reader.u16(layerRecordOffset + 2)
                        encodedLayers[layerIndex * LAYER_WORDS] = layerGlyphId
                        encodedLayers[layerIndex * LAYER_WORDS + 1] = paletteColor(colors, paletteIndex)
                        layerIndex++
                    }
                    result[glyphId] = encodedLayers
                }
                baseIndex++
            }
            return result
        }

        private fun paletteColor(
            colors: IntArray,
            paletteIndex: Int,
        ): Int =
            if (paletteIndex == FOREGROUND_PALETTE_INDEX || paletteIndex !in colors.indices) {
                FOREGROUND_ARGB
            } else {
                colors[paletteIndex]
            }

        private const val CPAL_MIN_LENGTH = 12
        private const val COLR_V0_HEADER_LENGTH = 14
        private const val COLOR_RECORD_SIZE = 4
        private const val BASE_GLYPH_RECORD_SIZE = 6
        private const val LAYER_RECORD_SIZE = 4
        private const val LAYER_WORDS = 2
        private const val FOREGROUND_PALETTE_INDEX = 0xFFFF
        private const val FOREGROUND_ARGB = 0xFFFFFFFF.toInt()
    }
}

private data class ColorGlyphLayer(
    val glyphId: Int,
    val argb: Int,
)

private class OpenTypeReader(
    private val bytes: ByteArray,
) {
    private val tables: Map<String, TableRange> = readTables()

    fun table(tag: String): TableRange? = tables[tag]

    fun hasRange(
        offset: Int,
        length: Int,
    ): Boolean = offset >= 0 && length >= 0 && offset <= bytes.size - length

    fun u8(offset: Int): Int = bytes[offset].toInt() and 0xFF

    fun u16(offset: Int): Int = (u8(offset) shl 8) or u8(offset + 1)

    fun u32(offset: Int): Int {
        val value =
            (u8(offset).toLong() shl 24) or
                (u8(offset + 1).toLong() shl 16) or
                (u8(offset + 2).toLong() shl 8) or
                u8(offset + 3).toLong()
        return if (value <= Int.MAX_VALUE) value.toInt() else -1
    }

    private fun readTables(): Map<String, TableRange> {
        if (!hasRange(4, 2)) return emptyMap()
        val numTables = u16(4)
        val recordsOffset = 12
        if (!hasRange(recordsOffset, numTables * TABLE_RECORD_SIZE)) return emptyMap()

        val result = HashMap<String, TableRange>(numTables)
        var index = 0
        while (index < numTables) {
            val recordOffset = recordsOffset + index * TABLE_RECORD_SIZE
            val tag =
                String(
                    byteArrayOf(
                        bytes[recordOffset],
                        bytes[recordOffset + 1],
                        bytes[recordOffset + 2],
                        bytes[recordOffset + 3],
                    ),
                    Charsets.US_ASCII,
                )
            val offset = u32(recordOffset + 8)
            val length = u32(recordOffset + 12)
            if (hasRange(offset, length)) {
                result[tag] = TableRange(offset, length)
            }
            index++
        }
        return result
    }

    private companion object {
        private const val TABLE_RECORD_SIZE = 16
    }
}

private data class TableRange(
    val offset: Int,
    val length: Int,
)

private fun BufferedImage.hasVisiblePixel(): Boolean {
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            if ((getRGB(x, y) ushr 24) != 0) return true
            x++
        }
        y++
    }
    return false
}
