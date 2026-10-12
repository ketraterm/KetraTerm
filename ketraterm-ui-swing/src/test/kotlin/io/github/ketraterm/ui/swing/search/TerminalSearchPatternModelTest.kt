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
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.render.cache.TerminalTextRangeCopy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException

class TerminalSearchPatternModelTest {
    @Test
    fun `whole words respect Unicode letters digits marks and underscores`() =
        runTest {
            val terminal = TerminalBuffers.create(128, 1)
            terminal.writeText("cat catfish _cat cat_ cat2 écat caté (cat) CAT ")
            terminal.writeText("ca")
            terminal.writeCluster(intArrayOf('t'.code, 0x301))
            val reader = terminal as TerminalRenderFrameReader
            val sensitive = compileSearchPattern("cat", false, true, false)
            assertEquals(2, requireNotNull(TerminalSearchWindow(reader, "cat", false, sensitive).collect(0)).highlights.resultCount)
            val folded = compileSearchPattern("cat", true, true, false)
            assertEquals(3, requireNotNull(TerminalSearchWindow(reader, "cat", true, folded).collect(0)).highlights.resultCount)
        }

    @Test
    fun `whole word literal quotes regex metacharacters and regex alternatives honor boundaries`() =
        runTest {
            val terminal = TerminalBuffers.create(64, 1)
            terminal.writeText("a.b axb a.bx cats cat")
            val reader = terminal as TerminalRenderFrameReader
            for ((query, regex, count) in listOf(Triple("a.b", false, 1), Triple("cat|cats", true, 2))) {
                val pattern = compileSearchPattern(query, false, true, regex)
                val found = requireNotNull(TerminalSearchWindow(reader, query, false, pattern).collect(0)).highlights
                assertEquals(count, found.resultCount, query)
            }
            // Without either option, punctuation remains ordinary literal text.
            assertEquals(2, requireNotNull(TerminalSearchWindow(reader, "a.b", false).collect(0)).highlights.resultCount)
        }

    @Test
    fun `regex supports groups backreferences lookarounds and Unicode case`() =
        runTest {
            val terminal = TerminalBuffers.create(64, 1)
            terminal.writeText("id=42 id=7 test test Éé")
            val reader = terminal as TerminalRenderFrameReader
            for ((query, count) in listOf("(?<=id=)\\d+" to 2, "(test) \\1" to 1, "é+" to 1)) {
                val pattern = compileSearchPattern(query, true, false, true)
                val matches = requireNotNull(TerminalSearchWindow(reader, query, true, pattern).collect(0)).highlights
                assertEquals(count, matches.resultCount, query)
            }
        }

    @Test
    fun `regex geometry includes wide cells clusters and supplementary characters`() {
        val terminal = TerminalBuffers.create(20, 1)
        terminal.writeText("x界")
        terminal.writeCluster(intArrayOf('e'.code, 0x301))
        terminal.writeText("😀z")
        val reader = terminal as TerminalRenderFrameReader
        val cache = TerminalRenderCache(20, 1).also { it.updateFrom(reader) }
        val copy = TerminalTextRangeCopy().also { assertTrue(it.read(reader, 0, 0)) }
        for ((query, from, to) in listOf(Triple("界e\\p{M}+😀", 1, 6), Triple("\\p{M}", 3, 4), Triple("😀", 4, 6))) {
            val model = TerminalSearchModel()
            model.begin(query, false, 0, validateRows = true, pattern = compileSearchPattern(query, false, false, true))
            model.append(copy)
            val result = model.finish()
            val viewport = TerminalSearchViewportHighlights()
            result.buildViewportHighlights(cache, viewport)
            assertEquals(1, result.resultCount, query)
            assertEquals(1, viewport.segmentCount, query)
            assertEquals(from, viewport.startColumn(0), query)
            assertEquals(to, viewport.endColumn(0), query)
        }
    }

