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
package io.github.ketraterm.ui.swing.host

import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import io.github.ketraterm.completion.api.TerminalCompletionContext
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.api.TerminalShellCapabilities
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.session.*
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionShellCompletionProviderTest {
    @Test
    fun `native ranges escaping order and metadata survive adaptation with a candidate limit`() =
        runTest {
            val line = "tool -Value n"
            Fixture(StandardTestDispatcher(testScheduler), line).use { fixture ->
                val request = TerminalCompletionRequest(line, line.length, shellCapabilities = TerminalShellCapabilities.POWERSHELL)
                val context = TerminalCompletionContext.resolve(request)
                val query = async { fixture.source.complete(request, context, 2) }
                runCurrent()
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
                                "registered detail",
                                TerminalShellCompletionKind.ARGUMENT,
                            ),
                            TerminalShellCompletionCandidate(
                                "'native path'",
                                "native path",
                                "directory detail",
                                TerminalShellCompletionKind.DIRECTORY,
                            ),
                            TerminalShellCompletionCandidate("omitted", "omitted", "", TerminalShellCompletionKind.OPTION),
                        ),
                    ),
                )
                runCurrent()
                val candidates = assertNotNull(query.await())
                assertEquals(listOf("'native value'", "'native path'"), candidates.map { it.replacementText })
                assertEquals(
                    listOf(TerminalCompletionCandidateKind.ARGUMENT, TerminalCompletionCandidateKind.PATH),
                    candidates.map { it.kind },
                )
                assertEquals(listOf(3, 2), candidates.map { it.score })
                assertEquals("native value", candidates.first().displayText)
                assertEquals("registered detail", candidates.first().detail)
                for (candidate in candidates) {
                    assertEquals(12, candidate.replacementStartOffset)
                    assertEquals(13, candidate.replacementEndOffset)
                    assertEquals("powershell", candidate.source)
                    assertEquals(context.expectedValueDomain, candidate.valueDomain)
                }
            }
        }

    @Test
    fun `non PowerShell requests never invoke the installed session hook`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), "tool").use { fixture ->
                val request = TerminalCompletionRequest("tool", 4, shellCapabilities = TerminalShellCapabilities.POSIX)
                assertNull(fixture.source.complete(request, TerminalCompletionContext.resolve(request), 10))
                runCurrent()
                assertTrue(fixture.connector.writtenBytes.isEmpty())
            }
        }

    @Test
    fun `directory arguments reject native files before applying the candidate limit`() =
        runTest {
            val line = "cd x"
            Fixture(StandardTestDispatcher(testScheduler), line).use { fixture ->
                val request = TerminalCompletionRequest(line, line.length, shellCapabilities = TerminalShellCapabilities.POWERSHELL)
                val query = async { fixture.source.complete(request, TerminalCompletionContext.resolve(request), 1) }
                runCurrent()
                fixture.respond(
                    TerminalShellCompletionResult(
                        line,
                        line.length,
                        3,
                        4,
                        listOf(
                            TerminalShellCompletionCandidate("x.txt", "x.txt", "file", TerminalShellCompletionKind.FILE),
                            TerminalShellCompletionCandidate("xdir/", "xdir", "directory", TerminalShellCompletionKind.DIRECTORY),
                        ),
                    ),
                )
                runCurrent()
                assertEquals(listOf("xdir/"), assertNotNull(query.await()).map { it.replacementText })
            }
        }

    @Test
    fun `busy installed hook returns no unverified command candidates`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), "tool").use { fixture ->
                val request = TerminalCompletionRequest("tool", 4, shellCapabilities = TerminalShellCapabilities.POWERSHELL)
                val context = TerminalCompletionContext.resolve(request)
                val first = async { fixture.source.complete(request, context, 10) }
                runCurrent()
                assertEquals(emptyList(), fixture.source.complete(request, context, 10))
                fixture.respond(TerminalShellCompletionResult("tool", 4, 0, 4, emptyList()))
                runCurrent()
                assertEquals(emptyList(), first.await())
            }
        }

    private class Fixture(
        dispatcher: CoroutineDispatcher,
        line: String,
    ) : AutoCloseable {
        val connector = MockConnector()
        private lateinit var channel: TerminalShellCompletionChannel
        private val commandLine = TerminalShellCommandLineState(TerminalShellCommandLineSnapshot(line, line.length, line.length, 0))
        private val host = TerminalShellIntegrationFactory.host(TerminalShellIntegrationState(), commandLine)
        val session =
            TerminalSession.create(
                TerminalBuffers.create(80, 4),
                connector,
                shellIntegration =
                    TerminalShellIntegrationFactory { context ->
                        channel = context.shellCompletion
                        channel.ready("test", listOf(TerminalKeyEvent(codepoint = 'e'.code)))
                        host.create(context)
                    },
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            )
        val source = SessionShellCompletionProvider(session)

        init {
            session.start()
        }

        fun respond(result: TerminalShellCompletionResult) {
            channel.respond("test", 1, result)
            connector.feedFromHost(byteArrayOf())
        }

        override fun close() = session.close()
    }
}
