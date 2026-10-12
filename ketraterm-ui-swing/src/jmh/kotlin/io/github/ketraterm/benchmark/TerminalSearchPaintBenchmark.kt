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
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.ui.swing.api.SwingHostServices
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.api.TerminalViewportListener
import io.github.ketraterm.ui.swing.search.TerminalSearchModel
import io.github.ketraterm.ui.swing.search.TerminalSearchScan
import io.github.ketraterm.ui.swing.settings.SwingSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.openjdk.jmh.annotations.*
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.seconds

/**
 * IntelliJ huge-history fixture: 26,000 three-row ASCII records at 80 columns.
 * Loading, search-cache reset and initial oldest-viewport painting are outside timing.
 * The full path waits for completed search and the installed destination, then paints
 * to a BufferedImage. Invocation setup is included in GC-profiler totals.
 */
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
open class TerminalSearchPaintBenchmark {
    private lateinit var session: TerminalSession
    private lateinit var component: SwingTerminal
    private lateinit var cache: TerminalRenderCache
    private lateinit var graphics: Graphics2D
    private lateinit var image: BufferedImage
    private lateinit var scanner: TerminalSearchScan
    private val model = TerminalSearchModel()
    private var expectedOffset = -1
    private var installed = CompletableFuture<Unit>()
    private var count = 0
    private var pixel = 0

    @Setup(Level.Trial)
    open fun setup() {
        val terminal = TerminalBuffers.create(80, 24, maxHistory = 180_000)
        repeat(26_000) { record ->
            val prefix = "2026-08-07 12:34:56,%03d [%6d] INFO - request %d state=RUNNING ".format(record % 1000, record, record)
            terminal.writeText(prefix + "abcdefghijklmnopqrstuvwxyz-0123456789 ".repeat(3))
            terminal.carriageReturn()
            terminal.newLine()
        }
        check(terminal.historySize == 77_977)
        session = benchmarkSession(terminal)
        session.requestRender(0)
        runBlocking { withTimeout(30.seconds) { session.renderGeneration.first { it > 0 } } }
        SwingUtilities.invokeAndWait {
            component =
                SwingTerminal(
                    settingsProvider = {
                        SwingSettings.create {
                            it.columns = 80
                            it.rows = 24
                            it.cursorBlinkMillis = 0
                            it.useSystemFallbackFonts = false
                        }
                    },
                    hostServices =
                        SwingHostServices.create {
                            it.viewportListener =
                                TerminalViewportListener { _, _, _, _, _ ->
                                    if (::cache.isInitialized && cache.hasFrame && cache.scrollbackOffset == expectedOffset) {
                                        installed.complete(Unit)
                                    }
                                }
                        },
                )
            component.size = component.preferredGridSize(80, 24)
            // Verify installed content, not just the requested scroll position.
            val field = SwingTerminal::class.java.getDeclaredField("renderCache")
            field.isAccessible = true
            cache = field.get(component) as TerminalRenderCache
            image = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_ARGB)
            graphics = image.createGraphics()
        }
    }

    @Setup(Level.Invocation)
    open fun prepareSearch() {
        scanner = TerminalSearchScan()
        SwingUtilities.invokeAndWait {
            expectedOffset = 0
            installed = CompletableFuture()
            component.bind(session)
            component.setSearchCaseSensitive(true)
            if (cache.hasFrame && cache.scrollbackOffset == 0) installed.complete(Unit)
        }
        installed.get(30, TimeUnit.SECONDS)
        SwingUtilities.invokeAndWait {
            check(cache.historySize in 77_976..77_977)
            expectedOffset = cache.historySize
            installed = CompletableFuture()
            component.scrollToScrollbackOffset(expectedOffset)
            if (cache.scrollbackOffset == expectedOffset) installed.complete(Unit)
        }
        installed.get(30, TimeUnit.SECONDS)
        SwingUtilities.invokeAndWait {
            check(cache.scrollbackOffset == cache.historySize)
            component.paint(graphics)
            expectedOffset = 0
            installed = CompletableFuture()
        }
    }

    @Benchmark
    open fun workerSearch(): Int =
        runBlocking(Dispatchers.Default) {
            count = requireNotNull(scanner.scan(session, model, QUERY, false)).highlights.resultCount
            count
        }

    @Benchmark
    open fun searchThroughSoftwarePaint(): Int {
        SwingUtilities.invokeAndWait { component.search(QUERY) }
        val state = runBlocking { withTimeout(30.seconds) { component.searchState.first { !it.isSearching } } }
        check(state.failure == null) { "Search failed: ${state.failure}" }
        count = state.resultCount
        installed.get(30, TimeUnit.SECONDS)
        SwingUtilities.invokeAndWait {
            check(cache.scrollbackOffset == 0 && component.viewportState().renderOffset == 0)
            component.paint(graphics)
            pixel = image.getRGB(image.width / 2, image.height / 2)
        }
        return pixel
    }

    @TearDown(Level.Invocation)
    open fun verifySearch() {
        check(count == 1) { "Expected one match, got $count" }
    }

    @TearDown(Level.Trial)
    open fun close() {
        try {
            SwingUtilities.invokeAndWait {
                component.dispose()
                graphics.dispose()
            }
        } finally {
            session.close()
        }
    }

    private companion object {
        const val QUERY = "request 25999 "
    }
}
