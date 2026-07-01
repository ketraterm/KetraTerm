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
 * Bounded in-memory MRU source for successful commands observed in one live
 * terminal session.
 *
 * This source never reads persistent history and never performs I/O. Hosts feed
 * it from trusted command lifecycle events, typically successful OSC 133 command
 * records. Matching uses the current command-line prefix and candidates replace
 * the full visible command line so repeated commands behave like professional
 * shell history completion rather than token completion.
 *
 * All public methods are thread-safe.
 *
 * @param capacity maximum number of distinct normalized commands retained.
 */
class TerminalSessionMruCompletionSource
    @JvmOverloads
    constructor(
        private val capacity: Int = DEFAULT_CAPACITY,
    ) : TerminalCompletionSource {
        init {
            require(capacity > 0) { "capacity must be > 0, was $capacity" }
        }

        private val lock = Any()
        private val entries = ArrayList<Entry>(capacity)
        private var nextSequence = 1L

        /**
         * Records a successful command for future MRU suggestions.
         *
         * Blank commands and commands containing line breaks are ignored because
         * they make poor single-line completion candidates.
         *
         * @param commandLine command line as executed by the shell.
         * @param profileId optional profile id associated with the command.
         * @param workingDirectoryUri optional working-directory URI at command start.
         */
        @JvmOverloads
        fun recordSuccessfulCommand(
            commandLine: String,
            profileId: String? = null,
            workingDirectoryUri: String? = null,
        ) {
            val command = commandLine.trim()
            if (!isRecordableTerminalCompletionCommand(command)) return

            val normalized = TerminalCommandCompletionStats.normalizeCommandLine(command)
            synchronized(lock) {
                val sequence = nextSequenceLocked()
                val index = indexOfNormalizedLocked(normalized)
                if (index >= 0) {
                    val entry = entries[index]
                    entries[index] =
                        entry.copy(
                            commandLine = command,
                            profileId = profileId,
                            workingDirectoryUri = workingDirectoryUri,
                            useCount = saturatedCompletionCounterIncrement(entry.useCount),
                            lastUsedSequence = sequence,
                        )
                } else {
                    if (entries.size == capacity) removeOldestLocked()
                    entries +=
                        Entry(
                            commandLine = command,
                            normalizedCommandLine = normalized,
                            profileId = profileId,
                            workingDirectoryUri = workingDirectoryUri,
                            useCount = 1,
                            lastUsedSequence = sequence,
                        )
                }
            }
        }

        /**
         * Removes all retained session MRU commands.
         */
        fun clear() {
            synchronized(lock) {
                entries.clear()
            }
        }

        override fun complete(request: TerminalCompletionRequest): List<TerminalCompletionCandidate> {
            val prefix = request.commandLine.substring(0, request.cursorOffset).trimStart()
            val normalizedPrefix = TerminalCommandCompletionStats.normalizeCommandLine(prefix)
            val snapshot =
                synchronized(lock) {
                    entries.toList()
                }
            if (snapshot.isEmpty()) return emptyList()

            val candidates = ArrayList<TerminalCompletionCandidate>(minOf(snapshot.size, request.maxCandidates))
            for (entry in snapshot) {
                if (!entry.normalizedCommandLine.startsWith(normalizedPrefix)) continue
                if (entry.normalizedCommandLine == normalizedPrefix) continue
                candidates += entry.toCandidate(request)
            }
            return candidates
                .sortedWith(TERMINAL_COMPLETION_CANDIDATE_ORDER)
                .take(request.maxCandidates)
        }

        private fun Entry.toCandidate(request: TerminalCompletionRequest): TerminalCompletionCandidate =
            TerminalCompletionCandidate(
                replacementText = commandLine,
                replacementStartOffset = 0,
                replacementEndOffset = request.commandLine.length,
                displayText = commandLine,
                source = SOURCE_MRU,
                kind = TerminalCompletionCandidateKind.HISTORY,
                score = score(request),
            )

        private fun Entry.score(request: TerminalCompletionRequest): Int {
            var score = MRU_BASE_SCORE
            score += minOf(useCount, MAX_USE_COUNT_SCORE_UNITS) * USE_COUNT_SCORE
            score += minOf(lastUsedSequence, MAX_RECENCY_SCORE).toInt()
            if (profileId != null && profileId == request.profileId) score += PROFILE_MATCH_SCORE
            if (workingDirectoryUri != null && workingDirectoryUri == request.workingDirectoryUri) {
                score += WORKING_DIRECTORY_MATCH_SCORE
            }
            return score
        }

        private fun indexOfNormalizedLocked(normalizedCommandLine: String): Int {
            var index = 0
            while (index < entries.size) {
                if (entries[index].normalizedCommandLine == normalizedCommandLine) return index
                index++
            }
            return -1
        }

        private fun removeOldestLocked() {
            var oldestIndex = 0
            var oldestSequence = entries[0].lastUsedSequence
            var index = 1
            while (index < entries.size) {
                val sequence = entries[index].lastUsedSequence
                if (sequence < oldestSequence) {
                    oldestSequence = sequence
                    oldestIndex = index
                }
                index++
            }
            entries.removeAt(oldestIndex)
        }

        private fun nextSequenceLocked(): Long {
            val sequence = nextSequence
            nextSequence = if (nextSequence == Long.MAX_VALUE) 1L else nextSequence + 1L
            return sequence
        }

        private data class Entry(
            val commandLine: String,
            val normalizedCommandLine: String,
            val profileId: String?,
            val workingDirectoryUri: String?,
            val useCount: Int,
            val lastUsedSequence: Long,
        )

        private companion object {
            private const val DEFAULT_CAPACITY = 128
            private const val SOURCE_MRU = "mru"
            private const val MRU_BASE_SCORE = 700
            private const val USE_COUNT_SCORE = 30
            private const val MAX_USE_COUNT_SCORE_UNITS = 20
            private const val MAX_RECENCY_SCORE = 100L
            private const val PROFILE_MATCH_SCORE = 60
            private const val WORKING_DIRECTORY_MATCH_SCORE = 90
        }
    }
