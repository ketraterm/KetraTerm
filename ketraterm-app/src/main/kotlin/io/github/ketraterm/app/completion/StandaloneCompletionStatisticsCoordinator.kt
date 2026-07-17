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

import io.github.ketraterm.completion.api.TerminalCommandStatsCompletionSource
import io.github.ketraterm.completion.api.TerminalCompletionPersistencePolicy
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import io.github.ketraterm.completion.persistence.TerminalCompletionStatsStore
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackHandler
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standalone owner of serialized completion learning and optional persistence.
 *
 * Disk loading, store replacement, mutations, and store shutdown all run on a
 * dedicated daemon worker, so constructing or reconfiguring the Swing window
 * never reads completion-learning files on the event-dispatch thread.
 */
internal class StandaloneCompletionStatisticsCoordinator(
    private val statsSource: TerminalCommandStatsCompletionSource,
    initialPersistencePath: Path?,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "standalone-completion-stats").apply { isDaemon = true }
        }
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
                replacement.persist(statsSource.snapshotAll())
            }
        }
    }

    private fun executeMutation(mutation: () -> Unit) {
        execute {
            mutation()
            store?.persist(statsSource.snapshotAll())
        }
    }

    private fun execute(action: () -> Unit) {
        if (closed.get()) return
        runCatching { executor.execute(action) }
    }

    /** Drains queued learning, closes persistence, and stops the worker. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching {
            executor
                .submit {
                    store?.close()
                    store = null
                    storePath = null
                }.get()
        }
        executor.shutdown()
        val terminated =
            try {
                executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        if (!terminated) executor.shutdownNow()
    }

    private companion object {
        private const val SHUTDOWN_TIMEOUT_SECONDS = 5L

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
