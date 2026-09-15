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
package io.github.ketraterm.ui.swing.api

import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.ui.swing.render.TestCell
import io.github.ketraterm.ui.swing.render.TestRenderFrame
import io.github.ketraterm.ui.swing.render.renderCache
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class TerminalSelectionTextExtractorTest {
    private val extractor = TerminalSelectionTextExtractor()

    @Test
    fun `selected text trims trailing row blanks but preserves interior blanks`() {
        val cache = renderCache(TestRenderFrame.text("ab  cd  "))
        val selection = CellSelection(anchorColumn = 0, anchorRow = 0, caretColumn = 8, caretRow = 0)

        assertEquals("ab  cd", extractor.selectedText(cache, selection))
    }

    @Test
    fun `selected text ignores empty cells after row content`() {
        val cache =
            renderCache(
                TestRenderFrame(
                    arrayOf(
                        arrayOf(
                            TestCell(codeWord = 'a'.code, flags = TerminalRenderCellFlags.CODEPOINT),
                            TestCell(codeWord = 'b'.code, flags = TerminalRenderCellFlags.CODEPOINT),
                            TestCell(),
                            TestCell(),
                            TestCell(),
                        ),
                    ),
                ),
            )
        val selection = CellSelection(anchorColumn = 0, anchorRow = 0, caretColumn = 5, caretRow = 0)

        assertEquals("ab", extractor.selectedText(cache, selection))
    }

    @Test
    fun `selected text returns empty when only empty cells are selected`() {
        val cache =
            renderCache(
                TestRenderFrame(
                    arrayOf(
                        arrayOf(
                            TestCell(codeWord = 'a'.code, flags = TerminalRenderCellFlags.CODEPOINT),
                            TestCell(),
                            TestCell(),
                            TestCell(),
                        ),
                    ),
                ),
            )
        val selection = CellSelection(anchorColumn = 1, anchorRow = 0, caretColumn = 4, caretRow = 0)

        assertEquals("", extractor.selectedText(cache, selection))
    }

    @Test
    fun `selected text preserves empty positioning cells before later content`() {
        val cache =
            renderCache(
                TestRenderFrame(
                    arrayOf(
                        arrayOf(
                            TestCell(codeWord = 'a'.code, flags = TerminalRenderCellFlags.CODEPOINT),
                            TestCell(),
                            TestCell(),
                            TestCell(codeWord = 'b'.code, flags = TerminalRenderCellFlags.CODEPOINT),
                        ),
                    ),
                ),
            )
        val selection = CellSelection(anchorColumn = 0, anchorRow = 0, caretColumn = 4, caretRow = 0)

        assertEquals("a  b", extractor.selectedText(cache, selection))
    }

    @Test
    fun `selected text joins soft wrapped rows for linear copy`() {
        val frame =
            object : TestRenderFrame(arrayOf(textCells("ab"), textCells("cd"))) {
                override fun lineWrapped(row: Int): Boolean = row == 0
            }
        val cache = renderCache(frame)
        val selection = CellSelection(anchorColumn = 0, anchorRow = 0, caretColumn = 2, caretRow = 1)

        assertEquals("abcd", extractor.selectedText(cache, selection, joinSoftWrappedRows = true))
    }

    @Test
    fun `linear copy preserves a separator space at a soft wrap boundary`() {
        val frame =
            object : TestRenderFrame(arrayOf(textCells("echo "), textCells("test "))) {
                override fun lineWrapped(row: Int): Boolean = row == 0
            }
        val selection = CellSelection(0, 0, 4, 1)

        assertEquals("echo test", extractor.selectedText(renderCache(frame), selection, joinSoftWrappedRows = true))
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 5, 8, 13, 80])
    fun `copying a quoted path preserves text at different wrap widths`(columns: Int) {
        val text = "cat '/work/my project/file.txt'"
        val chunks = text.chunked(columns)
        val cells =
            chunks
                .map { chunk ->
                    Array(columns) { column ->
                        if (column < chunk.length) {
                            TestCell(codeWord = chunk[column].code, flags = TerminalRenderCellFlags.CODEPOINT)
                        } else {
                            TestCell()
                        }
                    }
                }.toTypedArray()
        val frame =
            object : TestRenderFrame(cells) {
                override fun lineWrapped(row: Int): Boolean = row < chunks.lastIndex
            }
        val selection = CellSelection(0, 0, chunks.last().length, chunks.lastIndex)

        assertEquals(text, extractor.selectedText(renderCache(frame), selection, joinSoftWrappedRows = true))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `partial wrapped copy preserves spaces in both selection directions`(backward: Boolean) {
        val frame =
            object : TestRenderFrame(arrayOf(textCells(">abc "), textCells("def!?"))) {
                override fun lineWrapped(row: Int): Boolean = row == 0
            }
        val selection = if (backward) CellSelection(3, 1, 1, 0) else CellSelection(1, 0, 3, 1)

        assertEquals("abc def", extractor.selectedText(renderCache(frame), selection, joinSoftWrappedRows = true))
    }

    @Test
    fun `linear copy preserves a fully blank physical row inside a logical line`() {
        val frame =
            object : TestRenderFrame(arrayOf(textCells("ab  "), textCells("    "), textCells("  cd"))) {
                override fun lineWrapped(row: Int): Boolean = row < 2
            }
        val selection = CellSelection(0, 0, 4, 2)

        assertEquals("ab        cd", extractor.selectedText(renderCache(frame), selection, joinSoftWrappedRows = true))
    }

    @Test
    fun `linear copy preserves erased cells across wrapped rows`() {
        val frame =
            object : TestRenderFrame(
                arrayOf(textCells("ab") + arrayOf(TestCell(), TestCell()), Array(4) { TestCell() }, textCells("  cd")),
            ) {
                override fun lineWrapped(row: Int): Boolean = row < 2
            }
        val selection = CellSelection(0, 0, 4, 2)

        assertEquals("ab        cd", extractor.selectedText(renderCache(frame), selection, joinSoftWrappedRows = true))
    }

    @Test
    fun `partial row copy preserves selected spaces before unselected text`() {
        val cache = renderCache(TestRenderFrame.text("echo test"))
        val selection = CellSelection(0, 0, 5, 0)

        assertEquals("echo ", extractor.selectedText(cache, selection, joinSoftWrappedRows = true))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `selection ending at the next row start includes only a hard line break`(softWrapped: Boolean) {
        val frame =
            object : TestRenderFrame(arrayOf(textCells("left"), textCells("next"))) {
                override fun lineWrapped(row: Int): Boolean = row == 0 && softWrapped
            }
        val selection = CellSelection(0, 0, 0, 1)

        assertEquals(
            if (softWrapped) "left" else "left\n",
            extractor.selectedText(renderCache(frame), selection, joinSoftWrappedRows = true),
        )
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `selection starting at the preceding row end includes only a hard line break`(softWrapped: Boolean) {
        val frame =
            object : TestRenderFrame(arrayOf(textCells("left"), textCells("next"))) {
                override fun lineWrapped(row: Int): Boolean = row == 0 && softWrapped
            }
        val selection = CellSelection(4, 0, 4, 1)

        assertEquals(
            if (softWrapped) "next" else "\nnext",
            extractor.selectedText(renderCache(frame), selection, joinSoftWrappedRows = true),
        )
    }

    @Test
    fun `linear copy keeps hard breaks and indentation around wrapped lines`() {
        val frame =
            object : TestRenderFrame(arrayOf(textCells("one "), textCells("  tw"), textCells("o   "), textCells("done"))) {
                override fun lineWrapped(row: Int): Boolean = row == 1
            }
        val selection = CellSelection(0, 0, 4, 3)

        assertEquals("one\n  two\ndone", extractor.selectedText(renderCache(frame), selection, joinSoftWrappedRows = true))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `wide character wrap omits empty padding but preserves a preceding real space`(hasSeparator: Boolean) {
        val firstRow =
            textCells(if (hasSeparator) "ab " else "abc") +
                TestCell(flags = TerminalRenderCellFlags.EMPTY or TerminalRenderCellFlags.WRAP_PADDING)
        val secondRow =
            arrayOf(
                TestCell(codeWord = '中'.code, flags = TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING),
                TestCell(flags = TerminalRenderCellFlags.WIDE_TRAILING),
                TestCell(codeWord = 'x'.code, flags = TerminalRenderCellFlags.CODEPOINT),
                TestCell(),
            )
        val frame =
            object : TestRenderFrame(arrayOf(firstRow, secondRow)) {
                override fun lineWrapped(row: Int): Boolean = row == 0
            }
        val selection = CellSelection(0, 0, 3, 1)

        assertEquals(
            if (hasSeparator) "ab 中x" else "abc中x",
            extractor.selectedText(renderCache(frame), selection, joinSoftWrappedRows = true),
        )
    }

    @Test
    fun `wrapped copy keeps combining and emoji clusters intact`() {
        val firstRow =
            arrayOf(
                TestCell(codeWord = 'A'.code, flags = TerminalRenderCellFlags.CODEPOINT),
                TestCell(flags = TerminalRenderCellFlags.CLUSTER, cluster = "e\u0301"),
                TestCell(codeWord = 'B'.code, flags = TerminalRenderCellFlags.CODEPOINT),
            )
        val secondRow =
            arrayOf(
                TestCell(flags = TerminalRenderCellFlags.CLUSTER or TerminalRenderCellFlags.WIDE_LEADING, cluster = "👩‍💻"),
                TestCell(flags = TerminalRenderCellFlags.WIDE_TRAILING),
                TestCell(codeWord = 'C'.code, flags = TerminalRenderCellFlags.CODEPOINT),
            )
        val frame =
            object : TestRenderFrame(arrayOf(firstRow, secondRow)) {
                override fun lineWrapped(row: Int): Boolean = row == 0
            }
        val selection = CellSelection(0, 0, 3, 1)

        assertEquals("Ae\u0301B👩‍💻C", extractor.selectedText(renderCache(frame), selection, joinSoftWrappedRows = true))
    }

    @Test
    fun `selected text preserves row breaks for block copy across soft wraps`() {
        val frame =
            object : TestRenderFrame(arrayOf(textCells("ab"), textCells("cd"))) {
                override fun lineWrapped(row: Int): Boolean = row == 0
            }
        val cache = renderCache(frame)
        val selection = CellSelection(anchorColumn = 0, anchorRow = 0, caretColumn = 2, caretRow = 1, isBlock = true)

        assertEquals("ab\ncd", extractor.selectedText(cache, selection, joinSoftWrappedRows = true))
    }

    @Test
    fun `block copy projects the same visual interval separately on each row`() {
        val cache = renderCache(TestRenderFrame(arrayOf(textCells("ABC"), textCells("אבג"))))
        val selection = CellSelection(0, 0, 1, 1, isBlock = true)

        assertEquals("A\nג", extractor.selectedText(cache, selection))
    }

    @Test
    fun `block copy preserves logical text order within an rtl row`() {
        val cache = renderCache(TestRenderFrame.text("אבג"))

        assertEquals("אבג", extractor.selectedText(cache, CellSelection(0, 0, 3, 0, isBlock = true)))
        assertEquals("אב", extractor.selectedText(cache, CellSelection(3, 0, 1, 0, isBlock = true)))
    }

    @Test
    fun `mixed bidi block copy selects disjoint logical spans without filling their gap`() {
        // Logical "AB אבג" displays as "AB גבא". Visual columns 1..4 select B, space and ג.
        val cache = renderCache(TestRenderFrame.text("AB אבג"))
        val selection = CellSelection(1, 0, 4, 0, isBlock = true)

        assertEquals("B ג", extractor.selectedText(cache, selection))
        assertEquals("B א", extractor.selectedText(cache, selection.copy(isBlock = false)))
    }

    @ParameterizedTest
    @CsvSource("1, false", "2, false", "1, true", "2, true")
    fun `block copy includes a whole wide cell when either visual half is selected`(
        selectedColumn: Int,
        clustered: Boolean,
    ) {
        val expected = if (clustered) "🙂\uFE0F" else "字"
        val cell =
            if (clustered) {
                TestCell(flags = TerminalRenderCellFlags.CLUSTER or TerminalRenderCellFlags.WIDE_LEADING, cluster = expected)
            } else {
                TestCell(codeWord = '字'.code, flags = TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING)
            }
        val cache =
            renderCache(
                TestRenderFrame(
                    arrayOf(
                        arrayOf(
                            TestCell(codeWord = 'א'.code, flags = TerminalRenderCellFlags.CODEPOINT),
                            cell,
                            TestCell(flags = TerminalRenderCellFlags.WIDE_TRAILING),
                            TestCell(codeWord = 'ב'.code, flags = TerminalRenderCellFlags.CODEPOINT),
                        ),
                    ),
                ),
            )

        assertEquals(expected, extractor.selectedText(cache, CellSelection(selectedColumn, 0, selectedColumn + 1, 0, isBlock = true)))
    }

    @Test
    fun `block copy updates its row mapping when the cache is replaced`() {
        val selection = CellSelection(0, 0, 1, 0, isBlock = true)

        assertEquals("A", extractor.selectedText(renderCache(TestRenderFrame.text("ABC")), selection))
        assertEquals("ג", extractor.selectedText(renderCache(TestRenderFrame.text("אבג")), selection))
    }

    @Test
    fun `selected text preserves leading and repeated empty hard rows`() {
        val cache =
            renderCache(
                TestRenderFrame(
                    arrayOf(
                        Array(2) { TestCell() },
                        Array(2) { TestCell() },
                        textCells("ab"),
                    ),
                ),
            )
        val selection = CellSelection(anchorColumn = 0, anchorRow = 0, caretColumn = 2, caretRow = 2)

        assertEquals("\n\nab", extractor.selectedText(cache, selection, joinSoftWrappedRows = true))
    }

    @Test
    fun `word selection groups letters digits and underscore`() {
        val cache = renderCache(TestRenderFrame.text("foo_bar-99"))
        val selection = extractor.wordSelectionAt(cache, row = 0, column = 2)

        assertEquals(CellSelection(0, 0, 7, 0), selection)
    }

    @Test
    fun `path selection groups entire path when containing indicators`() {
        val cache = renderCache(TestRenderFrame.text("/usr/local/bin/git"))
        val selection = extractor.wordSelectionAt(cache, row = 0, column = 6) // clicks on "l" in "local"

        assertEquals(CellSelection(0, 0, 18, 0), selection)
    }

    @Test
    fun `url selection groups entire url when containing indicators`() {
        val cache = renderCache(TestRenderFrame.text("https://google.com/search?q=test"))
        val selection = extractor.wordSelectionAt(cache, row = 0, column = 10) // clicks on "o" in "google"

        assertEquals(CellSelection(0, 0, 32, 0), selection)
    }

    private fun textCells(text: String): Array<TestCell> =
        Array(text.length) { index ->
            TestCell(codeWord = text[index].code, flags = TerminalRenderCellFlags.CODEPOINT)
        }
}
