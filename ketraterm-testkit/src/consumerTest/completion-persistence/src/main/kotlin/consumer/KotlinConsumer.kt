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
@file:OptIn(io.github.ketraterm.completion.api.ExperimentalTerminalCompletionApi::class)

package consumer

import io.github.ketraterm.completion.api.TerminalCompletionLearningStore
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackKind
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningCoordinator
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

fun main() =
    runBlocking {
        JavaConsumer.verify()
        val directory = Files.createTempDirectory("completion-persistence-consumer")
        val path = directory.resolve(TerminalCompletionLearningCoordinator.currentFileName())
        try {
            val original = TerminalCompletionLearningStore()
            val writer = JavaConsumer.createCoordinator(original, this, path, true)
            try {
                writer.recordCommandResult("git status", true, "bash", directory.toUri().toString(), 1L)
                writer.recordSuggestionFeedback(
                    "git status",
                    TerminalCompletionFeedbackKind.ACCEPTED,
                    "bash",
                    directory.toUri().toString(),
                    2L,
                )
                check(
                    original
                        .snapshot()
                        .rankingStats
                        .single()
                        .acceptedCount == 1,
                )
            } finally {
                writer.closeAndFlush()
            }
            check(Files.size(path) > 0)
            val restored = TerminalCompletionLearningStore()
            val reader = TerminalCompletionLearningCoordinator(restored, this, path, true) { throw AssertionError(it) }
            reader.closeAndFlush()
            check(restored.snapshot() == original.snapshot())
            val resetter = TerminalCompletionLearningCoordinator(restored, this, path, false) { throw AssertionError(it) }
            try {
                resetter.resetLearning().await()
                check(restored.snapshot().rankingStats.isEmpty())
            } finally {
                resetter.closeAndFlush()
            }
            val empty = TerminalCompletionLearningStore()
            val verifier = TerminalCompletionLearningCoordinator(empty, this, path, true) { throw AssertionError(it) }
            verifier.closeAndFlush()
            check(empty.snapshot().rankingStats.isEmpty())
        } finally {
            Files.deleteIfExists(path)
            Files.delete(directory)
        }
    }
