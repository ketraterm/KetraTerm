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
package io.github.ketraterm.completion.session

import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import io.github.ketraterm.completion.api.TerminalCompletionContext
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.api.TerminalShellCapabilities
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.session.*
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionShellCompletionProviderTest {
    @Test
    fun `POSIX inventories supply commands and current aliases without invoking the native completer`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), "gi").use { fixture ->
                val request = TerminalCompletionRequest("gi", 2, shellCapabilities = TerminalShellCapabilities.POSIX)
                val context = TerminalCompletionContext.resolve(request)
                assertNull(fixture.provider.complete(request, context, 1))
                fixture.channel.publishCommands(
                    TerminalShellCommandInventory(
                        listOf(
                            TerminalShellCompletionCandidate("git", "Git", "", TerminalShellCompletionKind.COMMAND),
                            TerminalShellCompletionCandidate("gish", "gish", "", TerminalShellCompletionKind.COMMAND),
                            TerminalShellCompletionCandidate("gs", "gs", "", TerminalShellCompletionKind.COMMAND, "git status"),
                        ),
                    ),
                )
                val answer = assertNotNull(fixture.provider.complete(request, context, 1))
                assertEquals(1, answer.candidates.size)
                val candidate = answer.candidates.single()
                assertEquals("shell", candidate.source)
                assertEquals(TerminalCompletionCandidateKind.COMMAND, candidate.kind)
                assertEquals(0, candidate.replacementStartOffset)
                assertEquals(2, candidate.replacementEndOffset)
                assertTrue(candidate.replacementText.startsWith("gi"))
                assertEquals(mapOf("gs" to "git status"), fixture.provider.commandAliases())
                fixture.channel.publishCommands(TerminalShellCommandInventory(emptyList()))
                assertTrue(fixture.provider.commandAliases().isEmpty())
                assertTrue(assertNotNull(fixture.provider.complete(request, context, 1)).candidates.isEmpty())
                runCurrent()
                assertEquals("", fixture.connector.writtenText())
            }
        }

    @Test
    fun `native results preserve quoted edits ranges metadata order and limits`() =
        runTest {
            val line = "tool -Value n"
            Fixture(StandardTestDispatcher(testScheduler), line).use { fixture ->
                val request = TerminalCompletionRequest(line, line.length, shellCapabilities = TerminalShellCapabilities.POWERSHELL)
                val context = TerminalCompletionContext.resolve(request)
                val query = async { fixture.provider.complete(request, context, 2) }
                runCurrent()
                assertEquals("e", fixture.connector.writtenText())
                fixture.respond(
                    TerminalShellCompletionResult(
                        line,
                        line.length,
                        12,
                        13,
                        listOf(
                            TerminalShellCompletionCandidate(
                                "'native value'",
                                "native value",
                                "value detail",
                                TerminalShellCompletionKind.ARGUMENT,
                            ),
                            TerminalShellCompletionCandidate(
                                "'native path'",
                                "native path",
                                "path detail",
                                TerminalShellCompletionKind.DIRECTORY,
                            ),
                            TerminalShellCompletionCandidate("omitted", "omitted", "", TerminalShellCompletionKind.OPTION),
                        ),
                        externalCommandName = "tool.exe",
                    ),
                )
                runCurrent()
                val answer = assertNotNull(query.await())
                assertEquals("tool.exe", answer.externalCommandName)
                assertEquals(listOf("'native value'", "'native path'"), answer.candidates.map { it.replacementText })
                assertEquals(listOf("value detail", "path detail"), answer.candidates.map { it.detail })
                assertEquals(listOf(3, 2), answer.candidates.map { it.score })
                assertEquals(
                    listOf(TerminalCompletionCandidateKind.ARGUMENT, TerminalCompletionCandidateKind.PATH),
                    answer.candidates.map { it.kind },
                )
                for (candidate in answer.candidates) {
                    assertEquals(12, candidate.replacementStartOffset)
                    assertEquals(13, candidate.replacementEndOffset)
                    assertEquals(context.expectedValueDomain, candidate.valueDomain)
                    assertEquals("powershell", candidate.source)
                }
            }
        }

    @Test
    fun `directory requests filter files before the candidate limit`() =
        runTest {
            val line = "cd n"
            Fixture(StandardTestDispatcher(testScheduler), line).use { fixture ->
                val request = TerminalCompletionRequest(line, line.length, shellCapabilities = TerminalShellCapabilities.POWERSHELL)
                val query = async { fixture.provider.complete(request, TerminalCompletionContext.resolve(request), 1) }
                runCurrent()
                fixture.respond(
                    TerminalShellCompletionResult(
                        line,
                        line.length,
                        3,
                        4,
                        listOf(
                            TerminalShellCompletionCandidate("notes.txt", "notes.txt", "file", TerminalShellCompletionKind.FILE),
                            TerminalShellCompletionCandidate(
                                "'new folder/'",
                                "new folder",
                                "directory",
                                TerminalShellCompletionKind.DIRECTORY,
                            ),
                        ),
                    ),
                )
                runCurrent()
                assertEquals(listOf("'new folder/'"), assertNotNull(query.await()).candidates.map { it.replacementText })
            }
        }

    @Test
    fun `unsupported contexts and invalid limits do not send native queries`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), "tool").use { fixture ->
                for (request in listOf(
                    TerminalCompletionRequest("tool", 4, shellCapabilities = TerminalShellCapabilities.PLAIN),
                    TerminalCompletionRequest("tool arg", 8, shellCapabilities = TerminalShellCapabilities.POSIX),
                    TerminalCompletionRequest("./tool", 6, shellCapabilities = TerminalShellCapabilities.POSIX),
                )) {
                    val context = TerminalCompletionContext.resolve(request)
                    assertNull(fixture.provider.complete(request, context, 10))
                    assertFailsWith<IllegalArgumentException> { fixture.provider.complete(request, context, 0) }
                }
                runCurrent()
                assertEquals("", fixture.connector.writtenText())
            }
        }

    private class Fixture(
        dispatcher: CoroutineDispatcher,
        line: String,
    ) : AutoCloseable {
        val connector = RecordingConnector()
        lateinit var channel: TerminalShellCompletionChannel
        private val commandLine = TerminalShellCommandLineState(TerminalShellCommandLineSnapshot(line, line.length, line.length, 0))
        private val host = TerminalShellIntegrationFactory.host(TerminalShellIntegrationState(), commandLine)
        val session =
            TerminalSession.create(
                TerminalBuffers.create(80, 4),
                connector,
                shellIntegration =
                    TerminalShellIntegrationFactory { context ->
                        channel = context.shellCompletion
                        channel.ready("test") { listOf(TerminalKeyEvent(codepoint = 'e'.code)) }
                        host.create(context)
                    },
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            )
        val provider = SessionShellCompletionProvider(session)

        init {
            session.start()
        }

        fun respond(result: TerminalShellCompletionResult) {
            channel.respond("test", 1, result)
            connector.outputProcessed()
        }

        override fun close() = session.close()
    }

    private class RecordingConnector : TerminalConnector {
        private lateinit var listener: TerminalConnectorListener
        private val written = ByteArrayOutputStream()

        override fun start(listener: TerminalConnectorListener) {
            this.listener = listener
        }

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            written.write(bytes, offset, length)
        }

        override fun resize(
            columns: Int,
            rows: Int,
        ) = Unit

        override fun close() = Unit

        fun writtenText(): String = written.toString(Charsets.UTF_8)

        fun outputProcessed() = listener.onBytes(byteArrayOf(), 0, 0)
    }
}
