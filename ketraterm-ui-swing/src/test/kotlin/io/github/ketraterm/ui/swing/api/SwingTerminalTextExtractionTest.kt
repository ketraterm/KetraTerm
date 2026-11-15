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
import io.github.ketraterm.render.api.TerminalRenderFrameConsumer
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalShellIntegrationFactory
import io.github.ketraterm.session.TerminalShellIntegrationState
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.settings.SwingPromptDecoration
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.*
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SwingTerminalTextExtractionTest {
    @ParameterizedTest
    @CsvSource(
        "selection,deliver",
        "command,deliver",
        "selection,clear",
        "selection,unbind",
        "command,dispose",
        "selection,replace",
        "selection,explicit",
    )
    fun `blocked extraction leaves EDT responsive and only current copy is delivered`(
        kind: String,
        transition: String,
    ) {
        Fixture().use { fixture ->
            try {
                edtCall {
                    fixture.holdReads = true
                    assertTrue(
                        if (kind ==
                            "command"
                        ) {
                            fixture.view.copyCommandOutputToClipboard(fixture.record)
                        } else {
                            fixture.view.copySelectionToClipboard()
                        },
                    )
                }
                assertTrue(fixture.entered.await(10, TimeUnit.SECONDS))
                val heartbeat =
                    FutureTask {
                        when (transition) {
                            "clear" -> fixture.view.clearSelection()
                            "unbind" -> fixture.view.unbind()
                            "dispose" -> fixture.view.dispose()
                            "replace" -> assertTrue(fixture.view.copyCommandOutputToClipboard(fixture.record))
                            "explicit" -> assertTrue(fixture.view.copyTextToClipboard("newer copy"))
                        }
                        assertEquals(if (transition == "explicit") listOf("newer copy") else emptyList(), fixture.copies)
                    }
                SwingUtilities.invokeLater(heartbeat)
                heartbeat.get(10, TimeUnit.SECONDS)
                fixture.release.countDown()
                val delivered = transition == "deliver" || transition == "replace" || transition == "explicit"
                val expected = if (transition == "explicit") "newer copy" else fixture.expected
                if (delivered) assertEquals(expected, fixture.delivered.get(10, TimeUnit.SECONDS))
                fixture.drainWorker()
                edtCall { assertEquals(if (delivered) listOf(expected) else emptyList(), fixture.copies) }
            } finally {
                fixture.release.countDown()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["selection", "block", "command", "cancel", "reflow", "unbind"])
    fun `suspending queries retain complete history and reject obsolete work`(kind: String) {
        Fixture(block = kind == "block").use { fixture ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                fixture.holdReads = true
                val result =
                    scope.async {
                        if (kind == "command") fixture.view.commandOutputTextAsync(fixture.record) else fixture.view.selectedTextAsync()
                    }
                assertTrue(fixture.entered.await(10, TimeUnit.SECONDS))
                edtCall {
                    if (kind == "cancel") result.cancel()
                    if (kind == "unbind") fixture.view.unbind()
                    assertFalse(result.isCompleted && !result.isCancelled)
                }
                if (kind == "reflow") fixture.buffer.resize(8, 4)
                fixture.release.countDown()
                runBlocking {
                    withTimeout(10.seconds) {
                        if (kind == "cancel" || kind == "unbind") {
                            result.join()
                            assertTrue(result.isCancelled)
                        } else {
                            assertEquals(if (kind == "reflow") null else fixture.expected, result.await())
                        }
                    }
                }
                edtCall { assertTrue(fixture.copies.isEmpty()) }
            } finally {
                fixture.release.countDown()
                scope.cancel()
            }
        }
    }

    private class Fixture(
        private val block: Boolean = false,
    ) : AutoCloseable {
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val scheduler = StandardTestDispatcher()
        val buffer = TerminalBuffers.create(16, 4, maxHistory = 600)
        val expected =
            (0 until 400).joinToString("\n") {
                val line = "line $it λ"
                if (block) line.substring(2, 7).trimEnd() else line
            }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        @Volatile var holdReads = false
        private val reader =
            object : TerminalRenderFrameReader by buffer {
                override fun readRenderFrameForAbsoluteRange(
                    startAbsoluteRow: Long,
                    endAbsoluteRow: Long,
                    consumer: TerminalRenderFrameConsumer,
                ) {
                    if (holdReads) {
                        assertFalse(SwingUtilities.isEventDispatchThread())
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                    buffer.readRenderFrameForAbsoluteRange(startAbsoluteRow, endAbsoluteRow, consumer)
                }
            }
        private val state = TerminalShellIntegrationState()
        val record: Int
        val session: TerminalSession
        val view: SwingTerminal
        val copies = mutableListOf<String>()
        val delivered = CompletableFuture<String>()

        init {
            repeat(400) {
                if (it != 0) {
                    buffer.newLine()
                    buffer.carriageReturn()
                }
                buffer.writeText("line $it λ")
            }
            buffer.readRenderFrameForAbsoluteRange(0, Long.MAX_VALUE) {
                state.recordPromptStart(it.lineId(0))
                state.recordCommandStart(it.lineId(0), includeLine = true)
                state.recordCommandFinished(it.lineId(it.rows - 1), exitCode = 0)
            }
            record = state.latestCommandRecordId()
            session =
                TerminalSession.create(
                    buffer,
                    reader,
                    NoOpConnector,
                    shellIntegration = TerminalShellIntegrationFactory.host(state),
                    workerDispatcher = scheduler,
                    ioDispatcher = scheduler,
                    cleanupDispatcher = scheduler,
                )
            view =
                edtCall {
                    SwingTerminal(
                        {
                            SwingSettings.create {
                                it.columns = 16
                                it.rows = 4
                                it.promptDecoration = SwingPromptDecoration.NONE
                                it.stickyCommandHeadersEnabled =
                                    false
                            }
                        },
                        SwingHostServices.create {
                            it.uiDispatcher = CONTROLLED_SWING_UI_DISPATCHER
                            it.clipboardHandler =
                                object : TerminalClipboardHandler {
                                    override fun copyText(text: String) {
                                        assertTrue(SwingUtilities.isEventDispatchThread())
                                        copies += text
                                        delivered.complete(text)
                                    }

                                    override fun readText(): String? = null
                                }
                        },
                        searchDispatcher = worker,
                        mutationDispatcher = scheduler,
                    ).also {
                        it.size = it.preferredGridSize(16, 4)
                        it.bind(session)
                        scheduler.scheduler.runCurrent()
                        val range = assertNotNull(it.createSelectionRange(if (block) 2 else 0, 0, if (block) 7 else 16, 399, block))
                        assertTrue(it.setSelection(range))
                    }
                }
        }

        fun drainWorker() {
            CompletableFuture.runAsync({}, worker.executor).get(10, TimeUnit.SECONDS)
            edtCall {}
        }

        override fun close() {
            edtCall {
                view.dispose()
                session.close()
                scheduler.scheduler.runCurrent()
            }
            worker.close()
        }
    }

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
