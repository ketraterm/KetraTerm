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
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
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
    @CsvSource("false, false", "false, true", "true, false", "true, true")
    fun `selection text and fill share logical and block bidi geometry`(
        block: Boolean,
        dynamic: Boolean,
    ) {
        val palette =
            basePalette.copyWithColorBehavior(
                if (dynamic) dynamicSelection else fixedSelection,
                selectionForeground = TEST_GREEN,
                selectionBackground = TEST_RED,
            )
        val image = paint(textCells("אבג"), palette, selection = CellSelection(0, 0, 1, 0, isBlock = block))
        val selectedColumn = if (block) 0 else 2

        for (column in 0..2) {
            val selected = column == selectedColumn
            val selectedBackground = if (dynamic) TEST_WHITE else TEST_RED
            val selectedForeground = if (dynamic) TEST_BLACK else TEST_GREEN
            assertEquals(if (selected) selectedBackground else TEST_BLACK, image.getRGB(column * metrics.cellWidth, 0))
            assertTrue(
                image.containsColorInRange(
                    if (selected) selectedForeground else TEST_WHITE,
                    column * metrics.cellWidth,
                    (column + 1) * metrics.cellWidth,
                ),
            )
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
    ): BufferedImage {
        val image = BufferedImage(cells.size * metrics.cellWidth, metrics.cellHeight, BufferedImage.TYPE_INT_ARGB)
        val frame =
            object : TestRenderFrame(arrayOf(cells), cursor) {
                override val palette = palette
            }
        val settings =
            defaultTestSettings().copy { draft ->
                draft.palette = palette
                draft.selectionBackground = TEST_GREEN
                draft.shellIntegrationDecorationGutterWidth = 0
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
            )
        } finally {
            graphics.dispose()
        }
        return image
    }
}
