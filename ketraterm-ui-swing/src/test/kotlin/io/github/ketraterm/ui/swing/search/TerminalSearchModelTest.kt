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
import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.render.cache.TerminalTextRangeCopy
import io.github.ketraterm.ui.swing.render.forEachLogicalTextCell
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CancellationException

class TerminalSearchModelTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `search preserves wrapped separators and trims only final spaces`(textOnly: Boolean) {
        val frame = WrappedTextFrame(arrayOf("ab  ", "cd  ", "    ", "xy  "), booleanArrayOf(true, false, false, false))
        val cache = renderCache(frame)
        val matches = searchFrame(frame, "ab  cd", textOnly)
        val viewport = TerminalSearchViewportHighlights()
        matches.buildViewportHighlights(cache, viewport)
        assertEquals(1, matches.resultCount)
        assertEquals(2, viewport.segmentCount)
        assertEquals(0, viewport.startColumn(0))
        assertEquals(4, viewport.endColumn(0))
        assertEquals(0, viewport.startColumn(1))
        assertEquals(2, viewport.endColumn(1))
        assertEquals(0, searchFrame(frame, "abcd", textOnly).resultCount)
        assertEquals(0, searchFrame(frame, "cd ", textOnly).resultCount)
        assertEquals(0, searchFrame(frame, "cdxy", textOnly).resultCount)
        assertEquals(1, searchFrame(frame, "xy", textOnly).resultCount)

