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

import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalCompletionStatsStoreTest {
    @Test
    fun `persist replaces the file synchronously`() {
        val path = createTempDirectory("completion-store").resolve(TerminalCompletionStatsStore.currentFileName())
        val store = TerminalCompletionStatsStore(path)
        val first = snapshot("git status")
        val second = snapshot("npm test")

        store.persist(first)
        assertEquals(first, store.loadSnapshot())
        store.persist(second)
        assertEquals(second, store.loadSnapshot())
    }

    @Test
    fun `private command rows are removed before writing`() {
        val path = createTempDirectory("completion-store-private").resolve(TerminalCompletionStatsStore.currentFileName())
        val store = TerminalCompletionStatsStore(path)
        store.persist(
            TerminalCommandCompletionStatsSnapshot(
                commandStats =
                    listOf(
                        record("git status"),
                        record("docker login --password hunter2"),
                    ),
            ),
        )

        assertEquals(listOf(record("git status")), store.loadSnapshot().commandStats)
    }

    @Test
    fun `oversized input file loads as empty`() {
        val path = createTempDirectory("completion-store-large").resolve(TerminalCompletionStatsStore.currentFileName())
        Files.write(path, ByteArray(5 * 1024 * 1024) { 'x'.code.toByte() })

        assertEquals(TerminalCommandCompletionStatsSnapshot(), TerminalCompletionStatsStore(path).loadSnapshot())
    }

    private fun snapshot(command: String) = TerminalCommandCompletionStatsSnapshot(commandStats = listOf(record(command)))

    private fun record(command: String) =
        TerminalCommandCompletionStats(
            commandLine = command,
            successCount = 1,
            failureCount = 0,
            lastUsedEpochMillis = 42L,
        )
}
