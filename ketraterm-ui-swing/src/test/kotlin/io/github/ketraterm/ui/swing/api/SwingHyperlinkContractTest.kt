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

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.render.cache.TerminalRenderCache
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SwingHyperlinkContractTest {
    @Test
    fun `backward results cross batches and empty producer replay removes every old span`() {
        val terminal = TerminalBuffers.create(width = 16, height = 3)
        for (text in listOf("first", "second", "producer")) {
            if (text != "first") {
                terminal.carriageReturn()
                terminal.newLine()
            }
            terminal.writeText(text)
        }
        val cache = TerminalRenderCache(16, 3).apply { updateFrom(terminal as TerminalRenderFrameReader) }
        val index = TerminalHyperlinkIndex()
        val context = SwingHyperlinkDetectionContext.ORDERED_CONTENT
        index.update(cache, context)
        val lines = index.pendingLines(context)
        index.acceptResults(lines.take(2), emptyList(), context, null)
        val source = SwingHyperlinkTextRange(SwingHyperlinkTextPosition(0, 1), SwingHyperlinkTextPosition(1, 3))
        val dependency = SwingHyperlinkTextRange(SwingHyperlinkTextPosition(0, 0), SwingHyperlinkTextPosition(2, 9))
        index.acceptResults(lines.takeLast(1), listOf(SwingHyperlink(source, dependency, { true })), context, null)
        index.writeOverlay(cache) { _, _, _, _ -> }
        val id = index.idsFor(cache)[1]
        assertTrue(id < 0)
        assertEquals(id, index.idsFor(cache)[cache.rowOffset(1)])
        index.restartOrdered()
        index.acceptResults(lines.take(2), emptyList(), context, null)
        index.writeOverlay(cache) { _, _, _, _ -> }
        assertEquals(id, index.idsFor(cache)[1], "An earlier replay batch must preserve results produced later")
        index.acceptResults(lines.takeLast(1), emptyList(), context, null)
        index.writeOverlay(cache) { _, _, _, _ -> }
        assertEquals(0, index.idsFor(cache)[1])
        assertEquals(0, index.idsFor(cache)[cache.rowOffset(1)])
        assertNull(index.hyperlinkFor(id, cache))
    }

    @Test
    fun `overlaps prefer visible then narrower then provider order with OSC8 first`() {
        val terminal = TerminalBuffers.create(width = 16, height = 1)
        terminal.writeText("abcdefgh")
        val cache = TerminalRenderCache(16, 1).apply { updateFrom(terminal as TerminalRenderFrameReader) }
        cache.hyperlinkIds[3] = 42
        val index = TerminalHyperlinkIndex()
        index.update(cache)
        val lines = index.pendingLines()
        val request = detectionRequest(lines)

        fun link(
            start: Int,
            end: Int,
            visible: Boolean,
            provider: Int,
            uri: String,
        ) = SwingHyperlink(
            request.range(0, start, 0, end),
            request.range(0, 0, 0, 9),
            { true },
            uri,
            SwingHyperlinkPresentation(isVisible = visible),
            providerOrder = provider,
        )
        index.acceptResults(
            lines,
            listOf(
                link(0, 8, false, 0, "implicit"),
                link(1, 7, true, 0, "wide"),
                link(2, 6, true, 2, "later"),
                link(2, 6, true, 1, "narrow"),
            ),
            SwingHyperlinkDetectionContext.INDEPENDENT_LINE,
            null,
        )
        index.writeOverlay(cache) { _, _, _, _ -> }
        val ids = index.idsFor(cache)
        assertEquals("implicit", index.hyperlinkFor(ids[0], cache)?.uri)
        assertEquals("wide", index.hyperlinkFor(ids[1], cache)?.uri)
        assertEquals("narrow", index.hyperlinkFor(ids[2], cache)?.uri)
        assertEquals(42, ids[3])
        assertEquals("narrow", index.hyperlinkFor(ids[4], cache)?.uri)
    }

    @Test
    fun `logical coordinates preserve UTF16 soft wrap anchors and owned input`() {
        val texts = arrayListOf("a\uD83D\uDE00b\n", "next\n")
        val first = longArrayOf(40L, 44L)
        val last = longArrayOf(43L, 44L)
        val request =
            SwingHyperlinkDetectionRequest(
                texts,
                first,
                last,
                SwingHyperlinkDetectionContext.ORDERED_CONTENT,
                3L,
                4L,
                5L,
            )
        texts[0] = "replaced\n"
        first.fill(0L)
        last.fill(0L)
        assertEquals("a\uD83D\uDE00b\n", request.lineText(0))
        assertEquals(5, request.lineStartOffset(1))
        assertEquals(10, request.lineEndOffset(1))
        assertEquals(40L, request.lineFirstAbsoluteRow(0))
        assertEquals(43L, request.lineLastAbsoluteRow(0))
        assertEquals(3L, request.bindingEpoch)
        assertEquals(4L, request.sourceEpoch)
        assertEquals(5L, request.providerEpoch)
        val result = request.hyperlink(0, 1, 3, SwingHyperlinkAction.NONE, uri = "https://example.invalid/complete")
        assertEquals(SwingHyperlinkTextPosition(40L, 1), result.sourceRange.start)
        assertEquals(SwingHyperlinkTextPosition(40L, 3), result.sourceRange.end)
        assertEquals(request.range(0, 0, 1, 5), result.dependencyRange)
        assertEquals(result.dependencyRange.end, result.consumedThrough)
        assertEquals("https://example.invalid/complete", result.uri)
    }

    @Test
    fun `independent dependency and explicit token dependency use absolute logical anchors`() {
        val request = SwingHyperlinkDetectionRequest(listOf("prefix\n", "url suffix\n"), longArrayOf(10, 12))
        assertEquals(request.range(1, 0, 1, 11), request.hyperlink(1, 0, 3, SwingHyperlinkAction.NONE).dependencyRange)
        val result =
            request.hyperlink(
                1,
                0,
                3,
                SwingHyperlinkAction.NONE,
                0,
                4,
                activation = SwingHyperlinkActivation.DIRECT,
            )
        assertEquals(request.range(1, 0, 1, 4), result.dependencyRange)
        assertEquals(SwingHyperlinkActivation.DIRECT, result.activation)
        assertEquals(0, SwingHyperlinkDetectionRequest(emptyList(), LongArray(0)).lineCount)
    }

    @Test
    fun `malformed coordinates and incomplete logical line geometry are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { SwingHyperlinkTextPosition(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { SwingHyperlinkTextPosition(0, -1) }
        assertThrows(IllegalArgumentException::class.java) {
            SwingHyperlinkDetectionRequest(listOf("no terminator"), longArrayOf(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SwingHyperlinkDetectionRequest(listOf("a\n"), LongArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SwingHyperlinkDetectionRequest(listOf("a\n", "b\n"), longArrayOf(0, 1), longArrayOf(1, 1))
        }
        val request = SwingHyperlinkDetectionRequest(listOf("url\n"), longArrayOf(0))
        assertThrows(IllegalArgumentException::class.java) { request.hyperlink(0, 2, 1, SwingHyperlinkAction.NONE) }
        assertThrows(IllegalArgumentException::class.java) { request.hyperlink(0, 0, 3, SwingHyperlinkAction.NONE, 1, 4) }
        assertThrows(IllegalArgumentException::class.java) {
            SwingHyperlink(
                request.range(0, 0, 3),
                request.range(0, 0, 4),
                SwingHyperlinkAction.NONE,
                consumedThrough = SwingHyperlinkTextPosition(0, 3),
            )
        }
    }

    @Test
    fun `ordered result can highlight earlier lines and retains complete metadata in every span`() {
        val terminal = TerminalBuffers.create(width = 8, height = 3)
        terminal.writeText("first")
        terminal.carriageReturn()
        terminal.newLine()
        terminal.writeText("second")
        terminal.carriageReturn()
        terminal.newLine()
        terminal.writeText("producer")
        val cache = TerminalRenderCache(8, 3).apply { updateFrom(terminal as TerminalRenderFrameReader) }
        val builder = TerminalHyperlinkLineSnapshotBuilder()
        val lines = List(3) { builder.snapshot(cache, it, it + 1) }
        val request = detectionRequest(lines, SwingHyperlinkDetectionContext.ORDERED_CONTENT)
        val style = SwingHyperlinkStyle(foregroundArgb = 0xFF123456.toInt())
        val presentation = SwingHyperlinkPresentation(normal = style, hovered = style, isVisible = true)
        val result =
            SwingHyperlink(
                request.range(0, 1, 1, 3),
                request.range(0, 0, 2, request.lineText(2).length),
                SwingHyperlinkAction.NONE,
                "https://example.invalid/full",
                presentation,
                SwingHyperlinkActivation.DIRECT,
                SwingHyperlinkTextPosition(2, request.lineText(2).length),
                providerOrder = 7,
            )
        val sink = TerminalHyperlinkDetectionAccumulator(lines)
        sink.addHyperlink(result)
        assertEquals(1, sink.links[0].size)
        assertEquals(1, sink.links[1].size)
        assertTrue(sink.links[2].isEmpty())
        assertEquals(1, sink.links[0].single().startOffset)
        assertEquals(5, sink.links[0].single().endOffset, "Trailing newline is not a highlighted cell")
        assertEquals(3, sink.links[1].single().endOffset)
        for (index in 0..1) {
            val span = sink.links[index].single()
            assertSame(result, span.hyperlink)
            assertTrue(span.viewportDependent)
            assertSame(style, span.hyperlink.presentation.normal)
            assertEquals(7, span.hyperlink.providerOrder)
        }
        val size = sink.links.sumOf { it.size }
        sink.addHyperlink(SwingHyperlink(request.range(0, 0, 0, 100), request.range(0, 0, 0, 100), SwingHyperlinkAction.NONE))
        sink.addHyperlink(
            SwingHyperlink(
                SwingHyperlinkTextRange(SwingHyperlinkTextPosition(100, 0), SwingHyperlinkTextPosition(100, 3)),
                SwingHyperlinkTextRange(SwingHyperlinkTextPosition(100, 0), SwingHyperlinkTextPosition(100, 3)),
                SwingHyperlinkAction.NONE,
            ),
        )
        assertEquals(size, sink.links.sumOf { it.size }, "Invalid results must not partially mutate accumulated spans")
    }

    private fun SwingHyperlinkDetectionRequest.range(
        line: Int,
        start: Int,
        end: Int,
    ) = range(line, start, line, end)
}