    @Test
    fun `regex joins soft wraps but never hard lines and trims trailing spaces`() =
        runTest {
            val terminal = TerminalBuffers.create(4, 1, 20)
            terminal.writeText("xxabcdef")
            terminal.carriageReturn()
            terminal.newLine()
            terminal.writeText("gh  ")
            val reader = terminal as TerminalRenderFrameReader
            for ((query, count) in listOf("^xxabc.*f$" to 1, "f.*g" to 0, "gh +" to 0, "gh$" to 1, "^|$|(?=x)" to 0)) {
                val pattern = compileSearchPattern(query, false, false, true)
                val search = TerminalSearchWindow(reader, query, false, pattern)
                val found = requireNotNull(search.collect(0)).highlights
                assertEquals(count, found.resultCount, query)
                if (query.startsWith("^xx")) {
                    assertEquals(2, found.segmentCount)
                    assertEquals(1, search.visible(1, 1).resultCount)
                }
            }
        }

    @Test
    fun `regex cap navigation and visible matches use the same pattern`() =
        runTest {
            val terminal = TerminalBuffers.create(20, 1, 3000)
            repeat(1500) {
                if (it > 0) {
                    terminal.carriageReturn()
                    terminal.newLine()
                }
                terminal.writeText("id=$it skip")
            }
            val query = "id=\\d+"
            val search =
                TerminalSearchWindow(terminal as TerminalRenderFrameReader, query, false, compileSearchPattern(query, false, true, true))
            assertEquals(1001, requireNotNull(search.collect(250)).highlights.resultCount)
            assertEquals(1401L, search.find(1400, 0, false).activeStartAbsoluteRow())
            assertEquals(1399L, search.find(1400, 0, true).activeStartAbsoluteRow())
            assertEquals(0L, search.find(1499, 0, false).activeStartAbsoluteRow())
            assertEquals(1499L, search.find(0, 0, true).activeStartAbsoluteRow())
            assertEquals(3, search.visible(1400, 1402).resultCount)
        }

    @Test
    fun `regex can span a copied chunk boundary`() =
        runTest {
            val terminal = TerminalBuffers.create(8, 1, 1000)
            terminal.writeText("a".repeat(3000) + "end")
            val query = "^a+end$"
            val search =
                TerminalSearchWindow(terminal as TerminalRenderFrameReader, query, false, compileSearchPattern(query, false, false, true))
            val result = requireNotNull(search.collect(0)).highlights
            assertEquals(1, result.resultCount)
            assertEquals(376, result.segmentCount)
        }

    @Test
    fun `cancellation interrupts regex backtracking inside a single logical line`() {
        val terminal = TerminalBuffers.create(256, 1)
        terminal.writeText("a".repeat(250) + "!")
        val copy = TerminalTextRangeCopy().also { assertTrue(it.read(terminal as TerminalRenderFrameReader, 0, 0)) }
        // Keep the line pending so cancellation below is reached by matching, not by decoding.
        copy.lineWrapped[0] = true
        var matching = false
        var checks = 0
        val model = TerminalSearchModel()
        model.begin("(a+)+$", false, 0, checkCancelled = {
            if (matching && ++checks == 5) throw CancellationException("cancel matching")
        }, pattern = compileSearchPattern("(a+)+$", false, false, true))
        model.append(copy)
        matching = true
        val failure = assertThrows(CancellationException::class.java) { model.finish() }
        assertEquals("cancel matching", failure.message)
        assertEquals(5, checks)
    }

    @Test
    fun `eviction discards incomplete regex lines without leaking matches into the next line`() {
        val terminal = TerminalBuffers.create(8, 1)
        terminal.writeText("prefix")
        val copy = TerminalTextRangeCopy().also { assertTrue(it.read(terminal as TerminalRenderFrameReader, 0, 0)) }
        copy.lineWrapped[0] = true
        val model = TerminalSearchModel()
        model.begin("prefix.*prefix", false, 0, pattern = compileSearchPattern("prefix.*prefix", false, false, true))
        model.append(copy)
        model.discardPendingLine()
        copy.lineWrapped[0] = false
        model.append(copy)
        assertEquals(0, model.finish().resultCount)
        model.begin("prefix", false, 0)
        model.append(copy)
        assertEquals(1, model.finish().resultCount)
    }
}
