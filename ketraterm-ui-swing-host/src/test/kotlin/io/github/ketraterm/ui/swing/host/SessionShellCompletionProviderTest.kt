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

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.completion.model.TerminalGitCompletionSnapshot
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.session.*
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionShellCompletionProviderTest {
    @Test
    fun `pending and unavailable inventories suppress unverified catalog fallback`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), "git").use { f ->
                val request = TerminalCompletionRequest("git", 3, shellCapabilities = TerminalShellCapabilities.POSIX)
                val context = TerminalCompletionContext.resolve(request)
                assertNull(f.source.complete(request, context, 10)?.candidates)
                for (status in TerminalShellCommandInventoryStatus.entries) {
                    f.channel.publishCommands(
                        TerminalShellCommandInventory(emptyList(), status),
                    )
                    assertEquals(emptyList(), f.source.complete(request, context, 10)?.candidates)
                    assertTrue(f.source.commandAliases().isEmpty())
                }
            }
        }

    @Test
    fun `native directory fallback merges equivalent history and paths before Swing adaptation`() =
        runTest {
            val line = "cd Do"
            Fixture(StandardTestDispatcher(testScheduler), line).use { fixture ->
                val learning =
                    TerminalCompletionLearningStore().apply {
                        recordCommandResult("cd Documents/", true, null, null, System.currentTimeMillis())
                    }
                val engine =
                    TerminalCompletionEngines.fromSources(
                        emptyList(),
                        learningStore = learning,
                        shellProvider = fixture.source,
                    )
                val provider =
                    SwingCompletionSuggestionProvider(engine, {
                        SwingCompletionContext(shellCapabilities = TerminalShellCapabilities.POWERSHELL)
                    })
                val query = async { provider.suggestions(SwingShellSuggestionRequest(line, line.length)).last() }
                runCurrent()
                fixture.respond(
                    TerminalShellCompletionResult(
                        line,
                        line.length,
                        3,
                        line.length,
                        listOf(
                            TerminalShellCompletionCandidate("Documents.txt", "Documents.txt", "", TerminalShellCompletionKind.FILE),
                            TerminalShellCompletionCandidate("./Documents/", "Documents", "", TerminalShellCompletionKind.DIRECTORY),
                            TerminalShellCompletionCandidate("Documents\\", "Documents", "", TerminalShellCompletionKind.DIRECTORY),
                        ),
                    ),
                )
                runCurrent()

                val suggestion = query.await().single()
                assertEquals("powershell", suggestion.source)
                // Equivalent native edits retain the shorter spelling, not enumeration order.
                assertEquals("Documents\\", suggestion.replacementText)
                assertEquals(3, suggestion.replacementStartOffset)
                assertEquals(line.length, suggestion.replacementEndOffset)
            }
        }

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
                        externalCommandName = "tool.exe",
                    ),
                )
                runCurrent()
                val answer = assertNotNull(query.await())
                val candidates = answer.candidates
                assertEquals("tool.exe", answer.externalCommandName)
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
    fun `unsupported dialects never invoke the installed session hook`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), "tool").use { fixture ->
                val request = TerminalCompletionRequest("tool", 4, shellCapabilities = TerminalShellCapabilities.PLAIN)
                assertNull(fixture.source.complete(request, TerminalCompletionContext.resolve(request), 10))
                runCurrent()
                assertTrue(fixture.connector.writtenBytes.isEmpty())
            }
        }

    @Test
    fun `POSIX command inventories preserve native authority without assuming Bash`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), "tool").use { fixture ->
                val request = TerminalCompletionRequest("tool", 4, shellCapabilities = TerminalShellCapabilities.POSIX)
                fixture.publishCommands(
                    listOf(TerminalShellCompletionCandidate("toolbox", "toolbox", "", TerminalShellCompletionKind.COMMAND)),
                )
                val candidates = assertNotNull(fixture.source.complete(request, TerminalCompletionContext.resolve(request), 10)).candidates
                runCurrent()
                assertEquals("shell", candidates.single().source)
                assertEquals("toolbox", candidates.single().replacementText)
                assertTrue(fixture.connector.writtenBytes.isEmpty())
            }
        }

    @Test
    fun `Bash alias definitions refresh shared argument semantics without rewriting accepted commands`() =
        runTest {
            val line = "gs fe"
            Fixture(StandardTestDispatcher(testScheduler), line).use { fixture ->
                val observedDirectories = mutableListOf<List<String>>()
                val engine =
                    TerminalCompletionEngines.fromSources(
                        listOf(
                            TerminalCompletionSourceEntry(
                                TerminalCompletionSources.git("git") { _, context ->
                                    observedDirectories += context.precedingOptionValues("-C")
                                    TerminalGitCompletionSnapshot(localBranches = listOf(TerminalCompletionDomainValue("feature/local")))
                                },
                            ),
                        ),
                        shellProvider = fixture.source,
                    )
                val request = TerminalCompletionRequest(line, line.length, shellCapabilities = TerminalShellCapabilities.POSIX)
                for (directory in listOf("first repo", "second repo")) {
                    fixture.publishCommands(
                        listOf(
                            TerminalShellCompletionCandidate(
                                "gs",
                                "gs",
                                "",
                                TerminalShellCompletionKind.COMMAND,
                                "git -C '$directory' switch",
                            ),
                        ),
                    )
                    val candidate = engine.completions(request).last().single { it.source == "git" }
                    assertEquals(listOf(directory), observedDirectories.last())
                    assertEquals(
                        "gs feature/local",
                        line.replaceRange(candidate.replacementStartOffset, candidate.replacementEndOffset, candidate.replacementText),
                    )
                }
                fixture.publishCommands(emptyList())
                assertTrue(engine.completions(request).last().isEmpty())
                assertTrue(fixture.connector.writtenBytes.isEmpty())
            }
        }

    @Test
    fun `Bash unmodeled arguments retain existing source fallback`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), "tool x").use { fixture ->
                val request = TerminalCompletionRequest("tool x", 6, shellCapabilities = TerminalShellCapabilities.POSIX)
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
                assertEquals(listOf("xdir/"), assertNotNull(query.await()).candidates.map { it.replacementText })
            }
        }

    @Test
    fun `concurrent provider requests share the native candidates without a second query`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), "tool").use { fixture ->
                val request = TerminalCompletionRequest("tool", 4, shellCapabilities = TerminalShellCapabilities.POWERSHELL)
                val context = TerminalCompletionContext.resolve(request)
                val first = async { fixture.source.complete(request, context, 10) }
                val second = async { fixture.source.complete(request, context, 1) }
                runCurrent()
                assertFalse(first.isCompleted)
                assertFalse(second.isCompleted)
                assertEquals("e", fixture.connector.writtenBytes.toString(Charsets.UTF_8))
                fixture.respond(
                    TerminalShellCompletionResult(
                        "tool",
                        4,
                        0,
                        4,
                        listOf(
                            TerminalShellCompletionCandidate("toolbox", "toolbox", "", TerminalShellCompletionKind.COMMAND),
                            TerminalShellCompletionCandidate("toolkit", "toolkit", "", TerminalShellCompletionKind.COMMAND),
                        ),
                    ),
                )
                runCurrent()

                assertEquals(listOf("toolbox", "toolkit"), assertNotNull(first.await()).candidates.map { it.replacementText })
                assertEquals(listOf("toolbox"), assertNotNull(second.await()).candidates.map { it.replacementText })
                assertEquals("e", fixture.connector.writtenBytes.toString(Charsets.UTF_8))
            }
        }

    @Test
    fun `cancelling the initiating provider does not cancel another consumer of its native query`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), "tool").use { fixture ->
                val request = TerminalCompletionRequest("tool", 4, shellCapabilities = TerminalShellCapabilities.POWERSHELL)
                val context = TerminalCompletionContext.resolve(request)
                val first = async { fixture.source.complete(request, context, 10) }
                val second = async { fixture.source.complete(request, context, 10) }
                runCurrent()
                first.cancelAndJoin()
                assertFalse(second.isCompleted)

                fixture.respond(
                    TerminalShellCompletionResult(
                        "tool",
                        4,
                        0,
                        4,
                        listOf(TerminalShellCompletionCandidate("toolbox", "toolbox", "", TerminalShellCompletionKind.COMMAND)),
                    ),
                )
                runCurrent()

                assertEquals(listOf("toolbox"), assertNotNull(second.await()).candidates.map { it.replacementText })
                assertEquals("e", fixture.connector.writtenBytes.toString(Charsets.UTF_8))
            }
        }

    private class Fixture(
        dispatcher: CoroutineDispatcher,
        line: String,
    ) : AutoCloseable {
        val connector = MockConnector()
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

        fun publishCommands(commands: List<TerminalShellCompletionCandidate>) =
            channel.publishCommands(
                TerminalShellCommandInventory(commands),
            )

        override fun close() = session.close()
    }
}
