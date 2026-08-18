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
package io.github.ketraterm.completion.api

import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class TerminalCompletionSessionRegistryTest {
    @Test
    fun `existing POSIX Gradle wrapper is presented as the authoritative Gradle command`() =
        runBlocking {
            val registry = registry(commandSpecs = TerminalCommandSpecs.defaults())
            val session = registry.openSession("session", fileSystemWith("gradlew"))

            val candidates =
                session.engine.complete(
                    request(
                        commandLine = "./gr",
                        workingDirectoryUri = "file:///project",
                        shellCapabilities = TerminalShellCapabilities.POSIX,
                    ),
                )
            val wrapper = candidates.single { it.replacementText == "./gradlew" }

            assertEquals("./gradlew", wrapper.displayText)
            assertEquals("build automation", wrapper.detail)
            assertEquals("spec", wrapper.source)
            assertEquals(TerminalCompletionCandidateKind.COMMAND, wrapper.kind)
            assertEquals(0, wrapper.replacementStartOffset)
            assertEquals(4, wrapper.replacementEndOffset)
            assertContentEquals(intArrayOf(0, 4), wrapper.matchedRanges.copyPackedOffsets())
            registry.close()
        }

    @Test
    fun `existing PowerShell Gradle wrapper uses its local batch invocation spelling`() =
        runBlocking {
            val registry = registry(commandSpecs = TerminalCommandSpecs.defaults())
            val session = registry.openSession("session", fileSystemWith("gradlew.bat"))

            val candidates =
                session.engine.complete(
                    request(
                        commandLine = ".\\gr",
                        workingDirectoryUri = "file:///project",
                        shellCapabilities = TerminalShellCapabilities.POWERSHELL,
                    ),
                )
            val wrapper = candidates.single { it.replacementText == ".\\gradlew.bat" }

            assertEquals(".\\gradlew.bat", wrapper.displayText)
            assertEquals("build automation", wrapper.detail)
            assertEquals("spec", wrapper.source)
            assertEquals(TerminalCompletionCandidateKind.COMMAND, wrapper.kind)
            registry.close()
        }

    @Test
    fun `quoted local Gradle wrapper retains specification presentation`() =
        runBlocking {
            val registry = registry(commandSpecs = TerminalCommandSpecs.defaults())
            val session = registry.openSession("session", fileSystemWith("gradlew"))

            val wrapper =
                session.engine
                    .complete(
                        request(
                            commandLine = "\"./gr",
                            workingDirectoryUri = "file:///project",
                            shellCapabilities = TerminalShellCapabilities.POSIX,
                        ),
                    ).single { it.replacementText == "\"./gradlew\"" }

            assertEquals("./gradlew", wrapper.displayText)
            assertEquals("build automation", wrapper.detail)
            assertEquals("spec", wrapper.source)
            assertEquals(TerminalCompletionCandidateKind.COMMAND, wrapper.kind)
            registry.close()
        }

    @Test
    fun `canonical Gradle command remains available without a local wrapper`() =
        runBlocking {
            val registry = registry(commandSpecs = TerminalCommandSpecs.defaults())
            val session = registry.openSession("session", EMPTY_FILE_SYSTEM)

            val candidate = session.engine.complete(request("gr")).single { it.replacementText == "gradle" }

            assertEquals("gradle", candidate.displayText)
            assertEquals("build automation", candidate.detail)
            assertEquals("spec", candidate.source)
            assertEquals(TerminalCompletionCandidateKind.COMMAND, candidate.kind)
            registry.close()
        }

    @Test
    fun `ordinary local file remains a path candidate`() =
        runBlocking {
            val registry = registry(commandSpecs = TerminalCommandSpecs.defaults())
            val session = registry.openSession("session", fileSystemWith("gradient"))

            val candidate =
                session.engine
                    .complete(
                        request(
                            commandLine = "./gr",
                            workingDirectoryUri = "file:///project",
                            shellCapabilities = TerminalShellCapabilities.POSIX,
                        ),
                    ).single()

            assertEquals("./gradient", candidate.replacementText)
            assertEquals("gradient", candidate.displayText)
            assertEquals("file", candidate.detail)
            assertEquals("path", candidate.source)
            assertEquals(TerminalCompletionCandidateKind.PATH, candidate.kind)
            registry.close()
        }

    @Test
    fun `PowerShell wrapper spelling resolves the Gradle command specification`() =
        runBlocking {
            val registry = registry(commandSpecs = TerminalCommandSpecs.defaults())
            val session = registry.openSession("session", EMPTY_FILE_SYSTEM)

            val candidate =
                session.engine
                    .complete(
                        request(
                            commandLine = ".\\gradlew.bat bu",
                            shellCapabilities = TerminalShellCapabilities.POWERSHELL,
                        ),
                    ).single { it.replacementText == "build" }

            assertEquals("assemble and test the project", candidate.detail)
            assertEquals("spec", candidate.source)
            assertEquals(TerminalCompletionCandidateKind.SUBCOMMAND, candidate.kind)
            registry.close()
        }

    @Test
    fun `repeated Gradle executions preserve default specification presentation`() =
        runBlocking {
            val learningStore = TerminalCompletionLearningStore()
            repeat(3) { index ->
                learningStore.recordCommandResult(
                    commandLine = "./gradlew build",
                    successful = true,
                    profileId = null,
                    workingDirectoryUri = null,
                    usedAtEpochMillis = 1_000L + index,
                )
            }
            val registry = registry(commandSpecs = TerminalCommandSpecs.defaults(), learningStore = learningStore)
            val session = registry.openSession("session", EMPTY_FILE_SYSTEM)
            repeat(3) {
                registry.recordSuccessfulCommand("session", "./gradlew build", null, null)
            }

            val candidate = session.engine.complete(request("./gradlew bu")).single()

            assertEquals("build", candidate.replacementText)
            assertEquals("build", candidate.displayText)
            assertEquals(10, candidate.replacementStartOffset)
            assertEquals(12, candidate.replacementEndOffset)
            assertEquals(TerminalCompletionCandidateKind.SUBCOMMAND, candidate.kind)
            assertEquals("spec", candidate.source)
            assertEquals("assemble and test the project", candidate.detail)
            assertContentEquals(intArrayOf(0, 2), candidate.matchedRanges.copyPackedOffsets())
            registry.close()
        }

    @Test
    fun `session learning preserves matching specification presentation`() =
        runBlocking {
            val registry = registry(commandSpecs = listOf(GRADLE_SPEC))
            val session = registry.openSession("session", EMPTY_FILE_SYSTEM)
            registry.recordSuccessfulCommand("session", "./gradlew build", null, null)

            val candidate = session.engine.complete(request("./gradlew bu")).single()

            assertEquals("build", candidate.replacementText)
            assertEquals(TerminalCompletionCandidateKind.SUBCOMMAND, candidate.kind)
            assertEquals("spec", candidate.source)
            assertEquals("assemble and test the project", candidate.detail)
            registry.close()
        }

    @Test
    fun `preexisting learned history preserves matching specification presentation`() =
        runBlocking {
            val learningStore = TerminalCompletionLearningStore()
            learningStore.recordCommandResult(
                commandLine = "./gradlew build",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 1_000,
            )
            val registry = registry(commandSpecs = listOf(GRADLE_SPEC), learningStore = learningStore)
            val session = registry.openSession("session", EMPTY_FILE_SYSTEM)

            val candidate = session.engine.complete(request("./gradlew bu")).single()

            assertEquals("build", candidate.replacementText)
            assertEquals(TerminalCompletionCandidateKind.SUBCOMMAND, candidate.kind)
            assertEquals("spec", candidate.source)
            assertEquals("assemble and test the project", candidate.detail)
            registry.close()
        }

    @Test
    fun `learned evidence promotes a specification outcome without replacing its presentation`() =
        runBlocking {
            val learningStore = TerminalCompletionLearningStore()
            val registry = registry(commandSpecs = TerminalCommandSpecs.defaults(), learningStore = learningStore)
            val session = registry.openSession("session", EMPTY_FILE_SYSTEM)
            val request = request("./gradlew ")
            val coldTest = session.engine.complete(request).single { it.replacementText == "test" }
            repeat(3) { index ->
                learningStore.recordCommandResult(
                    commandLine = "./gradlew test",
                    successful = true,
                    profileId = null,
                    workingDirectoryUri = null,
                    usedAtEpochMillis = 1_000L + index,
                )
            }

            val candidates = session.engine.complete(request)
            val learnedTest = candidates.single { it.replacementText == "test" }

            assertEquals("test", candidates.first().replacementText)
            assertTrue(learnedTest.score > coldTest.score)
            assertEquals("spec", learnedTest.source)
            assertEquals(TerminalCompletionCandidateKind.SUBCOMMAND, learnedTest.kind)
            assertEquals("run tests", learnedTest.detail)
            registry.close()
        }

    @Test
    fun `unknown learned outcome retains learned presentation`() =
        runBlocking {
            val learningStore = TerminalCompletionLearningStore()
            learningStore.recordCommandResult(
                commandLine = "unknown-cli deploy",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 1_000,
            )
            val registry = registry(commandSpecs = TerminalCommandSpecs.defaults(), learningStore = learningStore)
            val session = registry.openSession("session", EMPTY_FILE_SYSTEM)

            val candidate = session.engine.complete(request("unknown-cli d")).single()

            assertEquals("deploy", candidate.replacementText)
            assertEquals("mru", candidate.source)
            assertEquals(TerminalCompletionCandidateKind.ARGUMENT, candidate.kind)
            assertEquals("learned command", candidate.detail)
            registry.close()
        }

    @Test
    fun `learned continuation remains distinct from matching specification outcome`() =
        runBlocking {
            val learningStore = TerminalCompletionLearningStore()
            learningStore.recordCommandResult(
                commandLine = "./gradlew build --scan",
                successful = true,
                profileId = null,
                workingDirectoryUri = null,
                usedAtEpochMillis = 1_000,
            )
            val registry = registry(commandSpecs = TerminalCommandSpecs.defaults(), learningStore = learningStore)
            val session = registry.openSession("session", EMPTY_FILE_SYSTEM)

            val candidates = session.engine.complete(request("./gradlew bu"))

            assertEquals("spec", candidates.single { it.replacementText == "build" }.source)
            assertEquals("mru", candidates.single { it.replacementText == "build --scan" }.source)
            registry.close()
        }

    @Test
    fun `replacement retires the previous MRU and owns later records`() =
        runBlocking {
            val registry = registry()
            val previous = registry.openSession("session", EMPTY_FILE_SYSTEM)
            registry.recordSuccessfulCommand("session", "git status", null, null)
            assertEquals(listOf("git status"), previous.engine.complete(request()).map { it.replacementText })

            val replacement = registry.openSession("session", EMPTY_FILE_SYSTEM)
            registry.recordSuccessfulCommand("session", "git switch main", null, null)

            assertTrue(previous.engine.complete(request()).isEmpty())
            assertEquals(listOf("git switch main"), replacement.engine.complete(request()).map { it.replacementText })
            registry.close()
        }

    @Test
    fun `closing a replaced handle cannot remove the current session`() =
        runBlocking {
            val registry = registry()
            val previous = registry.openSession("session", EMPTY_FILE_SYSTEM)
            val replacement = registry.openSession("session", EMPTY_FILE_SYSTEM)

            previous.close()
            registry.recordSuccessfulCommand("session", "git status", null, null)

            assertEquals(listOf("git status"), replacement.engine.complete(request()).map { it.replacementText })
            registry.close()
        }

    @Test
    fun `close clears sessions ignores late records and rejects reopening`(): Unit =
        runBlocking {
            val registry = registry()
            val session = registry.openSession("session", EMPTY_FILE_SYSTEM)
            registry.recordSuccessfulCommand("session", "git status", null, null)

            registry.close()
            registry.close()
            registry.recordSuccessfulCommand("session", "git switch main", null, null)

            assertTrue(session.engine.complete(request()).isEmpty())
            assertFailsWith<IllegalStateException> { registry.openSession("replacement", EMPTY_FILE_SYSTEM) }
        }

    private fun registry(
        commandSpecs: List<TerminalCommandSpec> = emptyList(),
        learningStore: TerminalCompletionLearningStore? = null,
    ): TerminalCompletionSessionRegistry =
        TerminalCompletionSessionRegistry(
            commandSpecs = commandSpecs,
            learningStore = learningStore,
            sessionMruCapacity = 4,
        )

    private fun request(
        commandLine: String = "git",
        workingDirectoryUri: String? = null,
        shellCapabilities: TerminalShellCapabilities = TerminalShellCapabilities.PLAIN,
    ): TerminalCompletionRequest =
        TerminalCompletionRequest(
            commandLine = commandLine,
            cursorOffset = commandLine.length,
            workingDirectoryUri = workingDirectoryUri,
            shellCapabilities = shellCapabilities,
        )

    private fun fileSystemWith(name: String): TerminalFileSystemProvider =
        TerminalFileSystemProvider { request ->
            if (name.startsWith(request.entryNamePrefix, ignoreCase = true)) {
                listOf(TerminalFileEntry(name, isDirectory = false))
            } else {
                emptyList()
            }
        }

    private companion object {
        private val GRADLE_SPEC =
            TerminalCommandSpec(
                name = "gradle",
                description = "build automation",
                aliases = listOf("./gradlew"),
                subcommands = listOf(TerminalCommandSpec("build", "assemble and test the project")),
            )
        private val EMPTY_FILE_SYSTEM = TerminalFileSystemProvider { emptyList() }
    }
}
