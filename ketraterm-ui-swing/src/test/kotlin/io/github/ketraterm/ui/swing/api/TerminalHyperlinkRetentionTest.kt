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
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.render.api.TerminalRenderFrameConsumer
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.render.cache.TerminalRenderCache
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.FutureTask
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext

class TerminalHyperlinkRetentionTest {
    @Test
    fun `unchanged offscreen wrapped tail keeps its action through unrelated live output`() {
        Fixture(8, 4, 100).use { fixture ->
            val url = "https://example.invalid/" + "a".repeat(100)
            fixture.output(url)
            fixture.output("footer")
            fixture.show()
            fixture.settle()
            fixture.showAbsolute(11)
            val id = fixture.idAt(0)
            assertTrue(id < 0)
            fixture.terminal.positionCursor(0, 3)
            fixture.terminal.writeText("changed")
            fixture.showAbsolute(11)
            assertEquals(id, fixture.idAt(0))
            fixture.settle()
            assertEquals(id, fixture.idAt(0))
            assertTrue(fixture.openAt(0))
            assertEquals(url, fixture.opened.last())
        }
    }

    @Test
    fun `unpublished unrelated output cannot block an unchanged independent result`() {
        val release = CompletableDeferred<Unit>()
        Fixture(80, 3, 100, beforeDetection = { release.await() }).use { fixture ->
            val url = "https://example.invalid/stable"
            fixture.output(url)
            fixture.output("progress 0%")
            fixture.show()
            fixture.settle()
            fixture.terminal.positionCursor(0, 1)
            fixture.terminal.writeText("progress 1%")
            release.complete(Unit)
            fixture.settle()
            assertTrue(fixture.openAt(0))
            assertEquals(url, fixture.opened.last())
        }
    }

    @Test
    fun `an offscreen live edit invalidates the visible wrapped prefix before analysis`() {
        Fixture(8, 3, 200).use { fixture ->
            val url = "https://example.invalid/" + "a".repeat(100)
            fixture.output(url)
            fixture.show()
            fixture.settle()
            fixture.showAbsolute(0)
            val previous = fixture.idAt(0)
            assertTrue(previous < 0)
            fixture.terminal.positionCursor(0, 2)
            fixture.terminal.writeText("z")
            fixture.showAbsolute(0)
            assertEquals(0, fixture.idAt(0))
            assertFalse(fixture.resolvable(previous))
            fixture.settle()
            assertTrue(fixture.openAt(0))
            assertNotEquals(url, fixture.opened.last())
        }
    }

    @Test
    fun `cold bind prioritizes visible links before backfilling older history`() {
        Fixture(80, 24, 2000).use { fixture ->
            repeat(1000) { fixture.output("https://example.invalid/$it") }
            fixture.show()
            fixture.settle()
            assertEquals("https://example.invalid/976\n", fixture.detected.first())
            assertTrue(fixture.openAt(0))
            fixture.showAbsolute(0)
            assertTrue(fixture.openAt(0))
            assertEquals("https://example.invalid/0", fixture.opened.last())
        }
    }

    @Test
    fun `unpublished edits during provider suspension cannot publish obsolete actions`() {
        val release = CompletableDeferred<Unit>()
        Fixture(80, 3, 100, beforeDetection = { release.await() }).use { fixture ->
            fixture.output("https://example.invalid/old")
            fixture.show()
            fixture.settle()
            assertEquals(1, fixture.requestSizes.size)
            fixture.terminal.positionCursor(0, 0)
            fixture.terminal.writeText("https://example.invalid/new")
            release.complete(Unit)
            fixture.settle()
            assertFalse(fixture.openAt(0), "The cache still has old text, but the source already changed")
            fixture.show()
            fixture.settle()
            assertTrue(fixture.openAt(0))
            assertEquals("https://example.invalid/new", fixture.opened.last())
        }
    }

    @Test
    fun `unpublished reset rejects an otherwise identical provider result`() {
        val release = CompletableDeferred<Unit>()
        Fixture(80, 3, 100, beforeDetection = { release.await() }).use { fixture ->
            val url = "https://example.invalid/same"
            fixture.output(url)
            fixture.show()
            fixture.settle()
            assertEquals(1, fixture.requestSizes.size)
            fixture.terminal.reset()
            fixture.terminal.writeText(url)
            release.complete(Unit)
            fixture.settle()
            assertFalse(fixture.openAt(0))
            fixture.show()
            fixture.settle()
            assertTrue(fixture.openAt(0))
        }
    }

