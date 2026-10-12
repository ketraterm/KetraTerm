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
package io.github.ketraterm.ui.swing.search

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TerminalSearchWindowTest {
    @Test
    fun `collection starts at viewport and overflow does not restrict navigation`() =
        runTest {
            val terminal = TerminalBuffers.create(12, 1, 3000)
            repeat(2500) {
                if (it > 0) {
                    terminal.carriageReturn()
                    terminal.newLine()
                }
                terminal.writeText("needle")
            }
            val reader = terminal as TerminalRenderFrameReader
            val search = TerminalSearchWindow(reader, "needle", false)
            val found = requireNotNull(search.collect(500)).highlights
            assertEquals(1001, found.resultCount)
            assertEquals(500L, found.activeStartAbsoluteRow())
            assertEquals(2001L, search.find(2000, 0, false).activeStartAbsoluteRow())
            assertEquals(1999L, search.find(2000, 0, true).activeStartAbsoluteRow())
            assertEquals(0L, search.find(2499, 0, false).activeStartAbsoluteRow())
            assertEquals(2499L, search.find(0, 0, true).activeStartAbsoluteRow())
            val visible = search.visible(2300, 2304)
            assertEquals(5, visible.resultCount)
            assertEquals(2300L, visible.activeStartAbsoluteRow())
        }

    @Test
    fun `exact threshold and viewport near end wrap without losing older matches`() =
        runTest {
            for (count in listOf(999, 1000, 1001)) {
                val terminal = TerminalBuffers.create(8, 1, 2000)
                repeat(count) {
                    if (it > 0) {
                        terminal.carriageReturn()
                        terminal.newLine()
                    }
                    terminal.writeText("a")
                }
                val found =
                    requireNotNull(
                        TerminalSearchWindow(terminal as TerminalRenderFrameReader, "a", false).collect(count - 2L),
                    ).highlights
                assertEquals(count, found.resultCount)
                assertEquals(count - 2L, found.activeStartAbsoluteRow())
            }
        }

    @Test
    fun `long wrapped line bounds results and preserves nonoverlapping navigation`() =
        runTest {
            val terminal = TerminalBuffers.create(80, 1, 3000)
            terminal.writeText("a".repeat(100000))
            val search = TerminalSearchWindow(terminal as TerminalRenderFrameReader, "aaa", false)
            val found = requireNotNull(search.collect(0)).highlights
            assertEquals(1001, found.resultCount)
            val next = search.find(1100, 0, false)
            assertEquals(1100L, next.activeStartAbsoluteRow())
            assertEquals(2, next.activeStartColumn())
            val previous = search.find(1100, 2, true)
            assertEquals(1099L, previous.activeStartAbsoluteRow())
            assertEquals(79, previous.activeStartColumn())
            val visible = search.visible(1100, 1100)
            visible.activate(0)
            assertEquals(1099L, visible.activeStartAbsoluteRow(), "Include matches crossing into the viewport")
        }

    @Test
    fun `backward search preserves confirmed match before trailing spaces`() =
        runTest {
            val terminal = TerminalBuffers.create(8, 1, 20)
            terminal.writeText("x   y" + " ".repeat(100))
            val search = TerminalSearchWindow(terminal as TerminalRenderFrameReader, " ", false)
            val found = search.find(15, 0, true)
            assertEquals(0L, found.activeStartAbsoluteRow())
            assertEquals(3, found.activeStartColumn())
        }

    @Test
    fun `wrapped matches and case policy remain correct around viewport boundary`() =
        runTest {
            val terminal = TerminalBuffers.create(4, 1, 10)
            terminal.writeText("xxAbCdyy")
            val reader = terminal as TerminalRenderFrameReader
            assertEquals(0, requireNotNull(TerminalSearchWindow(reader, "abcd", false).collect(1)).highlights.resultCount)
            val search = TerminalSearchWindow(reader, "abcd", true)
            val found = requireNotNull(search.collect(1)).highlights
            assertEquals(1, found.resultCount)
            assertEquals(0L, found.activeStartAbsoluteRow())
            assertEquals(2, found.segmentCount)
            assertEquals(1, search.visible(1, 1).resultCount)
        }
}
