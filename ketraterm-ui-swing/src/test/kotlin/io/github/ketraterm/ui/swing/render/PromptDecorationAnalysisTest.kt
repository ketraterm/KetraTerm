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
package io.github.ketraterm.ui.swing.render

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.render.api.TerminalRenderFrameConsumer
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.session.TerminalShellIntegrationState
import io.github.ketraterm.ui.swing.api.edtCall
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PromptDecorationAnalysisTest {
    @ParameterizedTest
    @ValueSource(strings = ["replace", "reset", "dispose"])
    fun `blocked source leaves EDT responsive and obsolete projections cannot replace the latest demand`(transition: String) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ready = CompletableFuture<Unit>()
        val reads = AtomicInteger()
        val state = TerminalShellIntegrationState()
        val original = TerminalBuffers.create(8, 4)
        original.writeText("old")
        val replacement = TerminalBuffers.create(8, 4)
        replacement.writeText("new")
        val reader =
            object : TerminalRenderFrameReader by original {
                override fun readRenderFrameForAbsoluteRange(
                    startAbsoluteRow: Long,
                    endAbsoluteRow: Long,
                    consumer: TerminalRenderFrameConsumer,
                ) {
                    assertFalse(SwingUtilities.isEventDispatchThread())
                    reads.incrementAndGet()
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    original.readRenderFrameForAbsoluteRange(startAbsoluteRow, endAbsoluteRow, consumer)
                }
            }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
            val scope = CoroutineScope(SupervisorJob() + EdtDispatcher)
            var publications = 0
            val analysis =
                PromptDecorationAnalysis(scope, worker) {
                    assertTrue(SwingUtilities.isEventDispatchThread())
                    publications++
                    ready.complete(Unit)
                }
            val first = request(original, state).copy(reader = reader)
            val latest = request(replacement, state)
            try {
                edtCall { analysis.request(first) }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val heartbeat =
                    FutureTask {
                        if (transition != "replace") analysis.reset()
                        if (transition == "dispose") {
                            scope.cancel()
                        } else {
                            repeat(50) { analysis.request(first) }
                            analysis.request(latest)
                        }
                        assertNull(analysis.projection(latest))
                    }
                SwingUtilities.invokeLater(heartbeat)
                heartbeat.get(10, TimeUnit.SECONDS)
                release.countDown()
                if (transition == "dispose") {
                    // The executor barrier follows the held read and its cancelled UI continuation.
                    CompletableFuture.runAsync({}, worker.executor).get(10, TimeUnit.SECONDS)
                } else {
                    ready.get(10, TimeUnit.SECONDS)
                }
                edtCall {
                    assertEquals(if (transition == "dispose") 0 else 1, publications)
                    assertEquals(1, reads.get(), "Superseded intermediate demands must be coalesced")
                    if (transition == "dispose") assertNull(analysis.projection(latest)) else assertNotNull(analysis.projection(latest))
                    assertNull(analysis.projection(first))
                }
            } finally {
                release.countDown()
                edtCall {
                    analysis.reset()
                    scope.cancel()
                }
            }
        }
    }

    @Test
    fun `a source ahead of the viewport cannot publish until the matching frame is requested`() =
        runTest {
            val buffer = TerminalBuffers.create(8, 4)
            val state = TerminalShellIntegrationState()
            var publications = 0
            val analysis = PromptDecorationAnalysis(this, StandardTestDispatcher(testScheduler)) { publications++ }
            val oldFrame = request(buffer, state)
            buffer.writeText("new")
            analysis.request(oldFrame)
            runCurrent()
            assertNull(analysis.projection(oldFrame))
            assertEquals(0, publications)
            val current = request(buffer, state)
            analysis.request(current)
            runCurrent()
            assertNotNull(analysis.projection(current))
            assertEquals(1, publications)
        }

    @Test
    fun `reflow anchors retention and eviction publish detached projections`() =
        runTest {
            val buffer = TerminalBuffers.create(8, 4, maxHistory = 3)
            val state = TerminalShellIntegrationState()
            buffer.writeText("abcdefgh")
            var prompt = 0L
            buffer.readRenderFrame { prompt = it.lineId(0) }
            state.recordPromptStart(prompt)
            val analysis = PromptDecorationAnalysis(this, StandardTestDispatcher(testScheduler)) {}

            fun demand() = request(buffer, state).copy(anchorLineId = prompt, promptLineId = prompt)
            val first = demand()
            analysis.request(first)
            runCurrent()
            val before = assertNotNull(analysis.projection(first))
            assertTrue(before.hasDividerAt(0))
            assertEquals(prompt, before.retainedPromptLineId)
            buffer.resize(4, 4)
            val reflow = demand()
            analysis.request(reflow)
            runCurrent()
            val after = assertNotNull(analysis.projection(reflow))
            assertEquals(0L, after.resolvedAnchorRow)
            assertTrue(after.hasDividerAt(0))
            assertFalse(after.hasDividerAt(1), "A reflow continuation must not acquire another divider")
            repeat(12) {
                buffer.newLine()
                buffer.writeText("tail")
            }
            val eviction = demand()
            analysis.request(eviction)
            runCurrent()
            val evicted = assertNotNull(analysis.projection(eviction))
            assertEquals(0L, evicted.retainedPromptLineId)
            assertFalse(evicted.hasDividerAt(0))
            assertTrue(before.hasDividerAt(0), "Worker updates must not modify a published projection")
            assertEquals(prompt, before.retainedPromptLineId)
        }

    @Test
    fun `identical viewport demand does not reread the source`() =
        runTest {
            val buffer = TerminalBuffers.create(8, 4)
            val state = TerminalShellIntegrationState()
            var reads = 0
            val reader =
                object : TerminalRenderFrameReader by buffer {
                    override fun readRenderFrameForAbsoluteRange(
                        startAbsoluteRow: Long,
                        endAbsoluteRow: Long,
                        consumer: TerminalRenderFrameConsumer,
                    ) {
                        reads++
                        buffer.readRenderFrameForAbsoluteRange(startAbsoluteRow, endAbsoluteRow, consumer)
                    }
                }
            val analysis = PromptDecorationAnalysis(this, StandardTestDispatcher(testScheduler)) {}
            val demand = request(buffer, state).copy(reader = reader)
            analysis.request(demand)
            runCurrent()
            repeat(50) {
                analysis.request(demand)
                runCurrent()
            }
            assertEquals(1, reads)
        }

    private fun request(
        reader: TerminalRenderFrameReader,
        state: TerminalShellIntegrationState,
    ): PromptDecorationAnalysis.Request {
        var content = 0L
        var history = 0L
        reader.readRenderFrame {
            content = it.contentGeneration
            history = it.historyContentGeneration
        }
        return PromptDecorationAnalysis.Request(reader, state, content, history, state.revision.value, 4, false, 0, 0)
    }

    private object EdtDispatcher : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext) = !SwingUtilities.isEventDispatchThread()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = SwingUtilities.invokeLater(block)
    }
}
