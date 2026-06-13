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
package io.github.jvterm.ui.swing.api

import io.github.jvterm.ui.swing.render.TestRenderFrame
import io.github.jvterm.ui.swing.render.renderCache
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
}
