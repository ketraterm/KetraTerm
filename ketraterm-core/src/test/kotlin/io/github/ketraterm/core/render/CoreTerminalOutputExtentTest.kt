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
package io.github.ketraterm.core.render

import io.github.ketraterm.core.buffer.DefaultTerminalBuffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CoreTerminalOutputExtentTest {
    @Test
    fun `cursor movement and erase on untouched rows do not author output`() {
        val buffer = DefaultTerminalBuffer(8, 4)
        buffer.assertOutputEnd(0)
        buffer.positionCursor(7, 3)
        buffer.eraseCurrentLine()
        buffer.eraseLineToCursor()
        buffer.eraseLineToEnd()
        buffer.eraseCharacters(3)
        buffer.selectiveEraseCurrentLine()
        buffer.eraseScreenToCursor()
        buffer.eraseScreenToEnd()
        buffer.eraseEntireScreen()
        buffer.assertOutputEnd(0)
    }

    @Test
    fun `explicit blank line feeds author only their source and publish content changes`() {
        val buffer = DefaultTerminalBuffer(8, 4)
        var generation = 0L
        buffer.readRenderFrame { generation = it.contentGeneration }
        buffer.newLine()
        buffer.readRenderFrame {
            assertEquals(1L, it.outputEndAbsoluteRow)
            assertNotEquals(generation, it.contentGeneration)
            generation = it.contentGeneration
        }
        buffer.newLine()
        buffer.readRenderFrame {
            assertEquals(2L, it.outputEndAbsoluteRow)
            assertNotEquals(generation, it.contentGeneration)
            generation = it.contentGeneration
        }
        buffer.positionCursor(0, 0)
        buffer.newLine()
        buffer.readRenderFrame {
            assertEquals(2L, it.outputEndAbsoluteRow)
            assertEquals(generation, it.contentGeneration, "Revisiting authored output changes only the cursor")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["x", " ", "界", "🧪"])
    fun `text and explicit spaces author their destination row`(text: String) {
        val buffer = DefaultTerminalBuffer(8, 4)
        buffer.positionCursor(0, 2)
        buffer.writeText(text)
        buffer.assertOutputEnd(3)
        buffer.positionCursor(7, 3)
        buffer.assertOutputEnd(3)
    }

    @Test
    fun `wide wrapped clusters author complete output while preserving padding`() {
        val buffer = DefaultTerminalBuffer(4, 4)
        buffer.writeText("abc")
        buffer.writeCluster(intArrayOf(0x1F469, 0x200D, 0x1F4BB), length = 3)
        buffer.assertOutputEnd(2)
        buffer.resize(8, 4)
        buffer.assertOutputEnd(1)
        buffer.readRenderFrame { assertEquals(0, it.cursor.row) }
    }

    @Test
    fun `partial erasure and EL2 preserve an authored blank while display replacement resets it`() {
        val buffer = DefaultTerminalBuffer(8, 4)
        buffer.positionCursor(0, 2)
        buffer.writeText("text")
        buffer.positionCursor(0, 2)
        buffer.eraseLineToEnd()
        buffer.assertOutputEnd(3)
        buffer.eraseCurrentLine()
        buffer.assertOutputEnd(3)
        buffer.eraseEntireScreen()
        buffer.assertOutputEnd(0)
        buffer.writeText("new")
        buffer.assertOutputEnd(3)
        buffer.reset()
        buffer.assertOutputEnd(0)
    }

    @Test
    fun `ED0 replaces erased tail rows without extending output into untouched rows`() {
        val buffer = DefaultTerminalBuffer(8, 4)
        buffer.positionCursor(0, 2)
        buffer.writeText("tail")
        buffer.positionCursor(0, 0)
        buffer.eraseScreenToEnd()
        buffer.assertOutputEnd(0)
        buffer.writeText("new")
        buffer.assertOutputEnd(1)
    }

    @Test
    fun `blank output remains absolute through saturated history and scrolled frames`() {
        val buffer = DefaultTerminalBuffer(8, 2, maxHistory = 2)
        repeat(6) { buffer.newLine() }
        buffer.assertOutputEnd(6)
        buffer.readRenderFrame(scrollbackOffset = 2, viewportRows = 1) {
            assertEquals(3L, it.discardedCount)
            assertEquals(6L, it.outputEndAbsoluteRow)
        }
        buffer.writeText("x")
        buffer.assertOutputEnd(7)
        buffer.scrollUp()
        buffer.assertOutputEnd(7)
        buffer.scrollUp()
        buffer.assertOutputEnd(7)
    }

    @Test
    fun `scrolling and line insertion move provenance and recycled rows start untouched`() {
        val buffer = DefaultTerminalBuffer(8, 4)
        buffer.writeText("one")
        buffer.scrollDown()
        buffer.assertOutputEnd(2)
        buffer.positionCursor(0, 0)
        buffer.insertLines(1)
        buffer.assertOutputEnd(3)
        buffer.deleteLines(1)
        buffer.assertOutputEnd(2)
        buffer.scrollUp()
        buffer.assertOutputEnd(2)
        buffer.readRenderFrame { assertEquals(1, it.historySize) }
    }

    @Test
    fun `line feeds with margins mark source output before local rotation`() {
        val buffer = DefaultTerminalBuffer(8, 4)
        buffer.setScrollRegion(2, 3)
        buffer.positionCursor(0, 2)
        buffer.newLine()
        buffer.assertOutputEnd(2)
        buffer.readRenderFrame { assertEquals(0, it.historySize) }

        buffer.reset()
        buffer.setScrollRegion(1, 2)
        buffer.newLine()
        buffer.newLine()
        buffer.assertOutputEnd(2)
        buffer.readRenderFrame { assertEquals(1, it.historySize) }
    }

    @Test
    fun `horizontal slice scrolling carries authored output without creating untouched tail output`() {
        val buffer = DefaultTerminalBuffer(8, 4)
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 6)
        buffer.writeText("x")
        buffer.scrollDown()
        buffer.assertOutputEnd(2)
        buffer.readRenderFrame { assertEquals(0, it.historySize) }
    }

    @Test
    fun `rectangle fill and copy author text including explicit blank destinations`() {
        val buffer = DefaultTerminalBuffer(8, 4)
        buffer.fillRectangle(' '.code, top = 2, left = 1, bottom = 2, right = 2)
        buffer.assertOutputEnd(2)
        buffer.copyRectangle(1, 1, 1, 2, 1, 4, 1, 1)
        buffer.assertOutputEnd(4)
        buffer.eraseEntireScreen()
        buffer.assertOutputEnd(0)
        buffer.decaln()
        buffer.assertOutputEnd(4)
    }

    @Test
    fun `alternate screens own their output extent and clearing entries reset it`() {
        val buffer = DefaultTerminalBuffer(8, 4)
        buffer.newLine()
        buffer.newLine()
        buffer.assertOutputEnd(2)
        buffer.enterAltBufferWithoutCursorSave(clearBeforeEnter = false)
        buffer.assertOutputEnd(0)
        buffer.writeText("alt")
        buffer.assertOutputEnd(1)
        buffer.exitAltBufferWithoutCursorRestore()
        buffer.assertOutputEnd(2)
        buffer.enterAltBufferWithoutCursorSave(clearBeforeEnter = false)
        buffer.assertOutputEnd(1)
        buffer.exitAltBufferWithoutCursorRestore()
        buffer.enterAltBufferWithoutCursorSave(clearBeforeEnter = true)
        buffer.assertOutputEnd(0)
        buffer.exitAltBufferWithoutCursorRestore()
        buffer.assertOutputEnd(2)
    }

    @Test
    fun `reflow preserves authored blank rows and excludes untouched capacity`() {
        val buffer = DefaultTerminalBuffer(8, 4)
        buffer.newLine()
        buffer.newLine()
        buffer.assertOutputEnd(2)
        buffer.resize(4, 4)
        buffer.assertOutputEnd(2)
        buffer.resize(16, 6)
        buffer.assertOutputEnd(2)
    }

    @Test
    fun `reflow cursor padding does not become authored output`() {
        val buffer = DefaultTerminalBuffer(8, 4)
        buffer.writeText("x")
        buffer.positionCursor(7, 0)
        buffer.resize(2, 4)
        buffer.assertOutputEnd(1)
        buffer.readRenderFrame { assertEquals(3, it.cursor.row) }
    }

    @Test
    fun `ED3 preserves visible authored blanks and resets only their absolute origin`() {
        val buffer = DefaultTerminalBuffer(8, 3, maxHistory = 4)
        repeat(4) { buffer.newLine() }
        buffer.assertOutputEnd(4)
        buffer.eraseScreenAndHistory()
        buffer.assertOutputEnd(2)
        buffer.readRenderFrame {
            assertEquals(0, it.historySize)
            assertEquals(0L, it.discardedCount)
        }
        buffer.softReset()
        buffer.assertOutputEnd(2)
        buffer.clearAll()
        buffer.assertOutputEnd(0)
    }

    private fun DefaultTerminalBuffer.assertOutputEnd(expected: Long) {
        readRenderFrame { assertEquals(expected, it.outputEndAbsoluteRow) }
    }
}
