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
import io.github.ketraterm.session.TerminalShellIntegrationFactory
import io.github.ketraterm.session.TerminalShellIntegrationState
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.api.TerminalCommandInteractionController
import io.github.ketraterm.ui.swing.api.TerminalCommandInteractionHost
import io.github.ketraterm.ui.swing.api.TerminalSelectionController
import io.github.ketraterm.ui.swing.api.TerminalSelectionHost
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingSettings
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

/**
 * Thread-confined UI controller work, without EDT dispatch, ingestion, painting or transport.
 * Histories have 100 columns and 28 live rows. Commands span 12 historical rows; selections
 * span every retained row. Only coldReveal constructs a controller/host inside measurement.
 * Source text and semantic expectations are built and checked in trial setup.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@Threads(1)
open class TerminalHistoryInteractionBenchmark {
    @Param("100", "10000")
    var historyRows: Int = 0

    private lateinit var session: TerminalSession
    private lateinit var viewport: TerminalRenderCache
    private lateinit var commands: TerminalCommandInteractionController
    private lateinit var linear: TerminalSelectionController
    private lateinit var block: TerminalSelectionController
    private var recordId = 0

    @Setup
    fun setup() {
        val terminal = TerminalBuffers.create(100, 28, maxHistory = historyRows)
        val line = "0123456789".repeat(9)
        repeat(historyRows + 27) {
            terminal.writeText(line)
            terminal.carriageReturn()
            terminal.newLine()
        }
        val state = TerminalShellIntegrationState()
        terminal.readRenderFrameForAbsoluteRange(0, Long.MAX_VALUE) { frame ->
            val start = historyRows / 2
            state.recordPromptStart(frame.lineId(start))
            state.recordCommandStart(frame.lineId(start), includeLine = true)
            state.recordCommandFinished(frame.lineId(start + 11), exitCode = 0)
        }
        recordId = state.latestCommandRecordId()
        session = TerminalSession.create(terminal, NoOpConnector, shellIntegration = TerminalShellIntegrationFactory.host(state))
        viewport = TerminalRenderCache(100, 28).also { it.updateFrom(session) }
        commands = TerminalCommandInteractionController(commandHost())
        linear = selection(false)
        block = selection(true)
        check(commands.revealCommand(recordId))
        check(commands.selectCommandOutput(recordId))
        check(commands.commandOutputText(recordId) == List(12) { line }.joinToString("\n"))
        check(linear.getSelectedText(session) == List(historyRows + 27) { line }.joinToString("\n", postfix = "\n"))
        check(block.getSelectedText(session) == List(historyRows + 27) { "5678901" }.joinToString("\n", postfix = "\n"))
    }

    private fun selection(isBlock: Boolean): TerminalSelectionController =
        TerminalSelectionController(
            object : TerminalSelectionHost {
                override val settings: SwingSettings get() = error("not used")
                override val metrics: SwingMetrics get() = error("not used")
                override val renderCache: TerminalRenderCache get() = viewport
                override val contentYOffset = 0.0
                override val componentWidth = 1000
                override val componentHeight = 560

                override fun cellAt(
                    x: Int,
                    y: Int,
                ): Long = error("not used")

                override fun visualCellAt(
                    x: Int,
                    y: Int,
                ): Long = error("not used")

                override fun scrollViewportByRows(deltaRows: Int): Boolean = error("not used")

                override fun repaint() = Unit

                override fun requestFocusInWindow() = false

                override fun copySelection() = Unit
            },
        ).also { controller ->
            controller.updateFrame(viewport)
            session.readRenderFrame { frame ->
                val range =
                    requireNotNull(
                        controller.createRange(
                            frame,
                            if (isBlock) 5 else 0,
                            0,
                            if (isBlock) 12 else 100,
                            historyRows + 27L,
                            isBlock,
                        ),
                    )
                check(controller.setRange(range, frame))
            }
        }

    private fun commandHost(): TerminalCommandInteractionHost =
        object : TerminalCommandInteractionHost {
            override val session: TerminalSession get() = this@TerminalHistoryInteractionBenchmark.session
            override val renderCache: TerminalRenderCache get() = viewport

            override fun cellAt(
                x: Int,
                y: Int,
                cache: TerminalRenderCache,
            ): Long = error("not used")

            override fun commandNavigationAnchorRow() = 0

            override fun refreshRenderCacheFromSession(session: TerminalSession) = Unit

            override fun refreshShellIntegrationDecorations(session: TerminalSession) = false

            override fun selectAbsoluteRows(
                startAbsoluteRow: Long,
                endAbsoluteRow: Long,
                columns: Int,
            ) = Unit

            override fun scrollToAbsoluteRow(
                row: Long,
                center: Boolean,
            ) = true

            override fun repaint() = Unit
        }

    @Benchmark fun coldReveal(): Boolean = TerminalCommandInteractionController(commandHost()).revealCommand(recordId)

    @Benchmark fun reveal(): Boolean = commands.revealCommand(recordId)

    @Benchmark fun selectOutput(): Boolean = commands.selectCommandOutput(recordId)

    @Benchmark fun copyOutput(): String? = commands.commandOutputText(recordId)

    @Benchmark fun copyLinear(): String? = linear.getSelectedText(session)

    @Benchmark fun copyBlock(): String? = block.getSelectedText(session)

    @TearDown fun close() = session.close()

    private object NoOpConnector : TerminalConnector {
        override fun start(listener: TerminalConnectorListener) = Unit

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) = Unit

        override fun resize(
            columns: Int,
            rows: Int,
        ) = Unit

        override fun close() = Unit
    }
}