        val blankContinuation = WrappedTextFrame(arrayOf("ab  ", "    "), booleanArrayOf(true, false))
        assertEquals(1, searchFrame(blankContinuation, "ab", textOnly).resultCount)
        assertEquals(0, searchFrame(blankContinuation, "ab ", textOnly).resultCount)
    }

    @ParameterizedTest
    @CsvSource("false,wide", "true,wide", "false,cluster", "true,cluster", "false,nbsp", "true,nbsp")
    fun `sparse search retains wide clusters and non ASCII spaces`(
        textOnly: Boolean,
        suffix: String,
    ) {
        val terminal = TerminalBuffers.create(16, 3)
        terminal.writeText("a ")
        val query =
            when (suffix) {
                "wide" -> "界".also { terminal.writeText(it) }
                "cluster" -> " \u0301".also { terminal.writeCluster(intArrayOf(' '.code, 0x301)) }
                "nbsp" -> "\u00a0".also { terminal.writeText(it) }
                else -> error("Unexpected suffix: $suffix")
            }
        terminal.writeText("  ")
        val reader = terminal as TerminalRenderFrameReader
        val cache = TerminalRenderCache(16, 3)
        cache.updateFrom(reader)
        val model = TerminalSearchModel()
        val matches =
            if (textOnly) {
                val copy = TerminalTextRangeCopy()
                assertTrue(copy.read(reader, 0, 2))
                model.begin(query, false, 0)
                model.append(copy)
                model.finish()
            } else {
                model.search(cache, query, false)
            }
        val viewport = TerminalSearchViewportHighlights()
        matches.buildViewportHighlights(cache, viewport)
        assertEquals(1, matches.resultCount)
        assertEquals(1, viewport.segmentCount)
        assertEquals(2, viewport.startColumn(0))
        assertEquals(if (suffix == "wide") 4 else 3, viewport.endColumn(0))
    }

    @Test
    fun `search remains cancellable in a long blank tail`() {
        val cache = renderCache(WrappedTextFrame(arrayOf("x" + " ".repeat(8192))))
        val model = TerminalSearchModel()
        val cancelled = CancellationException("stop blank tail")
        var checks = 0
        model.begin("x", false, 0, { if (++checks == 3) throw cancelled })
        assertSame(cancelled, assertThrows(CancellationException::class.java) { model.append(cache, 0) })
        assertEquals(1, model.search(cache, "x", false).resultCount)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `decoding checks cancellation within long rows and cluster payloads`(cluster: Boolean) {
        val length = 2048
        val flags = IntArray(length) { TerminalRenderCellFlags.CODEPOINT }
        val refs = LongArray(length)
        if (cluster) {
            flags[0] = TerminalRenderCellFlags.CLUSTER
            refs[0] = length.toLong()
        }
        val cancelled = CancellationException("stop decoding")
        var decoded = 0
        assertSame(
            cancelled,
            assertThrows(CancellationException::class.java) {
                forEachLogicalTextCell(
                    length,
                    IntArray(length) { 'a'.code },
                    flags,
                    refs,
                    IntArray(length) { 0x301 },
                    0,
                    { if (decoded >= 300) throw cancelled },
                ) { _, _, _ -> decoded++ }
            },
        )
        assertTrue(decoded in 300..556, "Cancellation must be observed within 256 more decoded codepoints: $decoded")
    }

    @Test
    fun `decoding remains cancellable when every cell is skipped`() {
        val cancelled = CancellationException("stop skipping")
        var checks = 0
        assertSame(
            cancelled,
            assertThrows(CancellationException::class.java) {
                forEachLogicalTextCell(
                    2048,
                    IntArray(2048),
                    IntArray(2048) {
                        if (it % 2 == 0) TerminalRenderCellFlags.WIDE_TRAILING else TerminalRenderCellFlags.WRAP_PADDING
                    },
                    LongArray(2048),
                    IntArray(0),
                    0,
                    { if (++checks == 2) throw cancelled },
                ) { _, _, _ -> fail("Skipped cells must not emit text") }
            },
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["b", "aa", "aaa"])
    fun `short query matching remains cancellable after decoding`(query: String) {
        val cache = renderCache(WrappedTextFrame(arrayOf("a".repeat(8192)), wrapped = booleanArrayOf(true)))
        val model = TerminalSearchModel()
        val cancelled = CancellationException("stop matching")
        var cancel = false
        var checks = 0
        model.begin(query, false, 0, { if (cancel && ++checks == 2) throw cancelled })
        model.append(cache, 0)
        cancel = true
        assertSame(cancelled, assertThrows(CancellationException::class.java) { model.finish() })
        assertEquals(1, model.search(renderCache(WrappedTextFrame(arrayOf("needle"))), "needle", false).resultCount)
    }

    @ParameterizedTest
    @CsvSource("false,abc", "false,'ab '", "true,abc", "true,'ab '")
    fun `search excludes wide wrap padding and retains written separators`(
        cluster: Boolean,
        prefix: String,
    ) {
        val terminal = TerminalBuffers.create(width = 4, height = 3, maxHistory = 8)
        terminal.writeText(prefix)
        val wideText = if (cluster) "界\u0301" else "界"
        if (cluster) terminal.writeCluster(intArrayOf(0x754C, 0x0301)) else terminal.writeCodepoint(0x754C)
        terminal.writeText("x")
        val cache = TerminalRenderCache(4, 3)
        val reader = terminal as TerminalRenderFrameReader
        val model = TerminalSearchModel()

        assertAll(
            intArrayOf(4, 8, 4).map { width ->
                Executable {
                    terminal.resize(newWidth = width, newHeight = 3)
                    cache.updateFromAbsoluteRange(reader, 0L, Long.MAX_VALUE)
                    val matches = model.search(cache, "$prefix${wideText}x", ignoreCase = false)
                    assertEquals(1, matches.resultCount, "width=$width prefix='$prefix'")
                    val viewport = TerminalSearchViewportHighlights()
                    matches.buildViewportHighlights(cache, viewport)
                    if (width == 4) {
                        assertEquals(2, viewport.segmentCount)
                        assertEquals(0, viewport.startColumn(0))
                        assertEquals(3, viewport.endColumn(0), "The first highlight must end before wrap padding")
                        assertEquals(0, viewport.startColumn(1))
                        assertEquals(3, viewport.endColumn(1), "The continuation must include the wide cell and following text")
                    } else {
                        assertEquals(1, viewport.segmentCount)
                        assertEquals(0, viewport.startColumn(0))
                        assertEquals(6, viewport.endColumn(0))
                    }
                    assertEquals(
                        0,
                        model.search(cache, "$prefix $wideText", ignoreCase = false).resultCount,
                        "Wrap padding is not a separator",
                    )
                    if (prefix.endsWith(' ')) {
                        assertEquals(
                            0,
                            model.search(cache, "ab$wideText", ignoreCase = false).resultCount,
                            "Written separators must remain",
                        )
                    }
                }
            },
        )
    }

    @Test
    fun `viewport projection includes all boundary matches and clips a wrapped active result`() {
        val cache = renderCache(WrappedTextFrame(Array(1000) { "aa" }, wrapped = BooleanArray(1000) { it < 999 }))
        val highlights = TerminalSearchModel().search(cache, "aaa", ignoreCase = false)
        val viewportCache = renderCache(WrappedTextFrame(arrayOf("aa", "aa"), historySize = 998, scrollbackOffset = 499))
        val viewport = TerminalSearchViewportHighlights()
        // Result 332 starts on row 498 and continues into the first viewport row (499).
        highlights.activate(332)

        highlights.buildViewportHighlights(viewportCache, viewport)

        assertEquals(3, viewport.segmentCount)
        assertEquals(2, viewport.segmentCountForRow(0))
        assertEquals(1, viewport.segmentCountForRow(1))
        assertEquals(0, viewport.startColumn(0))
        assertEquals(1, viewport.endColumn(0))
        assertTrue(viewport.isActive(0))
    }

    @Test
    fun `literal search returns all result occurrences`() {
        val cache = renderCache(WrappedTextFrame(arrayOf("foo bar foo")))
        val highlights = TerminalSearchModel().search(cache, "foo", ignoreCase = true)

        assertEquals(2, highlights.resultCount)
        assertEquals(0, highlights.activeResultIndex)
    }

    @Test
    fun `search joins soft wrapped rows`() {
        val cache =
            renderCache(
                WrappedTextFrame(
                    textRows = arrayOf("hello", "world"),
                    wrapped = booleanArrayOf(true, false),
                ),
            )
        val highlights = TerminalSearchModel().search(cache, "lowo", ignoreCase = true)
        val viewport = TerminalSearchViewportHighlights()

        highlights.buildViewportHighlights(cache, viewport)

        assertEquals(1, highlights.resultCount)
        assertEquals(1, viewport.segmentCountForRow(0))
        assertEquals(1, viewport.segmentCountForRow(1))
    }

    @Test
    fun `case sensitive search honors toggle policy`() {
        val cache = renderCache(WrappedTextFrame(arrayOf("Build build")))
        val model = TerminalSearchModel()

        assertEquals(2, model.search(cache, "build", ignoreCase = true).resultCount)
        assertEquals(1, model.search(cache, "build", ignoreCase = false).resultCount)
    }

    @Test
    fun `search reanchors absolute rows after repeated history eviction`() {
        val model = TerminalSearchModel()
        val beforeEviction =
            renderCache(
                WrappedTextFrame(
                    textRows = arrayOf("needle"),
                    historySize = 5,
                    scrollbackOffset = 2,
                    discardedCount = 0,
                ),
            )
        val afterEviction =
            renderCache(
                WrappedTextFrame(
                    textRows = arrayOf("needle"),
                    historySize = 5,
                    scrollbackOffset = 2,
                    discardedCount = 3,
                ),
            )

        assertEquals(3L, model.search(beforeEviction, "needle", ignoreCase = true).activeStartAbsoluteRow())
        assertEquals(6L, model.search(afterEviction, "needle", ignoreCase = true).activeStartAbsoluteRow())
    }

    private fun searchFrame(
        frame: TerminalRenderFrame,
        query: String,
        textOnly: Boolean,
    ): TerminalSearchHighlights {
        val model = TerminalSearchModel()
        if (!textOnly) return model.search(renderCache(frame), query, false)
        val copy = TerminalTextRangeCopy()
        val reader =
            object : TerminalRenderFrameReader {
                override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) = consumer.accept(frame)
            }
        assertTrue(copy.read(reader, 0, frame.rows - 1L))
        model.begin(query, false, 0)
        model.append(copy)
        return model.finish()
    }

    private fun renderCache(frame: TerminalRenderFrame): TerminalRenderCache {
        val cache = TerminalRenderCache(frame.columns, frame.rows)
        cache.updateFrom(
            object : TerminalRenderFrameReader {
                override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
                    consumer.accept(frame)
                }
            },
        )
        return cache
    }

    private class WrappedTextFrame(
        private val textRows: Array<String>,
        private val wrapped: BooleanArray = BooleanArray(textRows.size),
        override val historySize: Int = 0,
        override val scrollbackOffset: Int = 0,
        override val discardedCount: Long = 0L,
    ) : TerminalRenderFrame {
        override val columns: Int = textRows.maxOf { it.length }
        override val rows: Int = textRows.size
        override val frameGeneration: Long = 1
        override val structureGeneration: Long = 1
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = false,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = 1,
            )

        override fun lineGeneration(row: Int): Long = 1

        override fun lineWrapped(row: Int): Boolean = wrapped[row]

        override fun copyLine(
            row: Int,
            codeWords: IntArray,
            codeOffset: Int,
            attrWords: LongArray,
            attrOffset: Int,
            flags: IntArray,
            flagOffset: Int,
            extraAttrWords: LongArray?,
            extraAttrOffset: Int,
            hyperlinkIds: IntArray?,
            hyperlinkOffset: Int,
            clusterSink: TerminalRenderClusterSink?,
            clusterDataSink: TerminalRenderClusterDataSink?,
        ) {
            var column = 0
            while (column < columns) {
                attrWords[attrOffset + column] = TerminalRenderAttrs.DEFAULT
                extraAttrWords?.set(extraAttrOffset + column, TerminalRenderExtraAttrs.DEFAULT)
                hyperlinkIds?.set(hyperlinkOffset + column, 0)
                if (column < textRows[row].length) {
                    codeWords[codeOffset + column] = textRows[row][column].code
                    flags[flagOffset + column] = TerminalRenderCellFlags.CODEPOINT
                } else {
                    codeWords[codeOffset + column] = 0
                    flags[flagOffset + column] = TerminalRenderCellFlags.EMPTY
                }
                column++
            }
        }
    }
}
