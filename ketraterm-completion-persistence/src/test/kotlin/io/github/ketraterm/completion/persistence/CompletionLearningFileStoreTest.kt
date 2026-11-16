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
package io.github.ketraterm.completion.persistence

import io.github.ketraterm.completion.api.TerminalCompletionLearningStore
import io.github.ketraterm.completion.model.TerminalCommandReplay
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackKind
import io.github.ketraterm.completion.model.TerminalCompletionLearningSnapshot
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class CompletionLearningFileStoreTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `standard filenames automatically migrate the legacy sibling without a product override`() {
        val previous = directory.resolve(LEGACY_FILE_NAME)
        val current = directory.resolve(TerminalCompletionLearningCoordinator.currentFileName())
        val expected = snapshot("git status")
        CompletionLearningFileStore(previous).persist(expected)
        val original = Files.readAllBytes(previous)

        assertEquals(expected, CompletionLearningFileStore(current).loadSnapshot().loadedSnapshot())

        assertFalse(Files.exists(previous))
        assertContentEquals(original, Files.readAllBytes(current))
    }

    @Test
    fun `migration prefers the current file then its legacy sibling then the previous location`() {
        for (winner in 0..3) {
            val root = directory.resolve("case-$winner")
            val current = root.resolve("state").resolve(TerminalCompletionLearningCoordinator.currentFileName())
            val previous = root.resolve("config").resolve(TerminalCompletionLearningCoordinator.currentFileName())
            val candidates = listOf(current, current.resolveSibling(LEGACY_FILE_NAME), previous, previous.resolveSibling(LEGACY_FILE_NAME))
            val original = mutableMapOf<Path, ByteArray>()
            for (index in winner..3) {
                CompletionLearningFileStore(candidates[index]).persist(snapshot("command $index"))
                original[candidates[index]] = Files.readAllBytes(candidates[index])
            }

            assertEquals(
                snapshot("command $winner"),
                CompletionLearningFileStore(current, previousPath = previous).loadSnapshot().loadedSnapshot(),
            )

            assertContentEquals(original.getValue(candidates[winner]), Files.readAllBytes(current))
            if (winner != 0) assertFalse(Files.exists(candidates[winner]))
            for (index in winner + 1..3) assertContentEquals(original.getValue(candidates[index]), Files.readAllBytes(candidates[index]))
        }
    }

    @Test
    fun `a rejected legacy sibling blocks fallback to an older location`() {
        val current = directory.resolve(TerminalCompletionLearningCoordinator.currentFileName())
        val previous = directory.resolve("config").resolve(TerminalCompletionLearningCoordinator.currentFileName())
        val legacy = Files.writeString(current.resolveSibling(LEGACY_FILE_NAME), "unsupported newer state")
        CompletionLearningFileStore(previous.resolveSibling(LEGACY_FILE_NAME)).persist(snapshot("older command"))

        assertSame(CompletionLearningFileLoadOutcome.Rejected, CompletionLearningFileStore(current, previousPath = previous).loadSnapshot())

        assertEquals("unsupported newer state", Files.readString(legacy))
        assertTrue(Files.exists(previous.resolveSibling(LEGACY_FILE_NAME)))
        assertFalse(Files.exists(current))
    }

    @Test
    fun `reset clears all legacy locations without hydration`() {
        val current = directory.resolve(TerminalCompletionLearningCoordinator.currentFileName())
        val previous = directory.resolve("config").resolve(TerminalCompletionLearningCoordinator.currentFileName())
        Files.createDirectories(previous.parent)
        val stale = listOf(current.resolveSibling(LEGACY_FILE_NAME), previous, previous.resolveSibling(LEGACY_FILE_NAME))
        for (file in stale) Files.writeString(file, "unsupported stale data")
        val files = CompletionLearningFileStore(current, previousPath = previous)

        files.reset()

        for (file in stale) assertFalse(Files.exists(file), file.toString())
        assertEquals(TerminalCompletionLearningSnapshot.EMPTY, files.loadSnapshot().loadedSnapshot())
    }

    @Test
    fun `reset attempts every previous location even when multiple removals fail`() {
        val current = directory.resolve(TerminalCompletionLearningCoordinator.currentFileName())
        val previous = directory.resolve("config").resolve(TerminalCompletionLearningCoordinator.currentFileName())
        for (blocked in listOf(current.resolveSibling(LEGACY_FILE_NAME), previous)) {
            Files.createDirectories(blocked)
            Files.writeString(blocked.resolve("child"), "prevent directory removal")
        }
        val removable = Files.writeString(previous.resolveSibling(LEGACY_FILE_NAME), "stale learning")
        val files = CompletionLearningFileStore(current, previousPath = previous)

        val failure = assertFailsWith<IOException> { files.reset() }

        assertEquals(1, failure.suppressed.size)
        assertFalse(Files.exists(removable))
        assertEquals(TerminalCompletionLearningSnapshot.EMPTY, files.loadSnapshot().loadedSnapshot())
    }

    @Test
    fun `custom filenames do not implicitly adopt another files legacy sibling`() {
        val legacy = directory.resolve(LEGACY_FILE_NAME)
        CompletionLearningFileStore(legacy).persist(snapshot("unrelated command"))
        val files = CompletionLearningFileStore(directory.resolve("custom.tsv"))

        assertSame(CompletionLearningFileLoadOutcome.Missing, files.loadSnapshot())
        files.reset()
        assertTrue(Files.exists(legacy))
    }

    @Test
    fun `a valid previous snapshot is relocated once without changing its bytes`() {
        val previous = directory.resolve("config/learning.tsv")
        val current = directory.resolve("state/learning.tsv")
        val expected = snapshot("git status", "git log")
        CompletionLearningFileStore(previous).persist(expected)
        val originalBytes = Files.readAllBytes(previous)
        val files = CompletionLearningFileStore(current, previousPath = previous)

        assertEquals(expected, files.loadSnapshot().loadedSnapshot())
        assertFalse(Files.exists(previous))
        assertContentEquals(originalBytes, Files.readAllBytes(current))
        assertEquals(expected, files.loadSnapshot().loadedSnapshot())
    }

    @Test
    fun `an existing destination is authoritative even when it is rejected`() {
        for (rejected in listOf(false, true)) {
            val previous = directory.resolve("previous-$rejected.tsv")
            val current = directory.resolve("current-$rejected.tsv")
            CompletionLearningFileStore(previous).persist(snapshot("old command"))
            val expected = snapshot("current command")
            CompletionLearningFileStore(current).persist(expected)
            if (rejected) Files.writeString(current, "unsupported format")
            val currentBytes = Files.readAllBytes(current)
            val previousBytes = Files.readAllBytes(previous)
            val reads = mutableListOf<Path>()
            val files =
                CompletionLearningFileStore(current, previousPath = previous, openInput = {
                    reads.add(it)
                    Files.newInputStream(it)
                })

            val outcome = files.loadSnapshot()
            if (rejected) {
                assertSame(CompletionLearningFileLoadOutcome.Rejected, outcome)
            } else {
                assertEquals(expected, outcome.loadedSnapshot())
            }
            assertEquals(listOf(current), reads)
            assertContentEquals(currentBytes, Files.readAllBytes(current))
            assertContentEquals(previousBytes, Files.readAllBytes(previous))
        }
    }

    @Test
    fun `rejected or unreadable previous files are preserved without creating a destination`() {
        val previous = directory.resolve("previous.tsv")
        val current = directory.resolve("state/learning.tsv")
        for (contents in listOf("unsupported format", "x".repeat(MAX_FILE_BYTES + 1))) {
            Files.writeString(previous, contents)
            assertSame(
                CompletionLearningFileLoadOutcome.Rejected,
                CompletionLearningFileStore(current, previousPath = previous).loadSnapshot(),
            )
            assertEquals(contents, Files.readString(previous))
            assertFalse(Files.exists(current))
        }
        CompletionLearningFileStore(previous).persist(snapshot("git status"))
        val failure = IOException("read denied")
        val files = CompletionLearningFileStore(current, previousPath = previous, openInput = { throw failure })
        assertSame(failure, assertIs<CompletionLearningFileLoadOutcome.Failed>(files.loadSnapshot()).cause)
        assertTrue(Files.exists(previous))
        assertFalse(Files.exists(current))
    }

    @Test
    fun `failed directory creation leaves the previous snapshot available for recovery`() {
        val previous = directory.resolve("previous.tsv")
        CompletionLearningFileStore(previous).persist(snapshot("git status"))
        val blocker = Files.writeString(directory.resolve("blocked"), "not a directory")
        val files = CompletionLearningFileStore(blocker.resolve("learning.tsv"), previousPath = previous)
        assertIs<CompletionLearningFileLoadOutcome.Failed>(files.loadSnapshot())
        assertEquals(snapshot("git status"), CompletionLearningFileStore(previous).loadSnapshot().loadedSnapshot())
        assertEquals("not a directory", Files.readString(blocker))
    }

    @Test
    fun `a destination created during migration wins without replacing either snapshot`() {
        val previous = directory.resolve("previous.tsv")
        val current = directory.resolve("current.tsv")
        val previousSnapshot = snapshot("old command")
        val currentSnapshot = snapshot("new command")
        CompletionLearningFileStore(previous).persist(previousSnapshot)
        val files =
            CompletionLearningFileStore(current, previousPath = previous, openInput = { path ->
                if (path == previous) {
                    val bytes = Files.readAllBytes(path)
                    CompletionLearningFileStore(current).persist(currentSnapshot)
                    ByteArrayInputStream(bytes)
                } else {
                    Files.newInputStream(path)
                }
            })

        assertEquals(currentSnapshot, files.loadSnapshot().loadedSnapshot())
        assertEquals(previousSnapshot, CompletionLearningFileStore(previous).loadSnapshot().loadedSnapshot())
    }

    @Test
    fun `concurrent relocation is observed as a normal current snapshot`() {
        val previous = directory.resolve("previous.tsv")
        val current = directory.resolve("current.tsv")
        val expected = snapshot("git status")
        CompletionLearningFileStore(previous).persist(expected)
        val files =
            CompletionLearningFileStore(current, previousPath = previous, openInput = { path ->
                if (path == previous) {
                    val bytes = Files.readAllBytes(path)
                    Files.move(previous, current)
                    ByteArrayInputStream(bytes)
                } else {
                    Files.newInputStream(path)
                }
            })

        assertEquals(expected, files.loadSnapshot().loadedSnapshot())
        assertFalse(Files.exists(previous))
    }

    @Test
    fun `reset clears a previous file without first hydrating it`() {
        val previous = Files.writeString(directory.resolve("previous.tsv"), "unsupported previous data")
        val current = directory.resolve("state/learning.tsv")
        val files = CompletionLearningFileStore(current, previousPath = previous)

        files.reset()

        assertFalse(Files.exists(previous))
        assertEquals(TerminalCompletionLearningSnapshot.EMPTY, files.loadSnapshot().loadedSnapshot())
    }

    @Test
    fun `ordinary writes preserve an ignored unsupported previous file until reset`() {
        val previous = Files.writeString(directory.resolve("previous.tsv"), "unsupported previous data")
        val current = directory.resolve("current.tsv")
        CompletionLearningFileStore(current).persist(snapshot("current command"))
        val files = CompletionLearningFileStore(current, previousPath = previous)
        assertEquals(snapshot("current command"), files.loadSnapshot().loadedSnapshot())

        files.persist(snapshot("new command"))

        assertEquals("unsupported previous data", Files.readString(previous))
        files.reset()
        assertFalse(Files.exists(previous))
        assertEquals(TerminalCompletionLearningSnapshot.EMPTY, files.loadSnapshot().loadedSnapshot())
    }

    @Test
    fun `an identical previous path never removes the current snapshot`() {
        val current = directory.resolve("current.tsv")
        val expected = snapshot("git status")
        val files = CompletionLearningFileStore(current, previousPath = directory.resolve("unused/../current.tsv"))
        files.persist(expected)
        assertEquals(expected, files.loadSnapshot().loadedSnapshot())
        files.reset()
        assertEquals(TerminalCompletionLearningSnapshot.EMPTY, files.loadSnapshot().loadedSnapshot())
        assertTrue(Files.exists(current))
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `a parent directory alias never removes the current snapshot during cleanup`() {
        val target = Files.createDirectory(directory.resolve("target"))
        val alias = Files.createSymbolicLink(directory.resolve("alias"), target)
        val current = target.resolve("learning.tsv")
        val files = CompletionLearningFileStore(current, previousPath = alias.resolve("learning.tsv"))
        files.persist(snapshot("git status"))
        files.reset()
        assertEquals(TerminalCompletionLearningSnapshot.EMPTY, files.loadSnapshot().loadedSnapshot())
        assertTrue(Files.exists(alias.resolve("learning.tsv")))
    }

    @Test
    fun `supplementary Unicode contexts survive file persistence and hydration exactly`() {
        val files = CompletionLearningFileStore(path("completion-unicode-context"))
        val learning = TerminalCompletionLearningStore()
        val profiles = listOf("shell😀", "shell👩‍💻", "shell\uD800\uDC00", "shell\uDBFF\uDFFF", "shell?", "shell\uFFFD")
        for ((index, profile) in profiles.withIndex()) {
            learning.recordCommandResult("git status", true, profile, "file:///repo/$profile/", index + 1L)
        }
        val expected = learning.snapshot()
        files.persist(expected)
        val loaded = files.loadSnapshot().loadedSnapshot()
        assertEquals(expected, loaded)
        val hydrated = TerminalCompletionLearningStore()
        hydrated.mergeSnapshot(loaded)
        assertEquals(expected, hydrated.snapshot())
        assertEquals(
            profiles.toSet(),
            hydrated
                .snapshot()
                .rankingStats
                .map { it.profileId }
                .toSet(),
        )
    }

    @Test
    fun `persist replaces the file synchronously`() {
        val path = path("completion-store")
        val store = CompletionLearningFileStore(path)
        val first = snapshot("git status")
        val second = snapshot("npm test")

        store.persist(first)
        assertEquals(first, store.loadSnapshot().loadedSnapshot())
        store.persist(second)
        assertEquals(second, store.loadSnapshot().loadedSnapshot())
    }

    @Test
    fun `credential events round trip as opaque evidence without replay text`() {
        val path = path("completion-store-private")
        val store = CompletionLearningFileStore(path)
        val curl = "curl -u alice:s3cr3t https://example.test"
        val mysql = "mysql -p hunter2"
        val docker = "docker login -u alice -p hunter2"
        val redis = "redis-cli -a hunter2"
        val sshpass = "sshpass -p hunter2 ssh host"
        val learned = snapshot(curl, mysql, docker, redis, sshpass)

        assertEquals(5, learned.rankingStats.size)
        assertTrue(learned.replayCommands.isEmpty())
        store.persist(learned)

        val loaded = store.loadSnapshot().loadedSnapshot()
        val persistedText = Files.readString(path)
        assertEquals(learned.rankingStats, loaded.rankingStats)
        assertTrue(loaded.replayCommands.isEmpty())
        assertEquals(5, persistedText.lineSequence().count { it.startsWith("R\t") })
        assertFalse(persistedText.lineSequence().any { it.startsWith("H\t") })
        assertFalse("s3cr3t" in persistedText)
        assertFalse("hunter2" in persistedText)
    }

    @Test
    fun `storage boundary removes an injected sensitive replay projection`() {
        val path = path("completion-store-injected-private")
        val store = CompletionLearningFileStore(path)
        val command = "mysql -p hunter2"
        val learned = snapshot(command)
        val stats = learned.rankingStats.single()
        val injected =
            TerminalCommandReplay(
                identityDigest = stats.identityDigest,
                commandLine = command,
                profileId = stats.profileId,
                workingDirectoryUri = stats.workingDirectoryUri,
            )

        store.persist(TerminalCompletionLearningSnapshot(learned.rankingStats, listOf(injected)))

        val loaded = store.loadSnapshot().loadedSnapshot()
        assertEquals(learned.rankingStats, loaded.rankingStats)
        assertTrue(loaded.replayCommands.isEmpty())
    }

    @Test
    fun `storage boundary removes replay backed only by failed evidence`() {
        val path = path("completion-store-negative-replay")
        val store = CompletionLearningFileStore(path)
        val command = "git failed-safe-command"
        val learning = TerminalCompletionLearningStore()
        learning.recordCommandResult(command, false, null, null, 1L)
        val stats = learning.snapshot().rankingStats.single()
        val injected = TerminalCommandReplay(stats.identityDigest, command)

        store.persist(TerminalCompletionLearningSnapshot(listOf(stats), listOf(injected)))

        val loaded = store.loadSnapshot().loadedSnapshot()
        assertEquals(listOf(stats), loaded.rankingStats)
        assertTrue(loaded.replayCommands.isEmpty())
    }

    @Test
    fun `storage boundary removes replay backed only by accepted feedback`() {
        val path = path("completion-store-accepted-replay")
        val store = CompletionLearningFileStore(path)
        val command = "git status"
        val learning = TerminalCompletionLearningStore()
        learning.recordSuggestionFeedback(command, TerminalCompletionFeedbackKind.ACCEPTED, null, null, 1L)
        val stats = learning.snapshot().rankingStats.single()
        val injected = TerminalCommandReplay(stats.identityDigest, command)

        store.persist(TerminalCompletionLearningSnapshot(listOf(stats), listOf(injected)))

        val loaded = store.loadSnapshot().loadedSnapshot()
        assertEquals(listOf(stats), loaded.rankingStats)
        assertTrue(loaded.replayCommands.isEmpty())
    }

    @Test
    fun `over-bound replay row is dropped without dropping later evidence or replay`() {
        val path = path("completion-store-encoded-bound")
        val learned = snapshot("界".repeat(4_096), "git status")

        CompletionLearningFileStore(path).persist(learned)

        val loaded = CompletionLearningFileStore(path).loadSnapshot().loadedSnapshot()
        assertEquals(2, loaded.rankingStats.size)
        assertEquals(listOf("git status"), loaded.replayCommands.map { it.commandLine })
        assertTrue(Files.readAllLines(path).all { it.toByteArray().size <= MAX_LINE_BYTES })
    }

    @Test
    fun `each write uses a unique same-directory temporary file and cleans it`() {
        val directory = createTempDirectory("completion-store-temporary")
        val path = directory.resolve(TerminalCompletionLearningCoordinator.currentFileName())
        val temporaryFiles = mutableListOf<Path>()
        val store =
            CompletionLearningFileStore(
                path = path,
                createTemporaryFile = { parent, prefix, suffix ->
                    Files.createTempFile(parent, prefix, suffix).also(temporaryFiles::add)
                },
            )

        store.persist(snapshot("command-1"))
        store.persist(snapshot("command-2"))

        assertEquals(2, temporaryFiles.distinct().size)
        assertTrue(temporaryFiles.all { it.parent == directory.toAbsolutePath().normalize() })
        assertTrue(temporaryFiles.none(Files::exists))
        assertEquals(
            "command-2",
            CompletionLearningFileStore(path)
                .loadSnapshot()
                .loadedSnapshot()
                .replayCommands
                .single()
                .commandLine,
        )
    }

    @Test
    fun `missing file is reported separately`() {
        assertSame(CompletionLearningFileLoadOutcome.Missing, CompletionLearningFileStore(path("completion-store-missing")).loadSnapshot())
    }

    @Test
    fun `legacy unknown and malformed formats are rejected without changing bytes`() {
        val valid = CompletionLearningSnapshotCodec.encode(snapshot("git status"))
        val cases =
            listOf(
                "KetraTerm_COMMAND_COMPLETION_STATS\t2\n".encodeToByteArray(),
                "KetraTerm_COMMAND_COMPLETION_LEARNING\t999\n".encodeToByteArray(),
                (valid + "X\tunsupported").joinToString("\n", postfix = "\n").encodeToByteArray(),
                (valid.take(2) + "H\ttoo-short").joinToString("\n", postfix = "\n").encodeToByteArray(),
            )
        for (bytes in cases) assertRejectedWithoutMutation(bytes)
    }

    @Test
    fun `oversized input file line and line count are rejected`() {
        assertRejectedWithoutMutation(ByteArray(MAX_FILE_BYTES + 1) { 'x'.code.toByte() })
        assertRejectedWithoutMutation(HEADER.encodeToByteArray() + ByteArray(MAX_LINE_BYTES + 1) { 'x'.code.toByte() })

        val row = CompletionLearningSnapshotCodec.encode(snapshot("git status"))[1]
        val excessiveRows =
            buildString {
                appendLine(HEADER.trimEnd())
                repeat(MAX_FILE_LINES) { appendLine(row) }
            }.encodeToByteArray()
        assertRejectedWithoutMutation(excessiveRows)
    }

    @Test
    fun `non-file path is rejected`() {
        val directory = createTempDirectory("completion-store-directory")

        assertSame(CompletionLearningFileLoadOutcome.Rejected, CompletionLearningFileStore(directory).loadSnapshot())
        assertTrue(Files.isDirectory(directory))
    }

    @Test
    fun `input failure is returned separately`() {
        val path = path("completion-store-failed")
        Files.writeString(path, HEADER.trimEnd())
        val expectedFailure = IOException("test read failure")

        val outcome = CompletionLearningFileStore(path, openInput = { throw expectedFailure }).loadSnapshot()

        assertSame(expectedFailure, assertIs<CompletionLearningFileLoadOutcome.Failed>(outcome).cause)
        assertEquals(HEADER.trimEnd(), Files.readString(path))
    }

    @Test
    fun `output failure is rethrown`() {
        val expectedFailure = IOException("test write failure")
        val store =
            CompletionLearningFileStore(
                path = path("completion-store-output-failed"),
                createTemporaryFile = { _, _, _ -> throw expectedFailure },
            )

        assertSame(expectedFailure, assertFailsWith<IOException> { store.persist(snapshot("git status")) })
    }

    @Test
    fun `write failure survives temporary cleanup failure`() {
        val target = Files.writeString(directory.resolve("learning.tsv"), "existing snapshot")
        for (blockCleanup in listOf(false, true)) {
            val temporary = Files.createDirectory(directory.resolve("temporary-$blockCleanup"))
            val child = temporary.resolve("child")
            if (blockCleanup) Files.writeString(child, "prevent directory removal")
            val store = CompletionLearningFileStore(target, createTemporaryFile = { _, _, _ -> temporary })

            val failure = assertFailsWith<IOException> { store.persist(snapshot("git status")) }

            assertFalse(failure is DirectoryNotEmptyException)
            if (blockCleanup) {
                assertIs<DirectoryNotEmptyException>(failure.suppressed.single())
                assertEquals("prevent directory removal", Files.readString(child))
            } else {
                assertTrue(failure.suppressed.isEmpty())
                assertFalse(Files.exists(temporary))
            }
            assertEquals("existing snapshot", Files.readString(target))
        }
    }

    private fun assertRejectedWithoutMutation(originalBytes: ByteArray) {
        val path = path("completion-store-rejected")
        Files.write(path, originalBytes)

        assertSame(CompletionLearningFileLoadOutcome.Rejected, CompletionLearningFileStore(path).loadSnapshot())
        assertContentEquals(originalBytes, Files.readAllBytes(path))
    }

    private fun CompletionLearningFileLoadOutcome.loadedSnapshot(): TerminalCompletionLearningSnapshot =
        assertIs<CompletionLearningFileLoadOutcome.Loaded>(this).snapshot

    private fun snapshot(vararg commands: String): TerminalCompletionLearningSnapshot {
        val learning = TerminalCompletionLearningStore()
        for ((index, command) in commands.withIndex()) {
            learning.recordCommandResult(command, true, null, null, index + 1L)
        }
        return learning.snapshot()
    }

    private fun path(prefix: String) = createTempDirectory(prefix).resolve(TerminalCompletionLearningCoordinator.currentFileName())

    private companion object {
        private const val LEGACY_FILE_NAME = "command-completion-learning-v3.tsv"
        private const val HEADER = "KetraTerm_COMMAND_COMPLETION_LEARNING\t3\n"
        private const val MAX_FILE_BYTES = 2_000_256
        private const val MAX_FILE_LINES = 1 + 2_048 + 2_048
        private const val MAX_LINE_BYTES = 16 * 1024
    }
}