    @Test
    fun `every content generation value distinguishes unprocessed and analyzed source`() {
        val index = TerminalHyperlinkIndex()
        for (generation in longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0L)) {
            index.clear()
            assertFalse(index.isSourceAnalyzed(generation))
            index.nextSourceRow = 100L
            index.beginSourceScan(generation, 50L)
            assertEquals(50L, index.nextSourceRow)
            index.beginSourceScan(generation, 20L)
            assertEquals(50L, index.nextSourceRow, "The same demand keeps its in-progress frontier")
            index.finishSourceScan(generation)
            assertTrue(index.isSourceAnalyzed(generation))
        }
    }

    @Test
    fun `a different physical line at the same anchor cannot inherit an identical URL's occurrence`() {
        Fixture(80, 3, 100).use { fixture ->
            val url = "https://example.invalid/repeated"
            fixture.output(url)
            fixture.output(url)
            fixture.show()
            fixture.settle()
            val removed = fixture.idAt(0)
            assertNotEquals(removed, fixture.idAt(1))
            fixture.terminal.positionCursor(0, 0)
            fixture.terminal.deleteLines(1)
            fixture.show()
            assertEquals(0, fixture.idAt(0))
            assertFalse(fixture.resolvable(removed))
            fixture.settle()
            assertNotEquals(removed, fixture.idAt(0))
            assertTrue(fixture.openAt(0))
            assertEquals(url, fixture.opened.last())
        }
    }

    @Test
    fun `progress updates preserve a token-scoped occurrence before and after rediscovery`() {
        Fixture(80, 24, 100).use { fixture ->
            val url = "https://example.invalid/progress"
            fixture.output("$url 0%")
            fixture.show()
            fixture.settle()
            val id = fixture.idAt(0)
            fixture.terminal.positionCursor(url.length + 1, 0)
            fixture.terminal.writeText("1%")
            fixture.show()
            assertEquals(id, fixture.idAt(0))
            assertTrue(fixture.openAt(0))
            fixture.settle()
            assertEquals(id, fixture.idAt(0), "Unchanged occurrences must not get a new action slot after analysis")
            assertTrue(fixture.openAt(0))
            assertEquals(listOf(url, url), fixture.opened)
        }
    }

    @Test
    fun `ordered requests stay bounded as retained history grows and a live target changes`() {
        Fixture(80, 24, 1200, ordered = true).use { fixture ->
            repeat(1000) { fixture.output("https://example.invalid/$it") }
            fixture.show()
            fixture.settle()
            assertTrue(fixture.requestSizes.all { it <= 64 })
            fixture.showAbsolute(40)
            assertTrue(fixture.openAt(0))
            val survivingId = fixture.idAt(0)
            assertEquals("https://example.invalid/40", fixture.opened.last())
            val previousWork = fixture.detected.size
            fixture.terminal.positionCursor(0, 0)
            fixture.terminal.writeText("https://example.invalid/XYZ")
            fixture.showAbsolute(40)
            fixture.settle()
            assertTrue(fixture.detected.size - previousWork >= 1000, "An ordered edit must reconstruct earlier provider state")
            assertTrue(fixture.requestSizes.all { it <= 64 }, "Replay must publish bounded batches")
            assertEquals(survivingId, fixture.idAt(0), "Replay must preserve the unaffected occurrence")
            fixture.show()
            assertTrue(fixture.openAt(0))
            assertEquals("https://example.invalid/XYZ", fixture.opened.last())
        }
    }

    @ParameterizedTest
    @CsvSource("80,24", "160,48", "240,48")
    fun `history and successful empty results are ready before their first display`(
        columns: Int,
        rows: Int,
    ) {
        Fixture(columns, rows, 400).use { fixture ->
            repeat(200) { fixture.output(if (it % 3 == 0) "plain $it" else "https://example.invalid/$it") }
            fixture.show()
            fixture.settle()
            val calls = fixture.detected.size
            fixture.showAbsolute(40)
            assertTrue(fixture.idAt(0) < 0)
            assertTrue(fixture.openAt(0))
            assertEquals("https://example.invalid/40", fixture.opened.last())
            fixture.showAbsolute(39)
            assertEquals(0, fixture.idAt(0))
            fixture.settle()
            assertEquals(calls, fixture.detected.size, "Scrolling prepared content must not invoke detection")
            fixture.output("https://example.invalid/new")
            fixture.showAbsolute(40)
            fixture.settle()
            assertEquals(1, fixture.detected.count { it == "https://example.invalid/40\n" })
        }
    }

    @Test
    fun `saturated eviction preserves surviving occurrence identities and retires only lost actions`() {
        Fixture(64, 2, 4).use { fixture ->
            repeat(8) { fixture.output("https://example.invalid/$it") }
            fixture.show()
            fixture.settle()
            fixture.showAbsolute(2)
            val evicted = fixture.idAt(0)
            fixture.showAbsolute(3)
            val survivor = fixture.idAt(0)
            fixture.output("https://example.invalid/8")
            fixture.showAbsolute(3)
            assertEquals(survivor, fixture.idAt(0))
            assertTrue(fixture.openAt(0))
            assertEquals("https://example.invalid/3", fixture.opened.last())
            assertFalse(fixture.resolvable(evicted))
            fixture.settle()
            assertEquals(survivor, fixture.idAt(0))
            assertEquals(1, fixture.detected.count { it == "https://example.invalid/3\n" })
        }
    }

    @Test
    fun `partial wrapped eviction retains the complete destination through source reconciliation`() {
        Fixture(8, 3, 6).use { fixture ->
            val url = "https://example.invalid/" + "a".repeat(32)
            fixture.output(url)
            fixture.output("footer")
            fixture.show()
            fixture.settle()
            fixture.showAbsolute(0)
            val id = fixture.idAt(0)
            assertTrue(id < 0)
            repeat(3) { fixture.output("next") }
            fixture.showAbsolute(2)
            assertEquals(2L, fixture.cache.discardedCount)
            assertEquals(id, fixture.idAt(0))
            fixture.settle()
            assertEquals(id, fixture.idAt(0), "An unchanged retained suffix must keep its full prepared occurrence")
            assertTrue(fixture.openAt(0))
            assertEquals(url, fixture.opened.last())
            repeat(5) { fixture.output("tail") }
            fixture.show()
            fixture.settle()
            assertFalse(fixture.resolvable(id))
        }
    }

    @Test
    fun `a wrapped target spanning copy batches projects its clipped middle with its full destination`() {
        Fixture(8, 3, 200).use { fixture ->
            val url = "https://example.invalid/" + "a".repeat(900)
            fixture.output(url)
            fixture.output("footer")
            fixture.show()
            fixture.settle()
            assertEquals(1, fixture.detected.count { it == "$url\n" })
            fixture.showAbsolute(65)
            val id = fixture.idAt(0)
            assertTrue(id < 0)
            for (row in 0 until fixture.cache.rows) {
                for (column in 0 until fixture.cache.columns) assertEquals(id, fixture.idAt(row, column))
            }
            assertTrue(fixture.openAt(1))
            assertEquals(url, fixture.opened.last())
            val calls = fixture.detected.size
            fixture.settle()
            assertEquals(calls, fixture.detected.size)
        }
    }

    @Test
    fun `primary and alternate results restore independently and clearing validates the restored generation`() {
        Fixture(64, 3, 10).use { fixture ->
            fixture.output("https://example.invalid/primary")
            fixture.show()
            fixture.settle()
            val primary = fixture.idAt(0)
            fixture.terminal.enterAltBufferWithoutCursorSave(clearBeforeEnter = false)
            fixture.terminal.writeText("https://example.invalid/alternate")
            fixture.show()
            fixture.settle()
            val alternate = fixture.idAt(0)
            assertNotEquals(primary, alternate)
            fixture.terminal.exitAltBufferWithoutCursorRestore()
            fixture.show()
            assertEquals(primary, fixture.idAt(0))
            fixture.settle()
            assertEquals(1, fixture.detected.count { it == "https://example.invalid/primary\n" })
            fixture.terminal.enterAltBufferWithoutCursorSave(clearBeforeEnter = false)
            fixture.show()
            assertEquals(alternate, fixture.idAt(0))
            fixture.settle()
            fixture.terminal.exitAltBufferWithoutCursorRestore()
            fixture.show()
            fixture.terminal.enterAltBufferWithoutCursorSave(clearBeforeEnter = true)
            fixture.show()
            assertEquals(0, fixture.idAt(0))
            assertFalse(fixture.resolvable(alternate))
            fixture.terminal.exitAltBufferWithoutCursorRestore()
            fixture.show()
            assertEquals(primary, fixture.idAt(0))
        }
    }

    @Test
    fun `offscreen live edits are analyzed while the viewport stays in history`() {
        Fixture(64, 3, 100).use { fixture ->
            repeat(30) { fixture.output("https://example.invalid/$it") }
            fixture.show()
            fixture.settle()
            val old = fixture.idAt(0)
            fixture.showAbsolute(5)
            fixture.terminal.positionCursor(0, 0)
            fixture.terminal.writeText("https://example.invalid/XX")
            fixture.showAbsolute(5)
            fixture.settle()
            fixture.show()
            assertTrue(fixture.openAt(0))
            assertEquals("https://example.invalid/XX", fixture.opened.last())
            assertFalse(fixture.resolvable(old))
        }
    }

    @Test
    fun `an edited live row admitted to history before publication is reconciled`() {
        Fixture(64, 3, 100).use { fixture ->
            repeat(30) { fixture.output("https://example.invalid/$it") }
            fixture.show()
            fixture.settle()
            fixture.showAbsolute(5)
            fixture.terminal.positionCursor(0, 0)
            fixture.terminal.writeText("https://example.invalid/XX")
            fixture.terminal.positionCursor(0, 2)
            fixture.output("https://example.invalid/new")
            fixture.showAbsolute(5)
            fixture.settle()
            fixture.showAbsolute(27)
            assertTrue(fixture.openAt(0))
            assertEquals("https://example.invalid/XX", fixture.opened.last())
        }
    }

    @Test
    fun `reflow retires old occurrences and discovers complete targets in the new layout`() {
        Fixture(16, 3, 100).use { fixture ->
            val url = "https://example.invalid/reflow"
            fixture.output(url)
            fixture.show()
            fixture.settle()
            val old = fixture.idAt(0)
            fixture.terminal.resize(8, 3)
            fixture.show()
            assertFalse(fixture.resolvable(old))
            fixture.settle()
            fixture.showAbsolute(0)
            assertTrue(fixture.openAt(0))
            assertEquals(url, fixture.opened.last())
            assertNotEquals(old, fixture.idAt(0))
        }
    }

    @Test
    fun `a source ahead of the published viewport drains demand without retrying it indefinitely`() {
        Fixture(64, 3, 100).use { fixture ->
            fixture.output("https://example.invalid/old")
            fixture.show()
            fixture.terminal.positionCursor(0, 0)
            fixture.terminal.writeText("https://example.invalid/new")
            fixture.settle()
            assertFalse(fixture.openAt(0))
            fixture.show()
            fixture.settle()
            assertTrue(fixture.openAt(0))
            assertEquals("https://example.invalid/new", fixture.opened.last())
        }
    }

    @Test
    fun `source changes during a bounded copy reject the batch`() =
        runTest {
            val terminal = TerminalBuffers.create(80, 24, maxHistory = 100)
            terminal.writeText("https://example.invalid/old")
            val source = terminal as TerminalRenderFrameReader
            var history = 0L
            source.readRenderFrame { history = it.historyContentGeneration }
            val reader =
                object : TerminalRenderFrameReader by source {
                    override fun readRenderFrameForAbsoluteRange(
                        startAbsoluteRow: Long,
                        endAbsoluteRow: Long,
                        consumer: TerminalRenderFrameConsumer,
                    ) {
                        source.readRenderFrameForAbsoluteRange(startAbsoluteRow, endAbsoluteRow, consumer)
                        terminal.positionCursor(0, 0)
                        terminal.writeText("https://example.invalid/new")
                    }
                }
            assertNull(TerminalHyperlinkSourceScan().scan(reader, 0, TerminalRenderBufferKind.PRIMARY, 80, history))
        }

    @Test
    fun `stable action lookup survives removal and repeated table reuse without retargeting`() {
        val actions = TerminalHyperlinkActions()
        val range = SwingHyperlinkTextRange(SwingHyperlinkTextPosition(0, 0), SwingHyperlinkTextPosition(0, 1))
        val original = SwingHyperlink(range, range, SwingHyperlinkAction.NONE, uri = "https://example.invalid/original")
        val retained = actions.add(original)
        actions.retain(retained)
        var removed = 0
        repeat(10_000) {
            val id = actions.add(SwingHyperlink(range, range, SwingHyperlinkAction.NONE))
            actions.retain(id)
            assertNotEquals(removed, id)
            assertSame(original, actions.get(retained))
            actions.release(id)
            assertNull(actions.get(id))
            removed = id
        }
        actions.release(retained)
        assertNull(actions.get(retained))
    }

    private class Fixture(
        columns: Int,
        rows: Int,
        history: Int,
        ordered: Boolean = false,
        beforeDetection: suspend () -> Unit = {},
    ) : AutoCloseable {
        val terminal: TerminalBuffer = TerminalBuffers.create(columns, rows, maxHistory = history)
        private val source = terminal as TerminalRenderFrameReader
        val cache = TerminalRenderCache(columns, rows)
        val detected = ArrayList<String>()
        val requestSizes = ArrayList<Int>()
        val opened = ArrayList<String>()
        private val ui = StandardTestDispatcher()
        private val worker = QueuedDispatcher()
        private val scope = CoroutineScope(SupervisorJob() + ui)
        val controller =
            TerminalHyperlinkDiscoveryController(
                object : TerminalHyperlinkDiscoveryHost {
                    override val renderCache = cache
                    override val hyperlinkSource = source
                    override val hyperlinkDetector =
                        object : SwingHyperlinkDetector {
                            override val context =
                                if (ordered) {
                                    SwingHyperlinkDetectionContext.ORDERED_CONTENT
                                } else {
                                    SwingHyperlinkDetectionContext.INDEPENDENT_LINE
                                }

                            override suspend fun detect(
                                request: SwingHyperlinkDetectionRequest,
                                sink: SwingHyperlinkDetectionSink,
                            ) {
                                assertFalse(SwingUtilities.isEventDispatchThread())
                                requestSizes.add(request.lineCount)
                                beforeDetection()
                                for (index in 0 until request.lineCount) {
                                    val text = request.lineText(index)
                                    detected.add(text)
                                    if (!text.startsWith("https://")) continue
                                    val url = text.takeWhile { !it.isWhitespace() }
                                    sink.addHyperlink(
                                        request.hyperlink(index, 0, url.length, {
                                            assertTrue(SwingUtilities.isEventDispatchThread())
                                            opened.add(url)
                                        }, validationEndOffset = url.length + 1, uri = url),
                                    )
                                }
                            }
                        }

                    override fun hyperlinksChanged() {
                        assertTrue(SwingUtilities.isEventDispatchThread())
                    }

                    override fun repaintHyperlinkSpan(
                        startRow: Int,
                        startColumn: Int,
                        endRow: Int,
                        endColumn: Int,
                    ) {}
                },
                scope,
                worker,
            )
        private var firstOutput = true

        fun output(text: String) {
            if (!firstOutput) {
                terminal.carriageReturn()
                terminal.newLine()
            }
            firstOutput = false
            terminal.writeText(text)
        }

        fun show(offset: Int = 0) =
            onEdt {
                cache.updateFrom(source, offset)
                controller.scheduleForFrame()
            }

        fun showAbsolute(row: Long) {
            var top = 0L
            source.readRenderFrame { top = it.discardedCount + it.historySize }
            show((top - row).coerceAtLeast(0).toInt())
        }

        fun idAt(
            row: Int,
            column: Int = 0,
        ): Int = onEdt { controller.hyperlinkIdAt(row, column, cache) }

        fun openAt(row: Int): Boolean = onEdt { controller.openDiscoveredHyperlink(idAt(row), cache) }

        fun resolvable(id: Int): Boolean = onEdt { controller.isDiscoveredHyperlinkResolvable(id, cache) }

        fun settle() {
            onEdt { ui.scheduler.runCurrent() }
            var turns = 0
            while (worker.pending) {
                check(++turns < 1000) { "Discovery did not become idle" }
                worker.runCurrent()
                onEdt { ui.scheduler.runCurrent() }
            }
        }

        private fun <T> onEdt(action: () -> T): T {
            if (SwingUtilities.isEventDispatchThread()) return action()
            val task = FutureTask(Callable(action))
            SwingUtilities.invokeAndWait(task)
            return task.get()
        }

        override fun close() {
            onEdt {
                controller.dispose()
                scope.cancel()
            }
            settle()
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        val pending: Boolean get() = queue.isNotEmpty()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            queue.add(block)
        }

        fun runCurrent() {
            while (true) (queue.poll() ?: return).run()
        }
    }
}
