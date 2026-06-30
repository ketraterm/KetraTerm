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

private fun String.hasLineBreak(): Boolean = indexOf('\n') >= 0 || indexOf('\r') >= 0

/**
 * User feedback category recorded for a command completion candidate.
 */
enum class TerminalCompletionFeedbackKind {
    /**
     * The user accepted a suggestion and asked the host to apply it.
     */
    ACCEPTED,

    /**
     * The user explicitly dismissed a suggestion without applying it.
     */
    DISMISSED,
}

/**
 * Aggregated command statistics used by indexed history completion.
 *
 * The model stores compact counters instead of raw repeated history rows.
 * Hosts own persistence and import/export these values into
 * [TerminalCommandStatsCompletionSource]; this shared model performs no I/O.
 *
 * @property commandLine canonical command text shown to the user.
 * @property normalizedCommandLine normalized command key used for prefix
 * matching and deduplication.
 * @property profileId optional host profile id associated with the stats row.
 * @property workingDirectoryUri optional working directory URI associated with
 * the stats row.
 * @property useCount number of observed command executions.
 * @property successCount number of executions that completed successfully.
 * @property failureCount number of executions that completed unsuccessfully.
 * @property acceptedCount number of times this command was accepted from a
 * suggestion popup.
 * @property dismissedCount number of times this command was explicitly
 * dismissed from a suggestion popup.
 * @property lastUsedEpochMillis host wall-clock timestamp for the newest
 * execution or feedback event represented by this row.
 */
data class TerminalCommandCompletionStats
    @JvmOverloads
    constructor(
        val commandLine: String,
        val normalizedCommandLine: String = normalizeCommandLine(commandLine),
        val profileId: String? = null,
        val workingDirectoryUri: String? = null,
        val useCount: Int = 0,
        val successCount: Int = 0,
        val failureCount: Int = 0,
        val acceptedCount: Int = 0,
        val dismissedCount: Int = 0,
        val lastUsedEpochMillis: Long = 0L,
    ) {
        init {
            require(commandLine.isNotBlank()) { "commandLine must not be blank" }
            require(!commandLine.hasLineBreak()) { "commandLine must not contain line breaks" }
            require(normalizedCommandLine.isNotBlank()) { "normalizedCommandLine must not be blank" }
            require(useCount >= 0) { "useCount must be >= 0, was $useCount" }
            require(successCount >= 0) { "successCount must be >= 0, was $successCount" }
            require(failureCount >= 0) { "failureCount must be >= 0, was $failureCount" }
            require(acceptedCount >= 0) { "acceptedCount must be >= 0, was $acceptedCount" }
            require(dismissedCount >= 0) { "dismissedCount must be >= 0, was $dismissedCount" }
            require(lastUsedEpochMillis >= 0L) { "lastUsedEpochMillis must be >= 0, was $lastUsedEpochMillis" }
        }

        companion object {
            /**
             * Normalizes command text for case-insensitive prefix matching.
             *
             * @param commandLine command text to normalize.
             * @return trimmed lowercase key.
             */
            @JvmStatic
            fun normalizeCommandLine(commandLine: String): String = commandLine.trim().lowercase()
        }
    }

/**
 * Bounded in-memory completion source backed by aggregated command statistics.
 *
 * Hosts feed this source from compact persisted metadata and live command or
 * suggestion feedback. The source never scans raw history, performs I/O, spawns
 * shells, or depends on UI frameworks. All public methods are thread-safe.
 *
 * @param capacity maximum distinct command/profile/directory rows retained.
 */
