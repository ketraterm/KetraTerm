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

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.ui.swing.search.TerminalSearchController
import io.github.ketraterm.ui.swing.search.TerminalSearchHost
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext

/** Refreshes active search against unchanged or edited retained content; EDT dispatch is included per batch. */
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
open class TerminalSearchRefreshBenchmark {
    @Param("0", "1000", "10000")
    var historyRows = 0

    @Param("6", "80", "160")
    var columns = 0

    private lateinit var terminal: TerminalBuffer
    private lateinit var session: TerminalSession
    private lateinit var host: SearchHost
    private lateinit var controller: TerminalSearchController
    private val scope =
        CoroutineScope(
            SupervisorJob() +
                object : CoroutineDispatcher() {
                    override fun isDispatchNeeded(context: CoroutineContext) = !SwingUtilities.isEventDispatchThread()

                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) = SwingUtilities.invokeLater(block)
                },
        )
    private var segmentCount = 0
    private var uppercase = false
    private val refreshBatch =
        Runnable {
            repeat(REFRESHES_PER_BATCH) { controller.refreshForFrame() }
            segmentCount = controller.viewportHighlights.segmentCount
        }
    private val changingRefreshBatch =
        Runnable {
            uppercase = !uppercase
            terminal.positionCursor(0, 0)
            terminal.writeCodepoint(if (uppercase) 'N'.code else 'n'.code)
            host.renderCache.updateFrom(session)
            controller.refreshForFrame()
            segmentCount = controller.viewportHighlights.segmentCount
        }

    @Setup
    open fun setup() {
        terminal = TerminalBuffers.create(width = columns, height = 1, maxHistory = historyRows)
        repeat(historyRows + 1) { row ->
            if (row > 0) {
                terminal.carriageReturn()
                terminal.newLine()
            }
            for (character in "needle") terminal.writeCodepoint(character.code)
        }
        session = benchmarkSession(terminal)
        SwingUtilities.invokeAndWait {
            host = SearchHost(session, columns)
            host.renderCache.updateFrom(session)
            controller = TerminalSearchController(host, scope)
            controller.search("needle")
        }
        awaitSearch()
        SwingUtilities.invokeAndWait {
            check(controller.state().resultCount == historyRows + 1)
            refreshBatch.run()
            check(segmentCount == 1)
        }
    }

    @Benchmark
    @OperationsPerInvocation(REFRESHES_PER_BATCH)
    open fun refreshUnchangedSearchOnEdt(): Int {
        SwingUtilities.invokeAndWait(refreshBatch)
        return segmentCount
    }

    /** Includes editing, EDT scheduling, bounded worker copies/scanning and completed result publication. */
    @Benchmark
    @OperationsPerInvocation(CHANGING_REFRESHES_PER_BATCH)
    open fun refreshChangedSearchOnEdt(): Int {
        repeat(CHANGING_REFRESHES_PER_BATCH) {
            SwingUtilities.invokeAndWait(changingRefreshBatch)
            awaitSearch()
        }
        return segmentCount
    }

    private fun awaitSearch() =
        runBlocking {
            val result = controller.states.first { !it.isSearching }
            check(result.failure == null) { "Search failed: ${result.failure}" }
            check(result.resultCount == historyRows + 1)
        }

    @TearDown
    open fun tearDown() {
        try {
            SwingUtilities.invokeAndWait { controller.reset(1) }
        } finally {
            scope.cancel()
            session.close()
        }
    }

    private class SearchHost(
        override val session: TerminalSession,
        columns: Int,
    ) : TerminalSearchHost {
        override val renderCache = TerminalRenderCache(columns, 1)

        override fun visibleGridRows(): Int = 1

        override fun scrollViewportTo(
            offsetRows: Int,
            historySize: Int,
            boundSession: TerminalSession,
        ): Boolean {
            renderCache.updateFrom(boundSession, scrollbackOffset = offsetRows, viewportRows = 1)
            return true
        }

        override fun repaint() = Unit
    }

    private companion object {
        const val REFRESHES_PER_BATCH = 1024
        const val CHANGING_REFRESHES_PER_BATCH = 16
    }
}
