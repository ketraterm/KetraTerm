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
package io.github.ketraterm.session

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.render.api.TerminalRenderClusterDataSink
import io.github.ketraterm.render.api.TerminalRenderFrame
import io.github.ketraterm.render.api.TerminalRenderFrameConsumer
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.*

class TerminalHostCommandOutputTextTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `both host factories extract wrapped Unicode output without a protocol producer`(versioned: Boolean) =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), versioned = versioned, columns = 12).use { fixture ->
                val output = "0123456789abcdef界ghi\r\nabc e\u0301 👩‍💻"
                val id = fixture.command(output)
                val expected = TerminalCommandOutputText(output.replace("\r\n", "\n"), truncated = false, historyIncomplete = false)
                val detached = fixture.session.commandOutputText(id)
                assertEquals(expected, detached)
                fixture.session.resizeViewport(7, 8)
                assertEquals(expected, fixture.session.commandOutputText(id))
                fixture.session.resizeViewport(20, 8)
                assertEquals(expected, fixture.session.commandOutputText(id))
                fixture.command("later")
                assertEquals(expected, detached)
                assertEquals(expected, fixture.session.commandOutputText(id))
                assertTrue(fixture.connector.writtenBytes.isEmpty())
            }
        }

    @Test
    fun `host output limits keep the tail and complete graphemes`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                val id = fixture.command("one\r\ntwo\r\nthree")
                assertEquals(
                    TerminalCommandOutputText("three", truncated = true, historyIncomplete = false),
                    fixture.session.commandOutputText(id, maxRows = 1),
                )
                assertEquals(
                    TerminalCommandOutputText("two\nthree", truncated = true, historyIncomplete = false),
                    fixture.session.commandOutputText(id, maxChars = 9),
                )
                assertEquals(
                    TerminalCommandOutputText("one\ntwo\nthree", truncated = false, historyIncomplete = false),
                    fixture.session.commandOutputText(id, 13, 3),
                )
                for (cluster in listOf("e\u0301", "😀", "👩‍💻", "🇦🇲")) {
                    val clustered = fixture.command("prefix${cluster}z")
                    assertEquals(
                        TerminalCommandOutputText("z", truncated = true, historyIncomplete = false),
                        fixture.session.commandOutputText(clustered, cluster.length),
                    )
                    assertEquals(
                        TerminalCommandOutputText("${cluster}z", truncated = true, historyIncomplete = false),
                        fixture.session.commandOutputText(clustered, cluster.length + 1),
                    )
                }
                assertFailsWith<IllegalArgumentException> { fixture.context.commandOutputText(fixture.state, id, 0, 1) }
                assertFailsWith<IllegalArgumentException> { fixture.context.commandOutputText(fixture.state, id, 1, 0) }
            }
        }

    @Test
    fun `host lifecycle distinguishes empty output from unfinished and missing output`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                assertNull(fixture.session.commandOutputText(123))
                val empty = fixture.command("")
                assertEquals(
                    TerminalCommandOutputText("", truncated = false, historyIncomplete = false),
                    fixture.session.commandOutputText(empty),
                )
                val unfinished = fixture.startCommand()
                fixture.feed("partial")
                assertNull(fixture.session.commandOutputText(unfinished))
                fixture.marker(fixture.state::recordPromptStart)
                assertNull(fixture.session.commandOutputText(unfinished))
                val completed = fixture.command("done")
                assertNotNull(fixture.session.commandOutputText(completed))
                fixture.session.clearBuffer()
                assertNull(fixture.session.commandOutputText(completed))
                assertNotNull(fixture.state.commandMetadata(completed))
            }
        }

    @Test
    fun `host output reports lost history independently of requested limits`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), rows = 3, history = 2).use { fixture ->
                val id = fixture.command((1..8).joinToString("\r\n") { "line$it" })
                val result = assertNotNull(fixture.session.commandOutputText(id))
                assertTrue(result.historyIncomplete)
                assertFalse(result.truncated)
                assertTrue(result.text.endsWith("line8"))
                assertFalse(result.text.contains("line1"))
                assertEquals(
                    TerminalCommandOutputText("line8", truncated = true, historyIncomplete = true),
                    fixture.session.commandOutputText(id, maxRows = 1),
                )
                fixture.feed("\r\n".repeat(10))
                assertNull(fixture.session.commandOutputText(id))
            }
        }

    @Test
    fun `host output follows retained row order and rejects a deleted anchor`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                val id = fixture.command("one\r\n\u001b[Linserted")
                assertEquals(
                    TerminalCommandOutputText("one\ninserted", truncated = false, historyIncomplete = false),
                    fixture.session.commandOutputText(id),
                )
                fixture.feed("\u001b[2;1H\u001b[M")
                assertNull(fixture.session.commandOutputText(id))
            }
        }

    @Test
    fun `host output remains primary and retained after closure without taking model ownership`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                val id = fixture.command("primary")
                val expected = TerminalCommandOutputText("primary", truncated = false, historyIncomplete = false)
                fixture.feed("\u001b[?1049halternate")
                assertNull(fixture.session.commandOutputText(id))
                fixture.feed("\u001b[?1049l")
                assertEquals(expected, fixture.session.commandOutputText(id))
                fixture.session.close()
                assertEquals(expected, fixture.session.commandOutputText(id))
                fixture.state.recordCurrentWorkingDirectory("file:///after-close")
                assertEquals("file:///after-close", fixture.session.shellIntegrationState.currentWorkingDirectoryUri())
                fixture.state.clear()
                assertNull(fixture.session.commandOutputText(id))
            }
        }

    @Test
    fun `context rejects metadata that changes during extraction`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                val id = fixture.command("stable")
                val changing =
                    object : TerminalShellIntegrationView by fixture.state {
                        override fun copyCommandOutputRange(
                            recordId: Int,
                            destination: LongArray,
                            destinationOffset: Int,
                        ): Boolean {
                            val copied = fixture.state.copyCommandOutputRange(recordId, destination, destinationOffset)
                            fixture.state.recordCurrentWorkingDirectory("file:///changed")
                            return copied
                        }
                    }
                assertNull(fixture.context.commandOutputText(changing, id, 100, 10))
                assertEquals("stable", fixture.context.commandOutputText(fixture.state, id, 100, 10)?.text)
            }
        }

    @Test
    fun `custom producer output policy including explicit null stays authoritative`() =
        runTest {
            for (custom in listOf(null, TerminalCommandOutputText("host-owned", truncated = false, historyIncomplete = false))) {
                var calls = 0
                Fixture(
                    StandardTestDispatcher(testScheduler),
                    decorate = { producer ->
                        object : TerminalShellIntegration by producer {
                            override fun commandOutputText(
                                recordId: Int,
                                maxChars: Int,
                                maxRows: Int,
                            ): TerminalCommandOutputText? {
                                calls++
                                assertEquals(20, maxChars)
                                assertEquals(2, maxRows)
                                return custom
                            }
                        }
                    },
                ).use { fixture ->
                    val id = fixture.command("retained terminal output")
                    assertEquals("retained terminal output", fixture.context.commandOutputText(fixture.state, id, 100, 10)?.text)
                    assertSame(custom, fixture.session.commandOutputText(id, 20, 2))
                    assertEquals(1, calls)
                }
            }
        }

    @Test
    fun `row budget bounds cell copying instead of slicing all retained text`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                val id = fixture.command((1..40).joinToString("\r\n") { "line$it" })
                var copies = 0
                val reader =
                    object : TerminalRenderFrameReader by fixture.session {
                        override fun readRenderFrameForAbsoluteRange(
                            startAbsoluteRow: Long,
                            endAbsoluteRow: Long,
                            consumer: TerminalRenderFrameConsumer,
                        ) {
                            fixture.session.readRenderFrameForAbsoluteRange(startAbsoluteRow, endAbsoluteRow) { frame ->
                                consumer.accept(
                                    object : TerminalRenderFrame by frame {
                                        override fun copyTextLine(
                                            row: Int,
                                            codeWords: IntArray,
                                            codeOffset: Int,
                                            flags: IntArray,
                                            flagOffset: Int,
                                            clusterDataSink: TerminalRenderClusterDataSink?,
                                        ) {
                                            copies++
                                            frame.copyTextLine(row, codeWords, codeOffset, flags, flagOffset, clusterDataSink)
                                        }
                                    },
                                )
                            }
                        }
                    }
                assertEquals("line39\nline40", ShellCommandOutputTextExtractor.extract(reader, fixture.state, id, 100, 2)?.text)
                assertEquals(2, copies)
            }
        }

    private class Fixture(
        dispatcher: CoroutineDispatcher,
        versioned: Boolean = true,
        columns: Int = 30,
        rows: Int = 8,
        history: Int = 100,
        decorate: (TerminalShellIntegration) -> TerminalShellIntegration = { it },
    ) : AutoCloseable {
        val connector = MockConnector()
        val state = TerminalShellIntegrationState()
        lateinit var context: TerminalShellIntegrationContext
            private set
        private val host =
            if (versioned) {
                TerminalShellIntegrationFactory.host(state, TerminalShellCommandLineState())
            } else {
                TerminalShellIntegrationFactory.host(state)
            }
        val session =
            TerminalSession.create(
                TerminalBuffers.create(columns, rows, maxHistory = history),
                connector,
                shellIntegration =
                    TerminalShellIntegrationFactory { context ->
                        this.context = context
                        decorate(host.create(context))
                    },
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
                cleanupDispatcher = dispatcher,
            )

        init {
            session.start(columns, rows)
        }

        fun feed(text: String) = connector.feedFromHost(text.encodeToByteArray())

        fun marker(action: (Long) -> Unit) {
            context.withTerminalState {
                var lineId = 0L
                context.readRenderFrame { frame -> lineId = frame.lineId(frame.cursor.row) }
                check(lineId > 0)
                action(lineId)
            }
        }

        fun startCommand(): Int {
            marker(state::recordPromptStart)
            feed("$ ")
            marker(state::recordPromptEnd)
            feed("run\r\n")
            marker { state.recordCommandStart(it, includeLine = true, commandText = "run") }
            return state.latestCommandRecordId()
        }

        fun command(output: String): Int {
            val id = startCommand()
            if (output.isNotEmpty()) feed(output + "\r\n")
            marker { state.recordCommandFinished(it, 0) }
            marker(state::recordPromptStart)
            return id
        }

        override fun close() = session.close()
    }
}
