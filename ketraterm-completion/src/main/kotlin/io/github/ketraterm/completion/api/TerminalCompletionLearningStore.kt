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
import io.github.ketraterm.completion.model.*
import io.github.ketraterm.completion.stats.CommandCompletionStatsIndex
import io.github.ketraterm.completion.stats.CommandShapeStatsIndex
import io.github.ketraterm.completion.stats.CompletionFeedbackStatsIndex
import io.github.ketraterm.completion.stats.isRecordableStatsEvent
import java.util.concurrent.atomic.AtomicReference

/**
 * Mutable, bounded in-memory store for aggregated completion learning.
 *
 * Hosts may load and persist [snapshot] values, record command lifecycle
 * outcomes, and feed explicit popup feedback. The store performs no I/O and
 * does not emit candidates. All operations are thread-safe, and repeated
 * [snapshot] calls return the same immutable object until the next mutation.
 *
 * @param capacity maximum distinct rows retained in each statistics family.
 * @param commandSpecs command specifications used to classify
 * privacy-preserving command-family shapes.
 * @throws IllegalArgumentException if [capacity] is not positive.
 */
class TerminalCompletionLearningStore
    @JvmOverloads
    constructor(
        private val capacity: Int = DEFAULT_CAPACITY,
        commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
    ) {
        init {
            require(capacity > 0) { "capacity must be > 0, was $capacity" }
        }

        private val commandSpecs = commandSpecs.toList()
        private val learningIndexCache = CompletionLearningIndexCache()
        private val state = AtomicReference(emptyState())

        /**
         * Replaces all retained statistics with [snapshot].
         *
         * Each statistics family independently compacts duplicate keys,
         * rejects malformed rows, and applies the configured capacity.
         *
         * @param snapshot compact completion-learning snapshot loaded by a host.
         */
        fun replaceSnapshot(snapshot: TerminalCommandCompletionStatsSnapshot) {
            replaceState(stateFrom(snapshot))
        }

        /**
         * Adds distinct aggregate events from [snapshot] to retained learning.
         *
         * Rows sharing a canonical exact, shape, or provider context key have
         * their counters added with saturation and retain the newest timestamp.
         * Callers must not merge the same aggregate event set more than once.
         *
         * @param snapshot aggregate events not already represented by this store.
         */
        fun mergeSnapshot(snapshot: TerminalCommandCompletionStatsSnapshot) {
            if (snapshot == TerminalCommandCompletionStatsSnapshot.EMPTY) return
            updateState { current ->
                val commandStats = current.commandStats.copy().apply { mergeAll(snapshot.commandStats) }
                val shapeStats = current.shapeStats.copy().apply { mergeAll(snapshot.shapeStats) }
                val feedbackStats = current.feedbackStats.copy().apply { mergeAll(snapshot.feedbackStats) }
                createState(commandStats, shapeStats, feedbackStats)
            }
        }

        /**
         * Returns every retained statistics family in one immutable snapshot.
         *
         * @return exact command, command-shape, and provider-feedback statistics.
         */
        fun snapshot(): TerminalCommandCompletionStatsSnapshot = state.get().snapshot

        /** Returns identity-cached learned indexes shared by ranking and history recovery. */
        internal fun indexesFor(
            shellSyntax: TerminalShellSyntax,
            commandSpecs: List<TerminalCommandSpec>,
        ): CompletionLearningIndexes = learningIndexCache.indexesFor(state.get().snapshot, shellSyntax, commandSpecs)

        /**
         * Records a completed command execution.
         *
         * Blank, multiline, and otherwise non-recordable commands are ignored
         * by the bounded indexes.
         *
         * @param commandLine command text executed by the shell.
         * @param successful whether the command exited successfully.
         * @param profileId optional host profile id.
         * @param workingDirectoryUri optional working-directory URI.
         * @param usedAtEpochMillis host timestamp for the execution event.
         */
        fun recordCommandResult(
            commandLine: String,
            successful: Boolean,
            profileId: String?,
            workingDirectoryUri: String?,
            usedAtEpochMillis: Long,
        ) {
            if (!isRecordableStatsEvent(commandLine, usedAtEpochMillis)) return
            updateState { current ->
                val commandStats = current.commandStats.copy()
                commandStats.recordCommandResult(
                    commandLine = commandLine,
                    successful = successful,
                    profileId = profileId,
                    workingDirectoryUri = workingDirectoryUri,
                    usedAtEpochMillis = usedAtEpochMillis,
                )
                val shapeStats = current.shapeStats.copy()
                shapeStats.recordCommandResult(
                    commandLine = commandLine,
                    successful = successful,
                    profileId = profileId,
                    workingDirectoryUri = workingDirectoryUri,
                    usedAtEpochMillis = usedAtEpochMillis,
                )
                createState(commandStats, shapeStats, current.feedbackStats)
            }
        }

        /**
         * Records explicit user feedback for a displayed suggestion.
         *
         * @param commandLine command text represented after applying the suggestion.
         * @param feedback accepted or dismissed feedback kind.
         * @param profileId optional host profile id.
         * @param workingDirectoryUri optional working-directory URI.
         * @param feedbackAtEpochMillis host timestamp for the feedback event.
         * @param context optional source-specific candidate context.
         */
        @JvmOverloads
        fun recordSuggestionFeedback(
            commandLine: String,
            feedback: TerminalCompletionFeedbackKind,
            profileId: String?,
            workingDirectoryUri: String?,
            feedbackAtEpochMillis: Long,
            context: TerminalCompletionFeedbackContext? = null,
        ) {
            if (!isRecordableStatsEvent(commandLine, feedbackAtEpochMillis)) return
            updateState { current ->
                val commandStats = current.commandStats.copy()
                commandStats.recordSuggestionFeedback(
                    commandLine = commandLine,
                    feedback = feedback,
                    profileId = profileId,
                    workingDirectoryUri = workingDirectoryUri,
                    feedbackAtEpochMillis = feedbackAtEpochMillis,
                )
                val shapeStats = current.shapeStats.copy()
                shapeStats.recordSuggestionFeedback(
                    commandLine = commandLine,
                    feedback = feedback,
                    profileId = profileId,
                    workingDirectoryUri = workingDirectoryUri,
                    feedbackAtEpochMillis = feedbackAtEpochMillis,
                )
                val feedbackStats =
                    if (context == null) {
                        current.feedbackStats
                    } else {
                        current.feedbackStats.copy().also { feedbackStats ->
                            feedbackStats.recordSuggestionFeedback(
                                context = context,
                                feedback = feedback,
                                profileId = profileId,
                                workingDirectoryUri = workingDirectoryUri,
                                feedbackAtEpochMillis = feedbackAtEpochMillis,
                            )
                        }
                    }
                createState(commandStats, shapeStats, feedbackStats)
            }
        }

        private fun emptyState(): LearningState =
            createState(
                CommandCompletionStatsIndex(capacity),
                CommandShapeStatsIndex(capacity, commandSpecs),
                CompletionFeedbackStatsIndex(capacity),
            )

        private fun stateFrom(snapshot: TerminalCommandCompletionStatsSnapshot): LearningState {
            val commandStats = CommandCompletionStatsIndex(capacity).apply { replaceAll(snapshot.commandStats) }
            val shapeStats = CommandShapeStatsIndex(capacity, commandSpecs).apply { replaceAll(snapshot.shapeStats) }
            val feedbackStats = CompletionFeedbackStatsIndex(capacity).apply { replaceAll(snapshot.feedbackStats) }
            return createState(commandStats, shapeStats, feedbackStats)
        }

        private fun createState(
            commandStats: CommandCompletionStatsIndex,
            shapeStats: CommandShapeStatsIndex,
            feedbackStats: CompletionFeedbackStatsIndex,
        ): LearningState =
            LearningState(
                commandStats = commandStats,
                shapeStats = shapeStats,
                feedbackStats = feedbackStats,
                snapshot =
                    TerminalCommandCompletionStatsSnapshot(
                        commandStats = commandStats.snapshot(),
                        shapeStats = shapeStats.snapshot(),
                        feedbackStats = feedbackStats.snapshot(),
                    ),
            )

        private fun replaceState(replacement: LearningState) {
            while (true) {
                val current = state.get()
                if (current.snapshot == replacement.snapshot) return
                if (state.compareAndSet(current, replacement)) return
            }
        }

        private fun updateState(transform: (LearningState) -> LearningState) {
            while (true) {
                val current = state.get()
                val updated = transform(current)
                if (current.snapshot == updated.snapshot) return
                if (state.compareAndSet(current, updated)) return
            }
        }

        /** Copy-on-write state; contained indexes are never mutated after publication. */
        private class LearningState(
            val commandStats: CommandCompletionStatsIndex,
            val shapeStats: CommandShapeStatsIndex,
            val feedbackStats: CompletionFeedbackStatsIndex,
            val snapshot: TerminalCommandCompletionStatsSnapshot,
        )

        private companion object {
            private const val DEFAULT_CAPACITY = 2048
        }
    }
