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
package io.github.ketraterm.app.completion

import io.github.ketraterm.completion.api.TerminalCompletionLearningStore
import io.github.ketraterm.completion.api.TerminalCompletionPersistencePolicy
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningWorker
import io.github.ketraterm.completion.persistence.TerminalCompletionStatsStore
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackHandler
import java.nio.file.Path

/**
 * Standalone owner of serialized completion learning and optional persistence.
 *
 * Disk loading, store replacement, mutations, and store shutdown all run on a
 * dedicated daemon worker, so constructing or reconfiguring the Swing window
 * never reads completion-learning files on the event-dispatch thread.
 */
internal class StandaloneCompletionStatisticsCoordinator(
    private val statsSource: TerminalCompletionLearningStore,
    initialPersistencePath: Path?,
) : AutoCloseable {
    private val worker = TerminalCompletionLearningWorker("standalone-completion-stats")
    private var storePath: Path? = null
    private var store: TerminalCompletionStatsStore? = null
    private val feedbackRecorder =
        StandaloneCompletionFeedbackRecorder(
            statsSource = statsSource,
            submitMutation = ::executeMutation,
        )

    init {
        setPersistencePath(initialPersistencePath)
    }

    /** Creates a feedback handler whose mutations are serialized by this owner. */
    fun createFeedbackHandler(
        profileId: String?,
        workingDirectoryUriProvider: () -> String?,
    ): SwingShellSuggestionFeedbackHandler = feedbackRecorder.createHandler(profileId, workingDirectoryUriProvider)

    /** Records one privacy-filtered shell command result off the caller thread. */
    fun recordFinishedCommand(
        commandLine: String,
        successful: Boolean,
        profileId: String?,
        workingDirectoryUri: String?,
        usedAtEpochMillis: Long,
    ) {
        if (!TerminalCompletionPersistencePolicy.allowsCommand(commandLine)) return
        executeMutation {
            statsSource.recordCommandResult(
                commandLine = commandLine,
                successful = successful,
                profileId = profileId,
                workingDirectoryUri = workingDirectoryUri,
                usedAtEpochMillis = usedAtEpochMillis,
            )
        }
    }

    /** Enables, switches, or disables the persistence store asynchronously. */
    fun setPersistencePath(path: Path?) {
        execute {
            if (path == storePath) return@execute
            store?.close()
            store = null
            storePath = path
            if (path != null) {
                val replacement = TerminalCompletionStatsStore(path)
                store = replacement
                statsSource.replaceSnapshot(
                    mergeSnapshots(replacement.loadSnapshot(), statsSource.snapshotAll()),
                )
                replacement.persistBlocking(statsSource.snapshotAll())
            }
        }
    }

    private fun executeMutation(mutation: () -> Unit) {
        execute {
            mutation()
            store?.persistBlocking(statsSource.snapshotAll())
        }
    }

    private fun execute(action: () -> Unit) {
        worker.submit(action)
    }

    /** Drains queued learning, closes persistence, and stops the worker. */
    override fun close() {
        worker.close {
            store?.close()
            store = null
            storePath = null
        }
    }

    private companion object {
        private fun mergeSnapshots(
            loaded: TerminalCommandCompletionStatsSnapshot,
            live: TerminalCommandCompletionStatsSnapshot,
        ): TerminalCommandCompletionStatsSnapshot =
            TerminalCommandCompletionStatsSnapshot(
                commandStats = loaded.commandStats + live.commandStats,
                shapeStats = loaded.shapeStats + live.shapeStats,
                feedbackStats = loaded.feedbackStats + live.feedbackStats,
            )
    }
}
