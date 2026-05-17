package com.gagik.terminal.ui.swing.api

import com.gagik.terminal.ui.swing.render.TestRenderFrame
import com.gagik.terminal.ui.swing.render.renderCache
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TerminalSelectionTextExtractorTest {
    private val extractor = TerminalSelectionTextExtractor()

    @Test
    fun `selected text trims trailing row blanks but preserves interior blanks`() {
        val cache = renderCache(TestRenderFrame.text("ab  cd  "))
        val selection = CellSelection(anchorColumn = 0, anchorRow = 0, caretColumn = 8, caretRow = 0)

        assertEquals("ab  cd", extractor.selectedText(cache, selection))
    }

    @Test
    fun `word selection groups letters digits and underscore`() {
        val cache = renderCache(TestRenderFrame.text("foo_bar-99"))
        val selection = extractor.wordSelectionAt(cache, row = 0, column = 2)

        assertEquals(CellSelection(0, 0, 7, 0), selection)
    }
}
