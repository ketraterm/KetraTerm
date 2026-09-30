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
package io.github.ketraterm.benchmark

import io.github.ketraterm.render.api.TerminalRenderFrame
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.api.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.openjdk.jmh.annotations.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext

/**
 * One changed frame through EDT snapshotting, active viewport detection, and EDT publication.
 *
 * Scenarios encode columns, rows, and URLs per row without a Cartesian parameter expansion.
 * The last character of the last visible URL changes on each invocation. Completion requires
 * that URL's published action to open its new complete target; an invalidation notification
 * alone cannot finish the operation. The detector requests the same viewport context as
 * IntelliJ, but uses a deterministic token scan. Each result captures its own target string
 * and action, whose construction is included. IntelliJ filters, read actions, and painting
 * are outside this measurement.
 *
 * The measured duration includes dispatch and waiting, not just CPU execution. The GC profiler
 * includes worker and EDT allocations as well as the benchmark's completion handshake. Compare
 * [TerminalHyperlinkDiscoveryBenchmark] for the separate paused-worker frame-carry workload.
 */
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
open class TerminalHyperlinkDiscoveryCompletionBenchmark {
    @Param("80x24x1", "160x48x5", "240x48x8")
    @JvmField
    var scenario: String = ""

    private lateinit var cache: TerminalRenderCache
    private lateinit var frame: UpdatingFrame
    private lateinit var controller: TerminalHyperlinkDiscoveryController
    private lateinit var scope: CoroutineScope
    private lateinit var changedTargets: Array<String>
    private lateinit var expectedTarget: String
    private var openedTarget: String? = null
    private var lastLinkStartColumn = 0
    private var changedColumn = 0
    private val published = Semaphore(0)
    private var detectorCalls = 0L
    private var detectedLines = 0L
    private var emittedLinks = 0L
    private var installedId = 0
    private var publishedGeneration = 0L
    private val updateFrame =
        Runnable {
            frame.generation++
            expectedTarget = changedTargets[(frame.generation % changedTargets.size).toInt()]
            cache.accept(frame)
            cache.codeWords[cache.rowOffset(cache.rows - 1) + changedColumn] = expectedTarget.last().code
            controller.scheduleForFrame()
        }

    @Setup(Level.Trial)
    open fun setup() {
        val (columns, rows, linksPerRow) = scenario.split('x').map(String::toInt)
        val expectedLinks = rows * linksPerRow
        val lines =
            List(rows) { row ->
                val urls = (0 until linksPerRow).joinToString(" ") { "https://example.com/$row/$it" }
                require(urls.length < columns - 3)
                urls.padEnd(columns - 2) + "0%"
            }
        lastLinkStartColumn = lines.last().lastIndexOf("https://")
        expectedTarget = lines.last().substring(lastLinkStartColumn).substringBefore(' ')
        changedColumn = lastLinkStartColumn + expectedTarget.lastIndex
        changedTargets = Array(10) { expectedTarget.dropLast(1) + it }
        SwingUtilities.invokeAndWait {
            frame = UpdatingFrame(lines)
            cache = TerminalRenderCache(columns, rows).apply { accept(frame) }
            scope = CoroutineScope(SupervisorJob() + EdtDispatcher)
            val host =
                object : TerminalHyperlinkDiscoveryHost {
                    override val renderCache = cache
                    override val hyperlinkDetector =
                        object : SwingHyperlinkDetector {
                            override val context = SwingHyperlinkDetectionContext.ORDERED_CONTENT

                            override suspend fun detect(
                                request: SwingHyperlinkDetectionRequest,
                                sink: SwingHyperlinkDetectionSink,
                            ) {
                                check(!SwingUtilities.isEventDispatchThread())
                                detectorCalls++
                                detectedLines += request.lineCount
                                for (line in 0 until request.lineCount) {
                                    val text = request.lineText(line)
                                    var start = text.indexOf("https://")
                                    while (start >= 0) {
                                        var end = start
                                        while (end < text.length && text[end] != ' ' && text[end] != '\n') end++
                                        val target = text.substring(start, end)
                                        val action =
                                            SwingHyperlinkAction {
                                                openedTarget = target
                                                true
                                            }
                                        sink.addHyperlink(
                                            request.hyperlink(
                                                line,
                                                start,
                                                end,
                                                action,
                                                validationStartOffset = maxOf(0, start - 1),
                                                validationEndOffset = minOf(text.length, end + 1),
                                            ),
                                        )
                                        emittedLinks++
                                        start = text.indexOf("https://", end)
                                    }
                                }
                            }
                        }

                    override fun hyperlinksChanged() {
                        check(SwingUtilities.isEventDispatchThread())
                        if (publishedGeneration == frame.generation) return
                        val id = controller.hyperlinkIdAt(cache.rows - 1, lastLinkStartColumn, cache)
                        if (id >= 0) return
                        openedTarget = null
                        if (!controller.openDiscoveredHyperlink(id, cache) || openedTarget != expectedTarget) return
                        installedId = id
                        publishedGeneration = frame.generation
                        published.release()
                    }

                    override fun repaintHyperlinkSpan(
                        startRow: Int,
                        startColumn: Int,
                        endRow: Int,
                        endColumn: Int,
                    ) = Unit
                }
            controller = TerminalHyperlinkDiscoveryController(host, scope)
            controller.scheduleForFrame()
        }
        awaitPublication()
        check(detectorCalls == 1L && detectedLines == rows.toLong() && emittedLinks == expectedLinks.toLong())
        SwingUtilities.invokeAndWait {
            for (row in 0 until rows) {
                var start = lines[row].indexOf("https://")
                while (start >= 0) {
                    val id = controller.hyperlinkIdAt(row, start, cache)
                    check(id < 0 && controller.isDiscoveredHyperlinkResolvable(id, cache))
                    start = lines[row].indexOf("https://", start + 1)
                }
            }
        }
    }

    @Benchmark
    open fun refreshUntilPublished(counters: WorkCounters): Int {
        val callsBefore = detectorCalls
        val linesBefore = detectedLines
        val linksBefore = emittedLinks
        SwingUtilities.invokeAndWait(updateFrame)
        awaitPublication()
        val calls = detectorCalls - callsBefore
        val lines = detectedLines - linesBefore
        val links = emittedLinks - linksBefore
        counters.detectorCalls += calls
        counters.detectedLines += lines
        counters.emittedLinks += links
        return installedId
    }

    private fun awaitPublication() {
        check(published.tryAcquire(10, TimeUnit.SECONDS)) { "Hyperlink discovery did not publish its completed result" }
        check(installedId < 0 && publishedGeneration == frame.generation && openedTarget == expectedTarget) {
            "Completed discovery did not activate the edited URL's complete new target"
        }
    }

    @TearDown(Level.Trial)
    open fun tearDown() {
        SwingUtilities.invokeAndWait {
            controller.dispose()
            scope.cancel()
        }
    }

    /** Completed detector work; JMH reports event totals for each measured iteration. */
    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    open class WorkCounters {
        @JvmField var detectorCalls = 0L

        @JvmField var detectedLines = 0L

        @JvmField var emittedLinks = 0L
    }

    private class UpdatingFrame(
        lines: List<String>,
    ) : TerminalRenderFrame by TerminalRenderBenchmarkFrame(lines) {
        var generation = 1L
        override val frameGeneration: Long get() = generation
        override val contentGeneration: Long get() = generation

        override fun lineGeneration(row: Int): Long = if (row == rows - 1) generation else 1L
    }

    private object EdtDispatcher : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !SwingUtilities.isEventDispatchThread()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = SwingUtilities.invokeLater(block)
    }
}
