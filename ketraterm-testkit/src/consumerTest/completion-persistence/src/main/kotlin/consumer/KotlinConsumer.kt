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
package consumer

import io.github.ketraterm.completion.api.TerminalCompletionLearningStore
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningCoordinator
import kotlinx.coroutines.*
import java.nio.file.Files

fun main() =
    runBlocking {
        JavaConsumer.verify()
        val directory = Files.createTempDirectory("completion-persistence-consumer")
        val path = directory.resolve(TerminalCompletionLearningCoordinator.currentFileName())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val learning = TerminalCompletionLearningStore()
            val writer =
                TerminalCompletionLearningCoordinator(
                    learning,
                    scope,
                    path,
                    true,
                    Dispatchers.Unconfined,
                ) { throw AssertionError(it) }
            writer.recordCommandResult("git status", true, "bash", null, 1L)
            writer.closeAndFlush()
            check(
                Files.isRegularFile(path) &&
                    learning
                        .snapshot()
                        .rankingStats
                        .single()
                        .useCount == 1,
            )
            val restored = TerminalCompletionLearningStore()
            val reader =
                TerminalCompletionLearningCoordinator(
                    restored,
                    scope,
                    path,
                    true,
                    Dispatchers.Unconfined,
                ) { throw AssertionError(it) }
            reader.closeAndFlush()
            check(restored.snapshot() == learning.snapshot())
            var rejected = 0
            Files.writeString(path, "unsupported format\n")
            val invalid =
                TerminalCompletionLearningCoordinator(
                    TerminalCompletionLearningStore(),
                    scope,
                    path,
                    true,
                    Dispatchers.Unconfined,
                ) { rejected++ }
            invalid.closeAndFlush()
            check(rejected == 1 && Files.readString(path) == "unsupported format\n")
        } finally {
            scope.cancel()
            Files.deleteIfExists(path)
            Files.delete(directory)
        }
    }
