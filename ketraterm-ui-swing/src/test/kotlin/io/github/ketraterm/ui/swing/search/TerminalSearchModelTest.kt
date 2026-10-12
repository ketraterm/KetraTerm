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
import kotlin.random.Random

class TerminalSearchModelTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `ordinary run skipping preserves matches across chunk boundaries`(textOnly: Boolean) {
        val text = "x".repeat(252) + "needle" + "x".repeat(251) + "needle" + "x".repeat(249) + "needle" + "xxxxx"
        val frame = WrappedTextFrame(arrayOf(text))
        val matches = searchFrame(frame, "needle", textOnly)
        val viewport = TerminalSearchViewportHighlights()
        matches.buildViewportHighlights(renderCache(frame), viewport)
        assertEquals(3, matches.resultCount)
        for ((index, start) in intArrayOf(252, 509, 764).withIndex()) {
            assertEquals(start, viewport.startColumn(index))
            assertEquals(start + 6, viewport.endColumn(index))
        }
    }

    @Test
    fun `ordinary run search preserves case folding and hash collisions`() {
        val texts =
            listOf(
                "x".repeat(300) + "\u212A\u03C2\u0131\u017F" + "x".repeat(300),
                "\u0201".repeat(300) + "\u0100abc" + "\u0201".repeat(300),
                "a".repeat(600) + "b",
                "a".repeat(600) + "b" + "a".repeat(32),
            )
        for ((text, query) in texts.zip(listOf("k\u03A3iS", "\u0100abc", "a".repeat(32) + "b", "b" + "a".repeat(32)))) {
            val cache = renderCache(WrappedTextFrame(arrayOf(text)))
            val results = TerminalSearchModel().search(cache, query, true)
            assertEquals(1, results.resultCount, query)
            assertEquals(text.indexOf(query, ignoreCase = true), results.activeStartColumn(), query)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `ordinary run matches respect logical trailing spaces`(textOnly: Boolean) {
        for (tail in listOf(" ".repeat(16), "x" + " ".repeat(15))) {
            val frame = WrappedTextFrame(arrayOf("abcd    abcd    ", tail), booleanArrayOf(true, false))
            val results = searchFrame(frame, "abcd ", textOnly)
            assertEquals(if (tail.startsWith('x')) 2 else 1, results.resultCount, tail)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `bulk text boundaries preserve mixed cell geometry`(textOnly: Boolean) {
        val terminal = TerminalBuffers.create(1024, 1)
        terminal.writeText("a".repeat(255) + "界é")
        terminal.writeCluster(intArrayOf('e'.code, 0x301))
        terminal.writeText("b".repeat(257) + "😀z")
        val reader = terminal as TerminalRenderFrameReader
        val cache = TerminalRenderCache(1024, 1)
        cache.updateFrom(reader)
        val model = TerminalSearchModel()
        val copy = TerminalTextRangeCopy()
        for ((query, start, end) in listOf(
            Triple("a界é", 254, 258),
            Triple("ée\u0301b", 257, 260),
            Triple("b".repeat(257), 259, 516),
            Triple("bb😀z", 514, 519),
        )) {
            val matches =
                if (textOnly) {
                    assertTrue(copy.read(reader, 0, 0))
                    model.begin(query, false, 0)
                    model.append(copy)
                    model.finish()
                } else {
                    model.search(cache, query, false)
                }
            val viewport = TerminalSearchViewportHighlights()
            matches.buildViewportHighlights(cache, viewport)
            assertEquals(1, matches.resultCount, query)
            assertEquals(1, viewport.segmentCount, query)
            assertEquals(start, viewport.startColumn(0), query)
            assertEquals(end, viewport.endColumn(0), query)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `bulk text preserves empty cells inside a wrapped logical line`(textOnly: Boolean) {
        val frame = WrappedTextFrame(arrayOf("ab", "x".repeat(300)), booleanArrayOf(true, false))
        val matches = searchFrame(frame, "b" + " ".repeat(298) + "x", textOnly)
        val viewport = TerminalSearchViewportHighlights()
        matches.buildViewportHighlights(renderCache(frame), viewport)
        assertEquals(1, matches.resultCount)
        assertEquals(2, viewport.segmentCount)
        assertEquals(1, viewport.startColumn(0))
        assertEquals(300, viewport.endColumn(0))
        assertEquals(0, viewport.startColumn(1))
        assertEquals(1, viewport.endColumn(1))
    }

    @Test
    fun `bulk decoding can be cancelled within a long ordinary row`() {
        val model = TerminalSearchModel()
        val cache = renderCache(WrappedTextFrame(arrayOf("x".repeat(8192)), booleanArrayOf(true)))
        val cancelled = CancellationException("stop bulk decoding")
        var checks = 0
        model.begin("needle", false, 0, { if (++checks == 4) throw cancelled })
        assertSame(cancelled, assertThrows(CancellationException::class.java) { model.append(cache, 0) })
        assertEquals(1, model.search(renderCache(WrappedTextFrame(arrayOf("needle"))), "needle", false).resultCount)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `matches inside complex cells retain complete spans between ordinary runs`(textOnly: Boolean) {
        val terminal = TerminalBuffers.create(32, 1)
        terminal.writeText("ab")
        terminal.writeCluster(intArrayOf('e'.code, 'e'.code, 0x301))
        terminal.writeText("界")
        terminal.writeCluster(intArrayOf(0x1F469, 0x200D, 0x1F4BB))
        terminal.writeText("cd😀z")
        val reader = terminal as TerminalRenderFrameReader
        val cache = TerminalRenderCache(32, 1)
        cache.updateFrom(reader)
        val cases =
            listOf(
                Triple("e", 2, 3),
                Triple("\u0301", 2, 3),
                Triple("\u0301界\uD83D", 2, 7),
                Triple("\uDC69", 5, 7),
                Triple("\u200D", 5, 7),
                Triple("\uDCBBc", 5, 8),
                Triple("d\uD83D", 8, 11),
                Triple("\uDE00z", 9, 12),
                Triple("ab", 0, 2),
                Triple("cd", 7, 9),
            )
        val model = TerminalSearchModel()
        val copy = TerminalTextRangeCopy()
        for ((query, start, end) in cases) {
            val matches =
                if (textOnly) {
                    assertTrue(copy.read(reader, 0, 0))
                    model.begin(query, false, 0)
                    model.append(copy)
                    model.finish()
                } else {
                    model.search(cache, query, false)
                }
            val viewport = TerminalSearchViewportHighlights()
            matches.buildViewportHighlights(cache, viewport)
            val count = if (query == "e") 2 else 1
            assertEquals(count, matches.resultCount, query)
            assertEquals(count, viewport.segmentCount, query)
            repeat(count) {
                assertEquals(start, viewport.startColumn(it), query)
                assertEquals(end, viewport.endColumn(it), query)
            }
        }
    }

    @Test
    fun `discarded pending geometry cannot leak into a later row`() {
        val model = TerminalSearchModel()
        model.begin("needle", false, 0)
        model.append(renderCache(WrappedTextFrame(arrayOf("need"), booleanArrayOf(true))), 0)
        model.discardPendingLine()
        model.append(renderCache(WrappedTextFrame(arrayOf("needle"))), 7)
        val matches = model.finish()
        assertEquals(1, matches.resultCount)
        assertEquals(7L, matches.activeStartAbsoluteRow())
        assertEquals(0, matches.activeStartColumn())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `prefix matching agrees with literal non overlapping search`(ignoreCase: Boolean) {
        val random = Random(719)
        val alphabet = "abABiI\u0130\u0131kK\u212AsS\u017F\u03C3\u03C2\u03A3\u00DF\u1E9E"
        val cases =
            mutableListOf(
                "aaaaa" to "aa",
                "ababababca" to "abababca",
                "aaaaabaaaaab" to "aaaab",
                "ababababababca" to "ababababca",
                "aaaaaaaaaaaaaaaaa" to "aaaaaaaaa",
                "aaaaabaaaaabaaaaab" to "aaaaabaaaaab",
                "Ii\u0130\u0131Ii\u0130\u0131Ii\u0130\u0131" to "iI\u0131\u0130iI\u0131\u0130i",
                "\u03C3\u03C2\u03A3".repeat(5) to "\u03A3\u03C3\u03C2".repeat(3),
                "Ii\u0130\u0131Ii\u0130\u0131" to "iI\u0131",
                "\u03C3\u03C2\u03A3\u03C3\u03C2\u03A3" to "\u03A3\u03C3",
                "K\u212AkK\u212A" to "kk",
                "abc" to "",
                "abc" to "abcdef",
            )
        repeat(200) {
            val text = buildString { repeat(random.nextInt(1, 97)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            val query =
                if (it % 2 == 0) {
                    val start = random.nextInt(text.length)
                    text.substring(start, minOf(text.length, start + random.nextInt(1, 13)))
                } else {
                    buildString { repeat(random.nextInt(1, 13)) { append(alphabet[random.nextInt(alphabet.length)]) } }
                }
            cases += text to query
        }
        val model = TerminalSearchModel()
        for ((text, query) in cases) {
            val expected = mutableListOf<Int>()
            if (query.isNotEmpty()) {
                var start = 0
                while (start <= text.length - query.length) {
                    if (query.indices.all { text[start + it].equals(query[it], ignoreCase) }) {
                        expected += start
                        start += query.length
                    } else {
                        start++
                    }
                }
            }
            val cache = renderCache(WrappedTextFrame(arrayOf(text)))
            val matches = model.search(cache, query, ignoreCase)
            val viewport = TerminalSearchViewportHighlights()
            matches.buildViewportHighlights(cache, viewport)
            val context = "text='$text', query='$query', ignoreCase=$ignoreCase"
            assertEquals(expected.size, matches.resultCount, context)
            assertEquals(expected.size, viewport.segmentCount, context)
            for (index in expected.indices) {
                assertEquals(expected[index], viewport.startColumn(index), context)
                assertEquals(expected[index] + query.length, viewport.endColumn(index), context)
            }
        }
    }

    @Test
    fun `prefix fallback preserves supplementary cluster spans across wrapping`() {
        val terminal = TerminalBuffers.create(3, 3)
        val emoji = intArrayOf(0x1F469, 0x200D, 0x1F4BB)
        repeat(3) { terminal.writeCluster(emoji) }
        terminal.writeText("x")
        val cache = TerminalRenderCache(3, 3)
        cache.updateFrom(terminal as TerminalRenderFrameReader)
        val matches = TerminalSearchModel().search(cache, "\uD83D\uDC69\u200D\uD83D\uDCBB".repeat(2) + "x", false)
        val viewport = TerminalSearchViewportHighlights()
        matches.buildViewportHighlights(cache, viewport)
        assertEquals(1, matches.resultCount)
        assertEquals(2, viewport.segmentCount)
        assertEquals(0, viewport.segmentCountForRow(0))
        assertEquals(1, viewport.segmentCountForRow(1))
        assertEquals(1, viewport.segmentCountForRow(2))
        assertEquals(0, viewport.startColumn(0))
        assertEquals(2, viewport.endColumn(0))
        assertEquals(0, viewport.startColumn(1))
        assertEquals(3, viewport.endColumn(1))
    }

    @Test
    fun `query preprocessing can be cancelled and model can be reused`() {
        val model = TerminalSearchModel()
        val cancelled = CancellationException("stop preparing query")
        var checks = 0
        assertSame(
            cancelled,
            assertThrows(CancellationException::class.java) {
                model.begin("a".repeat(8192) + "b", true, 0, { if (++checks == 3) throw cancelled })
            },
        )
        assertEquals(1, model.search(renderCache(WrappedTextFrame(arrayOf("needle"))), "needle", false).resultCount)
    }

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
    fun `finishing pending results observes cancellation`(query: String) {
        val cache = renderCache(WrappedTextFrame(arrayOf("a".repeat(8192)), wrapped = booleanArrayOf(true)))
        val model = TerminalSearchModel()
        val cancelled = CancellationException("stop matching")
        var cancel = false
        model.begin(query, false, 0, { if (cancel) throw cancelled })
        model.append(cache, 0)
        cancel = true
        assertSame(cancelled, assertThrows(CancellationException::class.java) { model.finish() })
        assertEquals(1, model.search(renderCache(WrappedTextFrame(arrayOf("needle"))), "needle", false).resultCount)
    }

    @Test
    fun `streaming matches remain discardable until the logical line completes`() {
        val model = TerminalSearchModel()
        model.begin("ab", false, 0)
        model.append(renderCache(WrappedTextFrame(arrayOf("ab"), booleanArrayOf(true))), 0)
        assertEquals(0, model.completedResults.resultCount)
        model.discardPendingLine()
        model.append(renderCache(WrappedTextFrame(arrayOf("cd"))), 8)
        assertEquals(0, model.finish().resultCount)
    }

    @Test
    fun `streaming trailing-space matches commit only when later text arrives`() {
        val model = TerminalSearchModel()
        for (tail in listOf("    ", "   x")) {
            model.begin("ab ", false, 0)
            model.append(renderCache(WrappedTextFrame(arrayOf("ab  "), booleanArrayOf(true))), 0)
            assertEquals(0, model.completedResults.resultCount)
            model.append(renderCache(WrappedTextFrame(arrayOf(tail))), 1)
            val results = model.finish()
            assertEquals(if (tail.endsWith('x')) 1 else 0, results.resultCount)
            if (results.resultCount != 0) {
                assertEquals(0L, results.activeStartAbsoluteRow())
                assertEquals(0, results.activeStartColumn())
            }
        }
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
