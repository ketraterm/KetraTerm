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

import io.github.ketraterm.completion.internal.CompletionLearningIndexCache
import io.github.ketraterm.completion.internal.CompletionLearningIndexes
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackKind
import io.github.ketraterm.completion.stats.CommandCompletionStatsIndex
import io.github.ketraterm.completion.stats.isRecordableStatsEvent

/**
 * Mutable, bounded in-memory store for exact command completion learning.
 *
 * Hosts record command lifecycle outcomes and explicit popup feedback. Optional
 * persistence may hydrate the store once through [mergeSnapshot] and persist
 * [snapshot] values. The store performs no I/O and does not emit candidates.
 * Mutations are serialized around one mutable exact index. Published snapshots
 * are immutable and retain identity until their contents change.
 *
 * @param capacity maximum distinct exact-command rows retained.
 * @throws IllegalArgumentException if [capacity] is not positive.
 */
class TerminalCompletionLearningStore
    @JvmOverloads
    constructor(
        capacity: Int = DEFAULT_CAPACITY,
    ) {
        private val lock = Any()
        private val commandStats = CommandCompletionStatsIndex(capacity)
        private val learningIndexCache = CompletionLearningIndexCache()

        @Volatile
        private var publishedSnapshot = TerminalCommandCompletionStatsSnapshot.EMPTY

        @Volatile
        private var snapshotDirty = false

        /**
         * Adds distinct aggregate events from [snapshot] to retained learning.
         *
         * Rows sharing the same case-preserved command text and canonical
         * context key have their counters added with saturation and retain the
         * newest timestamp. Callers must not merge the same aggregate event set
         * more than once.
         *
         * @param snapshot aggregate events not already represented by this store.
         */
        fun mergeSnapshot(snapshot: TerminalCommandCompletionStatsSnapshot) {
            if (snapshot.commandStats.isEmpty()) return
            synchronized(lock) {
                commandStats.mergeAll(snapshot.commandStats)
                snapshotDirty = true
            }
        }

        /**
         * Returns the current immutable exact-command statistics snapshot.
         *
         * @return identity-stable snapshot until the retained rows change.
         */
        fun snapshot(): TerminalCommandCompletionStatsSnapshot {
            if (!snapshotDirty) return publishedSnapshot
            return synchronized(lock) {
                if (snapshotDirty) {
                    val rows = commandStats.snapshot()
                    if (publishedSnapshot.commandStats != rows) {
                        publishedSnapshot = TerminalCommandCompletionStatsSnapshot(rows)
                    }
                    snapshotDirty = false
                }
                publishedSnapshot
            }
        }

        /** Returns identity-cached ranking, history, and observed-token indexes. */
        internal fun indexesFor(shellSyntax: TerminalShellSyntax): CompletionLearningIndexes =
            learningIndexCache.indexesFor(snapshot(), shellSyntax)

        /**
         * Records a completed command execution.
         *
         * Blank, multiline, and otherwise non-recordable commands are ignored.
         *
         * @param commandLine command text executed by the shell.
         * @param successful whether the command exited successfully.
         * @param profileId optional host profile id.
         * @param workingDirectoryUri optional working-directory URI.
         * @param usedAtEpochMillis host timestamp for the execution event.
         * @return `true` if the retained exact-command rows changed.
         */
        fun recordCommandResult(
            commandLine: String,
            successful: Boolean,
            profileId: String?,
            workingDirectoryUri: String?,
            usedAtEpochMillis: Long,
        ): Boolean =
            isRecordableStatsEvent(commandLine, usedAtEpochMillis) &&
                synchronized(lock) {
                    val changed =
                        commandStats.recordCommandResult(
                            commandLine = commandLine,
                            successful = successful,
                            profileId = profileId,
                            workingDirectoryUri = workingDirectoryUri,
                            usedAtEpochMillis = usedAtEpochMillis,
                        )
                    if (changed) snapshotDirty = true
                    changed
                }

        /**
         * Records explicit user feedback for the exact suggested command.
         *
         * @param commandLine command text represented after applying the suggestion.
         * @param feedback accepted or dismissed feedback kind.
         * @param profileId optional host profile id.
         * @param workingDirectoryUri optional working-directory URI.
         * @param feedbackAtEpochMillis host timestamp for the feedback event.
         * @return `true` if the retained exact-command rows changed.
         */
        fun recordSuggestionFeedback(
            commandLine: String,
            feedback: TerminalCompletionFeedbackKind,
            profileId: String?,
            workingDirectoryUri: String?,
            feedbackAtEpochMillis: Long,
        ): Boolean =
            isRecordableStatsEvent(commandLine, feedbackAtEpochMillis) &&
                synchronized(lock) {
                    val changed =
                        commandStats.recordSuggestionFeedback(
                            commandLine = commandLine,
                            feedback = feedback,
                            profileId = profileId,
                            workingDirectoryUri = workingDirectoryUri,
                            feedbackAtEpochMillis = feedbackAtEpochMillis,
                        )
                    if (changed) snapshotDirty = true
                    changed
                }

        private companion object {
            private const val DEFAULT_CAPACITY = 2048
        }
    }
