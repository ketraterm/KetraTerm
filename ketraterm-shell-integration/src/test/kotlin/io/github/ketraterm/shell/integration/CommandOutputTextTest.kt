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
package io.github.ketraterm.shell.integration

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.render.api.TerminalRenderClusterDataSink
import io.github.ketraterm.render.api.TerminalRenderFrame
import io.github.ketraterm.render.api.TerminalRenderFrameConsumer
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.session.TerminalCommandOutputText
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CommandOutputTextTest {
    @Test
    fun `a final grapheme exceeding the character budget is omitted whole`() =
        runTest {
            fixture { session, connector ->
                val id = command(session, connector, "👩‍💻")
                assertEquals(TerminalCommandOutputText("", true, false), session.commandOutputText(id, maxChars = 1))
            }
        }

    @Test
    fun `exclusive command start excludes the input row`() =
        runTest {
            fixture { session, connector ->
                connector.feedFromHost((PROMPT + "run" + START + "\r\nresult\r\n" + FINISH + PROMPT).toByteArray())
                val id = session.shellIntegrationState.latestCommandRecordId()
                assertEquals(TerminalCommandOutputText("result", false, false), session.commandOutputText(id))
            }
        }

    @Test
    fun `command-finished callback cannot capture a partial ingress batch`() =
        runTest {
            fixture { session, connector ->
                var observed = false
                session.shellIntegrationState
                    .addCommandFinishedListener { metadata ->
                        observed = true
                        assertNull(session.commandOutputText(metadata.recordId))
                    }.use {
                        val id = command(session, connector, "result")
                        assertTrue(observed)
                        assertEquals("result", session.commandOutputText(id)?.text)
                    }
            }
        }

    @Test
    fun `reflow preserves wrapped logical output`() =
        runTest {
            fixture(columns = 12, rows = 8) { session, connector ->
                val output = "0123456789abcdef界ghi"
                val id = command(session, connector, output)
                assertEquals(output, session.commandOutputText(id)?.text)
                session.resizeViewport(7, 8)
                assertEquals(output, session.commandOutputText(id)?.text)
                session.resizeViewport(20, 8)
                assertEquals(output, session.commandOutputText(id)?.text)
            }
        }

    @Test
    fun `completed output excludes prompt and input and detaches from later output`() =
        runTest {
            fixture { session, connector ->
                val id = command(session, connector, "one\r\ntwo  \r\nthree")
                val result = session.commandOutputText(id)
                assertEquals(TerminalCommandOutputText("one\ntwo\nthree", false, false), result)
                command(session, connector, "later")
                assertEquals(result, session.commandOutputText(id))
                assertEquals("one\ntwo\nthree", result?.text)
                assertTrue(connector.writtenBytes.isEmpty())
            }
        }

    @Test
    fun `limits retain tail and distinguish exact fits`() =
        runTest {
            fixture { session, connector ->
                val id = command(session, connector, "one\r\ntwo\r\nthree")
                assertEquals(TerminalCommandOutputText("three", true, false), session.commandOutputText(id, maxRows = 1))
                assertEquals(TerminalCommandOutputText("two\nthree", true, false), session.commandOutputText(id, maxChars = 9))
                assertEquals(TerminalCommandOutputText("ree", true, false), session.commandOutputText(id, maxChars = 3))
                assertEquals(TerminalCommandOutputText("one\ntwo\nthree", false, false), session.commandOutputText(id, 13, 3))
                assertEquals("three", session.commandOutputText(id, Int.MAX_VALUE, 1)?.text)
            }
        }

    @Test
    fun `soft wraps preserve internal spaces and omit wide wrap padding`() =
        runTest {
            fixture(columns = 5) { session, connector ->
                val id = command(session, connector, "abcd界x\r\na  bcdef")
                assertEquals(TerminalCommandOutputText("abcd界x\na  bcdef", false, false), session.commandOutputText(id))
            }
        }

    @Test
    fun `character limits never split a stored grapheme or supplementary scalar`() =
        runTest {
            fixture { session, connector ->
                for (text in listOf("e\u0301", "😀", "👩‍💻", "🇦🇲")) {
                    val id = command(session, connector, "prefix${text}z")
                    assertEquals(TerminalCommandOutputText("z", true, false), session.commandOutputText(id, text.length))
                    assertEquals(TerminalCommandOutputText("${text}z", true, false), session.commandOutputText(id, text.length + 1))
                    assertEquals("prefix${text}z", session.commandOutputText(id)?.text)
                }
            }
        }

    @Test
    fun `ANSI styling is excluded and RTL stays in logical order`() =
        runTest {
            fixture { session, connector ->
                val id = command(session, connector, "\u001b[31mabc שלום\u001b[0m")
                assertEquals("abc שלום", session.commandOutputText(id)?.text)
            }
        }

    @Test
    fun `unknown unfinished abandoned and cleared commands remain unavailable`() =
        runTest {
            fixture { session, connector ->
                assertNull(session.commandOutputText(123))
                connector.feedFromHost((PROMPT + "run\r\n" + START + "partial").toByteArray())
                val id = session.shellIntegrationState.latestCommandRecordId()
                assertNull(session.commandOutputText(id))
                connector.feedFromHost(("\r\n" + PROMPT).toByteArray())
                assertNull(session.commandOutputText(id))
                val completed = command(session, connector, "done")
                assertNotNull(session.commandOutputText(completed))
                session.clearBuffer()
                assertNull(session.commandOutputText(completed))
            }
        }

    @Test
    fun `empty output is distinct from unavailable and blank lines are preserved`() =
        runTest {
            fixture { session, connector ->
                connector.feedFromHost((PROMPT + "run\r\n" + START + FINISH + PROMPT).toByteArray())
                val id = session.shellIntegrationState.latestCommandRecordId()
                assertEquals(TerminalCommandOutputText("", false, false), session.commandOutputText(id))
                val blankLines = command(session, connector, "one\r\n\r\nthree")
                assertEquals("one\n\nthree", session.commandOutputText(blankLines)?.text)
            }
        }

    @Test
    fun `evicted output reports missing history independently of caller truncation`() =
        runTest {
            fixture(rows = 3, history = 2) { session, connector ->
                val id = command(session, connector, (1..8).joinToString("\r\n") { "line$it" })
                val result = assertNotNull(session.commandOutputText(id))
                assertTrue(result.historyIncomplete)
                assertFalse(result.truncated)
                assertTrue(result.text.endsWith("line8"))
                assertFalse(result.text.contains("line1"))
                assertEquals(TerminalCommandOutputText("line8", true, true), session.commandOutputText(id, maxRows = 1))
                connector.feedFromHost("\r\n".repeat(10).toByteArray())
                assertNull(session.commandOutputText(id))
            }
        }

    @Test
    fun `alternate screen never supplies primary command output`() =
        runTest {
            fixture { session, connector ->
                val id = command(session, connector, "primary")
                connector.feedFromHost("\u001b[?1049halternate".toByteArray())
                assertNull(session.commandOutputText(id))
                connector.feedFromHost("\u001b[?1049l".toByteArray())
                assertEquals("primary", session.commandOutputText(id)?.text)
            }
        }

    @Test
    fun `retained output survives session closure and viewport changes`() =
        runTest {
            fixture { session, connector ->
                val id = command(session, connector, "one\r\ntwo")
                val before = session.commandOutputText(id)
                session.readRenderFrame(scrollbackOffset = 5) { }
                session.resizeViewport(12, 4)
                assertEquals(before, session.commandOutputText(id))
                session.close()
                assertEquals(before, session.commandOutputText(id))
            }
        }

    @Test
    fun `chunked markers and failed command output are captured`() =
        runTest {
            fixture { session, connector ->
                val bytes = (PROMPT + "run\r\n" + START + "error\r\n\u001b]133;D;1\u0007" + PROMPT).toByteArray()
                for (byte in bytes) connector.feedFromHost(byteArrayOf(byte))
                val id = session.shellIntegrationState.latestCommandRecordId()
                assertEquals(1, session.shellIntegrationState.commandMetadata(id)?.exitCode)
                assertEquals("error", session.commandOutputText(id)?.text)
            }
        }

    @Test
    fun `row budget bounds cell copying rather than slicing a full history cache`() =
        runTest {
            fixture { session, connector ->
                val id = command(session, connector, (1..40).joinToString("\r\n") { "line$it" })
                var copies = 0
                val reader =
                    object : TerminalRenderFrameReader by session {
                        override fun readRenderFrameForAbsoluteRange(
                            startAbsoluteRow: Long,
                            endAbsoluteRow: Long,
                            consumer: TerminalRenderFrameConsumer,
                        ) {
                            session.readRenderFrameForAbsoluteRange(startAbsoluteRow, endAbsoluteRow) { frame ->
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
                assertEquals(
                    "line39\nline40",
                    ShellCommandOutputTextExtractor().extract(reader, session.shellIntegrationState, id, 100, 2)?.text,
                )
                assertEquals(2, copies)
            }
        }

    private suspend fun kotlinx.coroutines.test.TestScope.fixture(
        columns: Int = 30,
        rows: Int = 5,
        history: Int = 100,
        block: suspend (TerminalSession, MockConnector) -> Unit,
    ) {
        val connector = MockConnector()
        val dispatcher = StandardTestDispatcher(testScheduler)
        TerminalSession
            .create(
                TerminalBuffers.create(columns, rows, maxHistory = history),
                connector,
                shellIntegration = OscShellIntegration,
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
                cleanupDispatcher = dispatcher,
            ).use { session ->
                session.start(columns, rows)
                block(session, connector)
            }
    }

    private fun command(
        session: TerminalSession,
        connector: MockConnector,
        output: String,
    ): Int {
        connector.feedFromHost((PROMPT + "run\r\n" + START + output + "\r\n" + FINISH + PROMPT).toByteArray())
        return session.shellIntegrationState.latestCommandRecordId()
    }

    private companion object {
        const val PROMPT = "\u001b]133;A\u0007$ \u001b]133;B\u0007"
        const val START = "\u001b]133;C\u0007"
        const val FINISH = "\u001b]133;D;0\u0007"
    }
}