class TerminalCommandStatsCompletionSource
    @JvmOverloads
    constructor(
        private val capacity: Int = DEFAULT_CAPACITY,
    ) : TerminalCompletionSource {
        init {
            require(capacity > 0) { "capacity must be > 0, was $capacity" }
        }

        private val lock = Any()
        private val entries = ArrayList<TerminalCommandCompletionStats>(capacity)
        private val shapeEntries = ArrayList<TerminalCommandShapeStats>(capacity)

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
            val compacted = ArrayList<TerminalCommandCompletionStats>(minOf(records.size, capacity))
            for (record in records) {
                val index = compacted.indexOfKey(record)
                if (index >= 0) {
                    if (record.lastUsedEpochMillis >= compacted[index].lastUsedEpochMillis) {
                        compacted[index] = record
                    }
                } else {
                    compacted += record
                }
            }
            compacted.sortWith(RECORD_ORDER)
            synchronized(lock) {
                entries.clear()
                entries.addAll(compacted.take(capacity))
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
            val compacted = ArrayList<TerminalCommandShapeStats>(minOf(records.size, capacity))
            for (record in records) {
                val index = compacted.indexOfShapeKey(record)
                if (index >= 0) {
                    if (record.lastUsedEpochMillis >= compacted[index].lastUsedEpochMillis) {
                        compacted[index] = record
                    }
                } else {
                    compacted += record
                }
            }
            compacted.sortWith(SHAPE_RECORD_ORDER)
            synchronized(lock) {
                shapeEntries.clear()
                shapeEntries.addAll(compacted.take(capacity))
            }
        }

        /**
         * Returns a stable snapshot for host persistence.
         *
         * @return retained rows sorted by ranking relevance.
         */
        fun snapshot(): List<TerminalCommandCompletionStats> =
            synchronized(lock) {
                entries.toList()
            }

        /**
         * Returns a stable privacy-preserving command-shape snapshot.
         *
         * @return retained shape rows sorted by ranking relevance.
         */
        fun shapeSnapshot(): List<TerminalCommandShapeStats> =
            synchronized(lock) {
                shapeEntries.toList()
            }

        /**
         * Returns exact command and structural shape stats in one snapshot.
         *
         * @return immutable stats snapshot for host persistence.
         */
        fun snapshotAll(): TerminalCommandCompletionStatsSnapshot =
            synchronized(lock) {
                TerminalCommandCompletionStatsSnapshot(
                    commandStats = entries.toList(),
                    shapeStats = shapeEntries.toList(),
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
            if (!isRecordableCommand(commandLine) || usedAtEpochMillis < 0L) return
            mutate(commandLine, profileId, workingDirectoryUri) { previous, canonical ->
                previous.copy(
                    commandLine = canonical,
                    useCount = saturatedIncrement(previous.useCount),
                    successCount = if (successful) saturatedIncrement(previous.successCount) else previous.successCount,
                    failureCount = if (successful) previous.failureCount else saturatedIncrement(previous.failureCount),
                    lastUsedEpochMillis = maxOf(previous.lastUsedEpochMillis, usedAtEpochMillis),
                )
            }
            mutateShape(commandLine, profileId, workingDirectoryUri) { previous ->
                previous.copy(
                    useCount = saturatedIncrement(previous.useCount),
                    successCount = if (successful) saturatedIncrement(previous.successCount) else previous.successCount,
                    failureCount = if (successful) previous.failureCount else saturatedIncrement(previous.failureCount),
                    lastUsedEpochMillis = maxOf(previous.lastUsedEpochMillis, usedAtEpochMillis),
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
         */
        fun recordSuggestionFeedback(
            commandLine: String,
            feedback: TerminalCompletionFeedbackKind,
            profileId: String?,
            workingDirectoryUri: String?,
            feedbackAtEpochMillis: Long,
        ) {
            if (!isRecordableCommand(commandLine) || feedbackAtEpochMillis < 0L) return
            mutate(commandLine, profileId, workingDirectoryUri) { previous, canonical ->
                previous.copy(
                    commandLine = canonical,
                    acceptedCount =
                        if (feedback == TerminalCompletionFeedbackKind.ACCEPTED) {
                            saturatedIncrement(previous.acceptedCount)
                        } else {
                            previous.acceptedCount
                        },
                    dismissedCount =
                        if (feedback == TerminalCompletionFeedbackKind.DISMISSED) {
                            saturatedIncrement(previous.dismissedCount)
                        } else {
                            previous.dismissedCount
                        },
                    lastUsedEpochMillis = maxOf(previous.lastUsedEpochMillis, feedbackAtEpochMillis),
                )
            }
            mutateShape(commandLine, profileId, workingDirectoryUri) { previous ->
                previous.copy(
                    acceptedCount =
                        if (feedback == TerminalCompletionFeedbackKind.ACCEPTED) {
                            saturatedIncrement(previous.acceptedCount)
                        } else {
                            previous.acceptedCount
                        },
                    dismissedCount =
                        if (feedback == TerminalCompletionFeedbackKind.DISMISSED) {
                            saturatedIncrement(previous.dismissedCount)
                        } else {
                            previous.dismissedCount
                        },
                    lastUsedEpochMillis = maxOf(previous.lastUsedEpochMillis, feedbackAtEpochMillis),
                )
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
                .sortedWith(CANDIDATE_ORDER)
                .take(request.maxCandidates)
        }

        private inline fun mutate(
            commandLine: String,
            profileId: String?,
            workingDirectoryUri: String?,
            update: (TerminalCommandCompletionStats, String) -> TerminalCommandCompletionStats,
        ) {
            val canonical = commandLine.trim()
            val normalized = TerminalCommandCompletionStats.normalizeCommandLine(canonical)
            synchronized(lock) {
                val existingIndex = entries.indexOfKey(normalized, profileId, workingDirectoryUri)
                if (existingIndex >= 0) {
                    entries[existingIndex] = update(entries[existingIndex], canonical)
                } else {
                    if (entries.size == capacity) removeLeastRelevantLocked()
                    val initial =
                        TerminalCommandCompletionStats(
                            commandLine = canonical,
                            normalizedCommandLine = normalized,
                            profileId = profileId,
                            workingDirectoryUri = workingDirectoryUri,
                        )
                    entries += update(initial, canonical)
                }
                entries.sortWith(RECORD_ORDER)
            }
        }

        private inline fun mutateShape(
            commandLine: String,
            profileId: String?,
            workingDirectoryUri: String?,
            update: (TerminalCommandShapeStats) -> TerminalCommandShapeStats,
        ) {
            val shape = TerminalCommandLineShape.fromCommandLine(commandLine) ?: return
            synchronized(lock) {
                val existingIndex = shapeEntries.indexOfShapeKey(shape.normalizedShapeKey, profileId, workingDirectoryUri)
                if (existingIndex >= 0) {
                    shapeEntries[existingIndex] = update(shapeEntries[existingIndex])
                } else {
                    if (shapeEntries.size == capacity) removeLeastRelevantShapeLocked()
                    val initial =
                        TerminalCommandShapeStats(
                            shape = shape,
                            profileId = profileId,
                            workingDirectoryUri = workingDirectoryUri,
                        )
                    shapeEntries += update(initial)
                }
                shapeEntries.sortWith(SHAPE_RECORD_ORDER)
            }
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
            var score = STATS_BASE_SCORE
            score += minOf(useCount, MAX_COUNTER_SCORE_UNITS) * USE_COUNT_SCORE
            score += minOf(successCount, MAX_COUNTER_SCORE_UNITS) * SUCCESS_COUNT_SCORE
            score -= minOf(failureCount, MAX_COUNTER_SCORE_UNITS) * FAILURE_COUNT_PENALTY
            score += minOf(acceptedCount, MAX_COUNTER_SCORE_UNITS) * ACCEPTED_COUNT_SCORE
            score -= minOf(dismissedCount, MAX_COUNTER_SCORE_UNITS) * DISMISSED_COUNT_PENALTY
            score += minOf(lastUsedEpochMillis / RECENCY_SCORE_BUCKET_MILLIS, MAX_RECENCY_SCORE).toInt()
            if (profileId != null && profileId == request.profileId) score += PROFILE_MATCH_SCORE
            if (workingDirectoryUri != null && workingDirectoryUri == request.workingDirectoryUri) {
                score += WORKING_DIRECTORY_MATCH_SCORE
            }
            return score
        }

        private fun removeLeastRelevantLocked() {
            if (entries.isEmpty()) return
            var removeIndex = 0
            var index = 1
            while (index < entries.size) {
                if (RECORD_ORDER.compare(entries[index], entries[removeIndex]) > 0) removeIndex = index
                index++
            }
            entries.removeAt(removeIndex)
        }

        private fun removeLeastRelevantShapeLocked() {
            if (shapeEntries.isEmpty()) return
            var removeIndex = 0
            var index = 1
            while (index < shapeEntries.size) {
                if (SHAPE_RECORD_ORDER.compare(shapeEntries[index], shapeEntries[removeIndex]) > 0) removeIndex = index
                index++
            }
            shapeEntries.removeAt(removeIndex)
        }

        private fun ArrayList<TerminalCommandCompletionStats>.indexOfKey(record: TerminalCommandCompletionStats): Int =
            indexOfKey(record.normalizedCommandLine, record.profileId, record.workingDirectoryUri)

        private fun List<TerminalCommandCompletionStats>.indexOfKey(
            normalizedCommandLine: String,
            profileId: String?,
            workingDirectoryUri: String?,
        ): Int {
            var index = 0
            while (index < size) {
                val entry = this[index]
                if (entry.normalizedCommandLine == normalizedCommandLine &&
                    entry.profileId == profileId &&
                    entry.workingDirectoryUri == workingDirectoryUri
                ) {
                    return index
                }
                index++
            }
            return -1
        }

        private fun ArrayList<TerminalCommandShapeStats>.indexOfShapeKey(record: TerminalCommandShapeStats): Int =
            indexOfShapeKey(record.shape.normalizedShapeKey, record.profileId, record.workingDirectoryUri)

        private fun List<TerminalCommandShapeStats>.indexOfShapeKey(
            normalizedShapeKey: String,
            profileId: String?,
            workingDirectoryUri: String?,
        ): Int {
            var index = 0
            while (index < size) {
                val entry = this[index]
                if (entry.shape.normalizedShapeKey == normalizedShapeKey &&
                    entry.profileId == profileId &&
                    entry.workingDirectoryUri == workingDirectoryUri
                ) {
                    return index
                }
                index++
            }
            return -1
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

            private val RECORD_ORDER =
                compareByDescending<TerminalCommandCompletionStats> { it.lastUsedEpochMillis }
                    .thenByDescending { it.acceptedCount }
                    .thenByDescending { it.successCount }
                    .thenBy { it.dismissedCount }
                    .thenBy { it.commandLine }

            private val SHAPE_RECORD_ORDER =
                compareByDescending<TerminalCommandShapeStats> { it.lastUsedEpochMillis }
                    .thenByDescending { it.acceptedCount }
                    .thenByDescending { it.successCount }
                    .thenBy { it.dismissedCount }
                    .thenBy { it.shape.normalizedShapeKey }

            private val CANDIDATE_ORDER =
                compareByDescending<TerminalCompletionCandidate> { it.score }
                    .thenBy { it.displayText }

            private fun isRecordableCommand(commandLine: String): Boolean = commandLine.isNotBlank() && !commandLine.hasLineBreak()

            private fun saturatedIncrement(value: Int): Int = if (value == Int.MAX_VALUE) value else value + 1
        }
    }
