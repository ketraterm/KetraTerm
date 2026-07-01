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
package io.github.ketraterm.completion

/**
 * Bounded in-memory completion source backed by aggregated command statistics.
 *
 * Hosts feed this source from compact persisted metadata and live command or
 * suggestion feedback. The source never scans raw history, performs I/O, spawns
 * shells, or depends on UI frameworks. All public methods are thread-safe.
 *
 * @param capacity maximum distinct command/profile/directory rows retained.
 * @param commandSpecs command specifications used to classify command-family
 * shapes for privacy-preserving structural learning.
 */
class TerminalCommandStatsCompletionSource
    @JvmOverloads
    constructor(
        capacity: Int = DEFAULT_CAPACITY,
        commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
    ) : TerminalCompletionSource {
        init {
            require(capacity > 0) { "capacity must be > 0, was $capacity" }
        }

        private val lock = Any()
        private val commandStats = CommandCompletionStatsIndex(capacity)
        private val shapeStats = CommandShapeStatsIndex(capacity, commandSpecs)
        private val feedbackStats = CompletionFeedbackStatsIndex(capacity)

        /**
         * Replaces the current index with [records].
         *
         * When multiple records share the same normalized command, profile, and
         * working directory key, the newest record by [TerminalCommandCompletionStats.lastUsedEpochMillis]
         * wins. At most [capacity] rows are retained.
         *
         * @param records compact command-stat rows loaded by a host.
         */
        fun replaceAll(records: List<TerminalCommandCompletionStats>) {
            synchronized(lock) {
                commandStats.replaceAll(records)
            }
        }

        /**
         * Replaces the current command-shape index with [records].
         *
         * Duplicate shape/profile/directory rows are compacted by keeping the
         * newest timestamp. At most [capacity] rows are retained.
         *
         * @param records compact shape-stat rows loaded by a host.
         */
        fun replaceShapeStats(records: List<TerminalCommandShapeStats>) {
            synchronized(lock) {
                shapeStats.replaceAll(records)
            }
        }

        /**
         * Replaces the current source-specific feedback index with [records].
         *
         * Duplicate rows are compacted by keeping the newest timestamp. At most
         * [capacity] rows are retained.
         *
         * @param records compact feedback rows loaded by a host.
         */
        fun replaceFeedbackStats(records: List<TerminalCompletionFeedbackStats>) {
            synchronized(lock) {
                feedbackStats.replaceAll(records)
            }
        }

        /**
         * Returns a stable snapshot for host persistence.
         *
         * @return retained rows sorted by ranking relevance.
         */
        fun snapshot(): List<TerminalCommandCompletionStats> =
            synchronized(lock) {
                commandStats.snapshot()
            }

        /**
         * Returns a stable privacy-preserving command-shape snapshot.
         *
         * @return retained shape rows sorted by ranking relevance.
         */
        fun shapeSnapshot(): List<TerminalCommandShapeStats> =
            synchronized(lock) {
                shapeStats.snapshot()
            }

        /**
         * Returns a stable source-specific feedback snapshot.
         *
         * @return retained feedback rows sorted by ranking relevance.
         */
        fun feedbackSnapshot(): List<TerminalCompletionFeedbackStats> =
            synchronized(lock) {
                feedbackStats.snapshot()
            }

        /**
         * Returns exact command and structural shape stats in one snapshot.
         *
         * @return immutable stats snapshot for host persistence.
         */
        fun snapshotAll(): TerminalCommandCompletionStatsSnapshot =
            synchronized(lock) {
                TerminalCommandCompletionStatsSnapshot(
                    commandStats = commandStats.snapshot(),
                    shapeStats = shapeStats.snapshot(),
                    feedbackStats = feedbackStats.snapshot(),
                )
            }

        /**
         * Records a completed command execution.
         *
         * Blank or multi-line commands are ignored because they are poor
         * single-line completion candidates.
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
            synchronized(lock) {
                commandStats.recordCommandResult(
                    commandLine = commandLine,
                    successful = successful,
                    profileId = profileId,
                    workingDirectoryUri = workingDirectoryUri,
                    usedAtEpochMillis = usedAtEpochMillis,
                )
                shapeStats.recordCommandResult(
                    commandLine = commandLine,
                    successful = successful,
                    profileId = profileId,
                    workingDirectoryUri = workingDirectoryUri,
                    usedAtEpochMillis = usedAtEpochMillis,
                )
            }
        }

        /**
         * Records explicit user feedback for a suggested command.
         *
         * @param commandLine command text represented by the suggestion.
         * @param feedback accepted or dismissed feedback kind.
         * @param profileId optional host profile id.
         * @param workingDirectoryUri optional working-directory URI.
         * @param feedbackAtEpochMillis host timestamp for the feedback event.
         * @param context optional source-specific context for the displayed candidate.
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
            synchronized(lock) {
                commandStats.recordSuggestionFeedback(
                    commandLine = commandLine,
                    feedback = feedback,
                    profileId = profileId,
                    workingDirectoryUri = workingDirectoryUri,
                    feedbackAtEpochMillis = feedbackAtEpochMillis,
                )
                shapeStats.recordSuggestionFeedback(
                    commandLine = commandLine,
                    feedback = feedback,
                    profileId = profileId,
                    workingDirectoryUri = workingDirectoryUri,
                    feedbackAtEpochMillis = feedbackAtEpochMillis,
                )
                if (isRecordableTerminalCompletionCommand(commandLine) && context != null) {
                    feedbackStats.recordSuggestionFeedback(
                        context = context,
                        feedback = feedback,
                        profileId = profileId,
                        workingDirectoryUri = workingDirectoryUri,
                        feedbackAtEpochMillis = feedbackAtEpochMillis,
                    )
                }
            }
        }

        override fun complete(request: TerminalCompletionRequest): List<TerminalCompletionCandidate> {
            val prefix = request.commandLine.substring(0, request.cursorOffset).trimStart()
            val normalizedPrefix = TerminalCommandCompletionStats.normalizeCommandLine(prefix)
            val snapshot = snapshot()
            if (snapshot.isEmpty()) return emptyList()

            val candidates = ArrayList<TerminalCompletionCandidate>(minOf(snapshot.size, request.maxCandidates))
            for (entry in snapshot) {
                if (!entry.hasPositiveSuggestionSignal()) continue
                if (!entry.normalizedCommandLine.startsWith(normalizedPrefix)) continue
                if (entry.normalizedCommandLine == normalizedPrefix) continue
                candidates += entry.toCandidate(request)
            }
            return candidates
                .sortedWith(TERMINAL_COMPLETION_CANDIDATE_ORDER)
                .take(request.maxCandidates)
        }

        private fun TerminalCommandCompletionStats.toCandidate(request: TerminalCompletionRequest): TerminalCompletionCandidate =
            TerminalCompletionCandidate(
                replacementText = commandLine,
                replacementStartOffset = 0,
                replacementEndOffset = request.commandLine.length,
                displayText = commandLine,
                source = SOURCE_STATS,
                kind = TerminalCompletionCandidateKind.HISTORY,
                score = score(request),
            )

        private fun TerminalCommandCompletionStats.hasPositiveSuggestionSignal(): Boolean = successCount > 0 || acceptedCount > 0

        private fun TerminalCommandCompletionStats.score(request: TerminalCompletionRequest): Int {
            val counterScore =
                STATS_BASE_SCORE.toLong() +
                    TerminalCompletionScoreAdjustment.counterContribution(SCORE_POLICY, useCount, USE_COUNT_SCORE) +
                    TerminalCompletionScoreAdjustment.counterContribution(SCORE_POLICY, successCount, SUCCESS_COUNT_SCORE) +
                    TerminalCompletionScoreAdjustment.counterContribution(SCORE_POLICY, failureCount, -FAILURE_COUNT_PENALTY) +
                    TerminalCompletionScoreAdjustment.counterContribution(SCORE_POLICY, acceptedCount, ACCEPTED_COUNT_SCORE) +
                    TerminalCompletionScoreAdjustment.counterContribution(SCORE_POLICY, dismissedCount, -DISMISSED_COUNT_PENALTY) +
                    minOf(lastUsedEpochMillis / RECENCY_SCORE_BUCKET_MILLIS, MAX_RECENCY_SCORE)
            return TerminalCompletionScoreAdjustment.score(
                policy = SCORE_POLICY,
                request = request,
                profileId = profileId,
                workingDirectoryUri = workingDirectoryUri,
                counterScore = counterScore,
            )
        }

        private companion object {
            private const val DEFAULT_CAPACITY = 2048
            private const val SOURCE_STATS = "stats"
            private const val STATS_BASE_SCORE = 620
            private const val USE_COUNT_SCORE = 18
            private const val SUCCESS_COUNT_SCORE = 10
            private const val FAILURE_COUNT_PENALTY = 16
            private const val ACCEPTED_COUNT_SCORE = 24
            private const val DISMISSED_COUNT_PENALTY = 30
            private const val MAX_COUNTER_SCORE_UNITS = 50
            private const val PROFILE_MATCH_SCORE = 50
            private const val WORKING_DIRECTORY_MATCH_SCORE = 80
            private const val RECENCY_SCORE_BUCKET_MILLIS = 60_000L
            private const val MAX_RECENCY_SCORE = 200L
            private val SCORE_POLICY =
                TerminalCompletionScoreAdjustment.Policy(
                    maxCounterScoreUnits = MAX_COUNTER_SCORE_UNITS,
                    minScoreAdjustment = Int.MIN_VALUE,
                    maxScoreAdjustment = Int.MAX_VALUE,
                    profileMatchBoost = PROFILE_MATCH_SCORE,
                    workingDirectoryMatchBoost = WORKING_DIRECTORY_MATCH_SCORE,
                )
        }
    }
