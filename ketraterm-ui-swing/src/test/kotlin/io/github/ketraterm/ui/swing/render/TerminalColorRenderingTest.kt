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
package io.github.ketraterm.ui.swing.render

import io.github.ketraterm.render.api.*
import io.github.ketraterm.ui.swing.api.CellSelection
import io.github.ketraterm.ui.swing.search.TerminalSearchViewportHighlights
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingSearchMatchStyle
import io.github.ketraterm.ui.swing.settings.SwingSelectionStyle
import io.github.ketraterm.ui.swing.settings.SwingSettings
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalColorRenderingTest {
    private val metrics = SwingMetrics(12, 24, 16, 18, 10, 0, 1)
    private val basePalette = TerminalColorPalette(defaultForeground = TEST_WHITE, defaultBackground = TEST_BLACK)
    private val fixedSelection = TerminalColorBehavior.OVERRIDE_SELECTION_FOREGROUND or TerminalColorBehavior.OVERRIDE_SELECTION_BACKGROUND
    private val dynamicSelection =
        fixedSelection or TerminalColorBehavior.DYNAMIC_SELECTION_FOREGROUND or TerminalColorBehavior.DYNAMIC_SELECTION_BACKGROUND

    @ParameterizedTest
    @CsvSource("ASCII,false", "ASCII,true", "RTL,false", "RTL,true", "COMBINING,false", "COMBINING,true", "WIDE,false", "WIDE,true")
    fun `normal and active search styles share text geometry across rendering paths`(
        shape: String,
        antialiased: Boolean,
    ) {
        val cells =
            when (shape) {
                "RTL" -> textCells("\u05d0\u05d1\u05d2")
                "COMBINING" -> Array(3) { TestCell(flags = TerminalRenderCellFlags.CLUSTER, cluster = "e\u0301") }
                "WIDE" ->
                    arrayOf(
                        TestCell('A'.code, TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING),
                        TestCell(flags = TerminalRenderCellFlags.WIDE_TRAILING),
                        TestCell(' '.code, TerminalRenderCellFlags.CODEPOINT),
                        TestCell('A'.code, TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING),
                        TestCell(flags = TerminalRenderCellFlags.WIDE_TRAILING),
                    )
                else -> textCells("AAA")
            }
        val span = if (shape == "WIDE") 2 else 1
        val highlights =
            TerminalSearchViewportHighlights().apply {
                reset(1)
                add(0, 0, span, false)
                add(0, cells.size - span, cells.size, true)
                finish()
            }
        val actual =
            paint(cells, basePalette, antialiased = antialiased, searchHighlights = highlights) {
                it.searchMatchStyle = SwingSearchMatchStyle(TEST_BLUE, TEST_RED)
                it.searchActiveMatchStyle = SwingSearchMatchStyle(TEST_GREEN, TEST_BLACK)
            }
        val referenceCells =
            Array(cells.size) { column ->
                val foreground = if (column < span) TEST_RED else TEST_BLACK
                val background = if (column < span) TEST_BLUE else TEST_GREEN
                if (column in span until cells.size - span) {
                    cells[column]
                } else {
                    cells[column].copy(
                        attr =
                            TerminalRenderAttrs.pack(
                                foregroundKind = TerminalRenderColorKind.RGB,
                                foregroundValue = foreground and 0xffffff,
                                backgroundKind = TerminalRenderColorKind.RGB,
                                backgroundValue = background and 0xffffff,
                            ),
                    )
                }
            }
        val expected = paint(referenceCells, basePalette, antialiased = antialiased)
        for (y in 0 until actual.height) {
            for (x in 0 until actual.width) {
                assertEquals(expected.getRGB(x, y), actual.getRGB(x, y), "Pixel ($x, $y), $shape")
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `selection and block cursor paint over search borders`(cursor: Boolean) {
        val highlights =
            TerminalSearchViewportHighlights().apply {
                reset(1)
                add(0, 0, 1, true)
                finish()
            }
        val image =
            paint(
                textCells(" "),
                basePalette.copy(cursorBackground = TEST_BLUE, cursorForeground = TEST_RED),
                selection = if (cursor) null else CellSelection(0, 0, 1, 0),
                selectionStyle = SwingSelectionStyle(TEST_GREEN),
                cursor = TerminalRenderCursor(0, 0, cursor, false, TerminalRenderCursorShape.BLOCK, 1),
                searchHighlights = highlights,
            ) { it.searchActiveMatchStyle = SwingSearchMatchStyle(TEST_BLACK, TEST_WHITE, TEST_RED) }
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                assertEquals(if (cursor) TEST_BLUE else TEST_GREEN, image.getRGB(x, y), "Pixel ($x, $y)")
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `focused and unfocused selection independently preserve or replace text`(focused: Boolean) {
        val image =
            paint(
                textCells("A"),
                basePalette,
                CellSelection(0, 0, 1, 0),
                focused = focused,
                selectionStyle = SwingSelectionStyle(TEST_BLUE, TEST_GREEN),
                inactiveStyle = SwingSelectionStyle(TEST_RED),
            )
        assertEquals(if (focused) TEST_BLUE else TEST_RED, image.getRGB(0, 0))
        assertTrue(image.containsColor(if (focused) TEST_GREEN else TEST_WHITE))
    }

    @Test
    fun `missing inactive style inherits active selection and zero alpha keeps cell fill`() {
        val image =
            paint(
                textCells("A"),
                basePalette,
                CellSelection(0, 0, 1, 0),
                focused = false,
                selectionStyle = SwingSelectionStyle(0, TEST_GREEN),
            )
        assertEquals(TEST_BLACK, image.getRGB(0, 0))
        assertTrue(image.containsColor(TEST_GREEN))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `host reverse selection uses the leading wide cell for either selected half`(trailing: Boolean) {
        val image =
            paint(
                wideCells(),
                basePalette,
                CellSelection(if (trailing) 1 else 0, 0, if (trailing) 2 else 1, 0),
                selectionStyle = SwingSelectionStyle(reverseVideo = true),
            )
        assertEquals(TEST_RED, image.getRGB((if (trailing) 1 else 0) * metrics.cellWidth, 0))
        if (!trailing) assertTrue(image.containsColor(TEST_GREEN))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `application role overrides take precedence over either host style`(focused: Boolean) {
        val palette = basePalette.copyWithColorBehavior(fixedSelection, selectionForeground = TEST_GREEN, selectionBackground = TEST_RED)
        val image =
            paint(
                textCells("A"),
                palette,
                CellSelection(0, 0, 1, 0),
                focused = focused,
                selectionStyle = SwingSelectionStyle(TEST_BLUE, TEST_WHITE),
                inactiveStyle = SwingSelectionStyle(reverseVideo = true),
            )
        assertEquals(TEST_RED, image.getRGB(0, 0))
        assertTrue(image.containsColor(TEST_GREEN))
    }

    @Test
    fun `host selection appearance remains unchanged without application overrides`() {
        val palette = basePalette.copy(selectionForeground = TEST_RED, selectionBackground = TEST_BLUE)
        val image = paint(textCells("A"), palette, selection = CellSelection(0, 0, 1, 0))

        assertEquals(TEST_GREEN, image.getRGB(0, 0))
        assertTrue(image.containsColor(TEST_WHITE))
        assertFalse(image.containsColor(TEST_RED))
    }

    @Test
    fun `explicit selection colors replace host overlay and split an ASCII run at both boundaries`() {
        val palette = basePalette.copyWithColorBehavior(fixedSelection, selectionForeground = TEST_GREEN, selectionBackground = TEST_RED)
        val image = paint(textCells("AAA"), palette, selection = CellSelection(1, 0, 2, 0))

        for (column in 0..2) {
            val selected = column == 1
            assertEquals(if (selected) TEST_RED else TEST_BLACK, image.getRGB(column * metrics.cellWidth, 0))
            assertTrue(
                image.containsColorInRange(
                    if (selected) TEST_GREEN else TEST_WHITE,
                    column * metrics.cellWidth,
                    (column + 1) * metrics.cellWidth,
                ),
            )
            if (!selected) assertFalse(image.containsColorInRange(TEST_GREEN, column * metrics.cellWidth, (column + 1) * metrics.cellWidth))
        }
    }

    @Test
    fun `dynamic selection inverts each original cell including inverse attributes`() {
        val normal =
            TerminalRenderAttrs.pack(
                foregroundKind = TerminalRenderColorKind.RGB,
                foregroundValue = TEST_BLUE and 0xffffff,
                backgroundKind = TerminalRenderColorKind.RGB,
                backgroundValue =
                    TEST_RED and 0xffffff,
            )
        val inverse =
            TerminalRenderAttrs.pack(
                foregroundKind = TerminalRenderColorKind.RGB,
                foregroundValue = TEST_BLUE and 0xffffff,
                backgroundKind = TerminalRenderColorKind.RGB,
                backgroundValue =
                    TEST_RED and 0xffffff,
                inverse = true,
            )
        val cells =
            arrayOf(
                TestCell('A'.code, TerminalRenderCellFlags.CODEPOINT, normal),
                TestCell('A'.code, TerminalRenderCellFlags.CODEPOINT, inverse),
            )
        val image = paint(cells, basePalette.copyWithColorBehavior(dynamicSelection), selection = CellSelection(0, 0, 2, 0))

        assertEquals(TEST_BLUE, image.getRGB(0, 0))
        assertTrue(image.containsColorInRange(TEST_RED, 0, metrics.cellWidth))
        assertEquals(TEST_RED, image.getRGB(metrics.cellWidth, 0))
        assertTrue(image.containsColorInRange(TEST_BLUE, metrics.cellWidth, metrics.cellWidth * 2))
    }

    @ParameterizedTest
    @CsvSource(
        "false, false, false",
        "false, false, true",
        "false, true, false",
        "false, true, true",
        "true, false, false",
        "true, false, true",
        "true, true, false",
        "true, true, true",
    )
    fun `selection text and fill share logical and block bidi geometry`(
        block: Boolean,
        dynamic: Boolean,
        antialiased: Boolean,
    ) {
        val palette =
            basePalette.copyWithColorBehavior(
                if (dynamic) dynamicSelection else fixedSelection,
                selectionForeground = TEST_GREEN,
                selectionBackground = TEST_BLUE,
            )
        val cells = textCells("אבג")
        val image = paint(cells, palette, selection = CellSelection(0, 0, 1, 0, isBlock = block), antialiased = antialiased)
        val selectedColumn = if (block) 0 else 2
        val selectedBackground = if (dynamic) TEST_WHITE else TEST_BLUE
        val selectedForeground = if (dynamic) TEST_BLACK else TEST_GREEN

        // Compare with explicit cell colors: platform glyphs need not contain a fully covered foreground pixel.
        // Green on blue also keeps the reference text above the ordinary text contrast threshold.
        val selectedLogicalColumn = if (block) 2 else 0
        val expectedCells = cells.copyOf()
        expectedCells[selectedLogicalColumn] =
            cells[selectedLogicalColumn].copy(
                attr =
                    TerminalRenderAttrs.pack(
                        foregroundKind = TerminalRenderColorKind.RGB,
                        foregroundValue = selectedForeground and 0xffffff,
                        backgroundKind = TerminalRenderColorKind.RGB,
                        backgroundValue = selectedBackground and 0xffffff,
                    ),
            )
        val expected = paint(expectedCells, basePalette, antialiased = antialiased)

        for (column in 0..2) {
            val selected = column == selectedColumn
            val background = if (selected) selectedBackground else TEST_BLACK
            assertEquals(background, image.getRGB(column * metrics.cellWidth, 0), "Background at visual column $column")
            var hasGlyphCoverage = false
            for (y in 0 until image.height) {
                for (x in column * metrics.cellWidth until (column + 1) * metrics.cellWidth) {
                    val pixel = image.getRGB(x, y)
                    assertEquals(expected.getRGB(x, y), pixel, "Pixel ($x, $y), visual column $column")
                    if (pixel != background) hasGlyphCoverage = true
                }
            }
            assertTrue(hasGlyphCoverage, "Visible glyph at visual column $column")
        }
    }

    @Test
    fun `dynamic cursor honors cell inverse before swapping its text and fill`() {
        val attr =
            TerminalRenderAttrs.pack(
                foregroundKind = TerminalRenderColorKind.RGB,
                foregroundValue = TEST_RED and 0xffffff,
                backgroundKind = TerminalRenderColorKind.RGB,
                backgroundValue =
                    TEST_GREEN and 0xffffff,
                inverse = true,
            )
        val palette =
            basePalette.copyWithColorBehavior(
                TerminalColorBehavior.DYNAMIC_CURSOR_BACKGROUND or TerminalColorBehavior.DYNAMIC_CURSOR_FOREGROUND,
            )
        val image =
            paint(
                arrayOf(TestCell('A'.code, TerminalRenderCellFlags.CODEPOINT, attr)),
                palette,
                cursor = TerminalRenderCursor(0, 0, true, false, TerminalRenderCursorShape.BLOCK, 1),
            )

        assertEquals(TEST_GREEN, image.getRGB(0, 0))
        assertTrue(image.containsColor(TEST_RED))
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1])
    fun `dynamic selection includes both halves of a wide cell using its leading attributes`(column: Int) {
        val image =
            paint(wideCells(), basePalette.copyWithColorBehavior(dynamicSelection), selection = CellSelection(column, 0, column + 1, 0))

        assertEquals(TEST_RED, image.getRGB(0, 0))
        assertEquals(TEST_RED, image.getRGB(metrics.cellWidth, 0))
        assertTrue(image.containsColor(TEST_GREEN))
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1])
    fun `dynamic block cursor resolves both channels from wide leading cell before selection`(column: Int) {
        val flags = dynamicSelection or TerminalColorBehavior.DYNAMIC_CURSOR_BACKGROUND or TerminalColorBehavior.DYNAMIC_CURSOR_FOREGROUND
        val image =
            paint(
                wideCells(),
                basePalette.copyWithColorBehavior(flags),
                selection = CellSelection(0, 0, 2, 0),
                cursor = TerminalRenderCursor(column, 0, true, false, TerminalRenderCursorShape.BLOCK, 1),
            )

        assertEquals(TEST_RED, image.getRGB(0, 0))
        assertEquals(TEST_RED, image.getRGB(metrics.cellWidth, 0))
        assertTrue(image.containsColor(TEST_GREEN))
        assertFalse(image.containsColor(TEST_BLUE))
    }

    @ParameterizedTest
    @EnumSource(TerminalRenderCursorShape::class)
    fun `dynamic cursor color applies to all unfocused shapes`(shape: TerminalRenderCursorShape) {
        val palette = basePalette.copyWithColorBehavior(TerminalColorBehavior.DYNAMIC_CURSOR_BACKGROUND, defaultForeground = TEST_RED)
        val image = paint(textCells(" "), palette, cursor = TerminalRenderCursor(0, 0, true, false, shape, 1), focused = false)
        val y = if (shape == TerminalRenderCursorShape.UNDERLINE) metrics.cellHeight - 1 else 0

        assertEquals(TEST_RED, image.getRGB(0, y))
        assertEquals(TEST_BLACK, image.getRGB(metrics.cellWidth / 2, metrics.cellHeight / 2))
    }

    private fun wideCells(): Array<TestCell> =
        arrayOf(
            TestCell(
                'A'.code,
                TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING,
                TerminalRenderAttrs.pack(
                    foregroundKind = TerminalRenderColorKind.RGB,
                    foregroundValue =
                        TEST_RED and 0xffffff,
                    backgroundKind = TerminalRenderColorKind.RGB,
                    backgroundValue = TEST_GREEN and 0xffffff,
                ),
            ),
            TestCell(
                flags = TerminalRenderCellFlags.WIDE_TRAILING,
                attr =
                    TerminalRenderAttrs.pack(
                        foregroundKind = TerminalRenderColorKind.RGB,
                        foregroundValue =
                            TEST_BLUE and 0xffffff,
                    ),
            ),
        )

    private fun textCells(text: String): Array<TestCell> = Array(text.length) { TestCell(text[it].code, TerminalRenderCellFlags.CODEPOINT) }

    private fun paint(
        cells: Array<TestCell>,
        palette: TerminalColorPalette,
        selection: CellSelection? = null,
        cursor: TerminalRenderCursor = TerminalRenderCursor(0, 0, false, false, TerminalRenderCursorShape.BLOCK, 1),
        focused: Boolean = true,
        antialiased: Boolean = false,
        selectionStyle: SwingSelectionStyle = SwingSelectionStyle(TEST_GREEN),
        inactiveStyle: SwingSelectionStyle? = null,
        searchHighlights: TerminalSearchViewportHighlights? = null,
        configure: (SwingSettings.Builder) -> Unit = {},
    ): BufferedImage {
        val image = BufferedImage(cells.size * metrics.cellWidth, metrics.cellHeight, BufferedImage.TYPE_INT_ARGB)
        val frame =
            object : TestRenderFrame(arrayOf(cells), cursor) {
                override val palette = palette
            }
        val settings =
            defaultTestSettings().copy { draft ->
                draft.palette = palette
                draft.selectionStyle = selectionStyle
                draft.inactiveSelectionStyle = inactiveStyle
                draft.shellIntegrationDecorationGutterWidth = 0
                draft.textAntialiasing =
                    if (antialiased) RenderingHints.VALUE_TEXT_ANTIALIAS_ON else RenderingHints.VALUE_TEXT_ANTIALIAS_OFF
                configure(draft)
            }
        val graphics = image.createGraphics()
        try {
            GridPainter().paint(
                graphics,
                renderCache(frame),
                settings,
                metrics,
                image.width,
                image.height,
                true,
                terminalFocused = focused,
                selection = selection,
                searchHighlights = searchHighlights,
            )
        } finally {
            graphics.dispose()
        }
        return image
    }
}
