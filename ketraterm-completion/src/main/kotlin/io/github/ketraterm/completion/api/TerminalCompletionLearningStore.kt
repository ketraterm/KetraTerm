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
import io.github.ketraterm.completion.internal.terminalCompletionRankingIdentity
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackKind
import io.github.ketraterm.completion.model.TerminalCompletionLearningSnapshot
import io.github.ketraterm.completion.stats.CompletionLearningStatsIndex
import io.github.ketraterm.completion.stats.isRecordableStatsEvent
import java.util.function.Predicate

/**
 * Mutable, bounded in-memory store for exact command completion learning.
 *
 * Hosts record command lifecycle outcomes and explicit popup feedback. Optional
 * persistence may hydrate the store once through [mergeSnapshot] and persist
 * [snapshot] values. The store performs no I/O and does not emit candidates.
 * Mutations are serialized around one mutable exact index. Ranking rows retain
 * only opaque identities; plaintext replay is attached only to successful
 * commands that both [TerminalCompletionReplayPolicy] and [replayFilter] approve. Published
 * snapshots are immutable and retain identity until their contents change.
 *
 * The host filter is an additional admission restriction for executions and
 * imported replay rows. Returning false for every command disables plaintext
 * replay while preserving opaque ranking evidence. The filter runs synchronously
 * outside the store lock, only after the built-in policy approves a command.
 * It may run concurrently and must be thread-safe and consistent for this store's
 * lifetime. Failures propagate before the operation records or merges evidence.
 * Admission filtering does not retroactively remove previously retained rows.
 *
 * @param replayFilter additional host restriction on plaintext replay retention.
 * @param capacity maximum distinct exact-command rows retained.
 * @throws IllegalArgumentException if [capacity] is not positive.
 */
public class TerminalCompletionLearningStore
    @JvmOverloads
    constructor(
        private val replayFilter: Predicate<String>,
        capacity: Int = DEFAULT_CAPACITY,
    ) {
        /** Creates a store using only the built-in plaintext replay policy. */
        @JvmOverloads
        public constructor(capacity: Int = DEFAULT_CAPACITY) : this(DEFAULT_REPLAY_FILTER, capacity)

        private val lock = Any()
        private val learningStats = CompletionLearningStatsIndex(capacity)
        private val learningIndexCache = CompletionLearningIndexCache()

        @Volatile
        private var publishedSnapshot = TerminalCompletionLearningSnapshot.EMPTY

        @Volatile
        private var snapshotDirty = false

        /**
         * Adds distinct aggregate events from [snapshot] to retained learning.
         *
         * Opaque rows sharing an identity and canonical context have counters
         * added with saturation. Replay rows must pass the built-in policy and host
         * filter and reference retained positive evidence. Callers must not merge
         * the same aggregate event set more than once.
         *
         * @param snapshot aggregate events not already represented by this store.
         */
        public fun mergeSnapshot(snapshot: TerminalCompletionLearningSnapshot) {
            if (snapshot.rankingStats.isEmpty() && snapshot.replayCommands.isEmpty()) return
            val sanitizedSnapshot =
                snapshot.copy(
                    replayCommands =
                        snapshot.replayCommands.filter { replay ->
                            allowsReplay(replay.commandLine)
                        },
                )
            synchronized(lock) {
                learningStats.mergeSnapshot(sanitizedSnapshot)
                snapshotDirty = true
            }
        }

        /**
         * Returns the current immutable exact-command statistics snapshot.
         *
         * @return identity-stable snapshot until the retained rows change.
         */
        public fun snapshot(): TerminalCompletionLearningSnapshot {
            if (!snapshotDirty) return publishedSnapshot
            return synchronized(lock) {
                if (snapshotDirty) {
                    val snapshot = learningStats.snapshot()
                    if (publishedSnapshot != snapshot) publishedSnapshot = snapshot
                    snapshotDirty = false
                }
                publishedSnapshot
            }
        }

        /** Removes all retained ranking evidence and replay commands. */
        public fun clear() {
            synchronized(lock) {
                learningStats.clear()
                publishedSnapshot = TerminalCompletionLearningSnapshot.EMPTY
                snapshotDirty = false
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
        public fun recordCommandResult(
            commandLine: String,
            successful: Boolean,
            profileId: String?,
            workingDirectoryUri: String?,
            usedAtEpochMillis: Long,
        ): Boolean {
            if (!isRecordableStatsEvent(commandLine, usedAtEpochMillis)) return false
            val identityDigest = terminalCompletionRankingIdentity(commandLine)
            val replayCommand =
                commandLine.takeIf {
                    successful && allowsReplay(commandLine)
                }
            return synchronized(lock) {
                val changed =
                    learningStats.recordCommandResult(
                        identityDigest = identityDigest,
                        replayCommandLine = replayCommand,
                        successful = successful,
                        profileId = profileId,
                        workingDirectoryUri = workingDirectoryUri,
                        usedAtEpochMillis = usedAtEpochMillis,
                    )
                if (changed) snapshotDirty = true
                changed
            }
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
        public fun recordSuggestionFeedback(
            commandLine: String,
            feedback: TerminalCompletionFeedbackKind,
            profileId: String?,
            workingDirectoryUri: String?,
            feedbackAtEpochMillis: Long,
        ): Boolean {
            if (!isRecordableStatsEvent(commandLine, feedbackAtEpochMillis)) return false
            val identityDigest = terminalCompletionRankingIdentity(commandLine)
            return synchronized(lock) {
                val changed =
                    learningStats.recordSuggestionFeedback(
                        identityDigest = identityDigest,
                        feedback = feedback,
                        profileId = profileId,
                        workingDirectoryUri = workingDirectoryUri,
                        feedbackAtEpochMillis = feedbackAtEpochMillis,
                    )
                if (changed) snapshotDirty = true
                changed
            }
        }

        private fun allowsReplay(commandLine: String): Boolean =
            TerminalCompletionReplayPolicy.allowsPlaintext(commandLine) && replayFilter.test(commandLine)

        private companion object {
            private const val DEFAULT_CAPACITY = 2048
            private val DEFAULT_REPLAY_FILTER = Predicate<String> { true }
        }
    }
