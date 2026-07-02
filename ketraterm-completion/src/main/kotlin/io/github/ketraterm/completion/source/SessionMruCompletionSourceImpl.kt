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
package io.github.ketraterm.completion.source

import io.github.ketraterm.completion.api.TerminalCompletionCandidate
import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.api.TerminalSessionMruCompletionSource
import io.github.ketraterm.completion.internal.TERMINAL_COMPLETION_CANDIDATE_ORDER
import io.github.ketraterm.completion.internal.canonicalizeWorkingDirectoryUri
import io.github.ketraterm.completion.internal.isRecordableTerminalCompletionCommand
import io.github.ketraterm.completion.internal.isRelativeCdCommand
import io.github.ketraterm.completion.internal.normalizeTerminalCommandLine
import io.github.ketraterm.completion.internal.saturatedCompletionCounterIncrement

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
internal class SessionMruCompletionSourceImpl(
    private val capacity: Int = DEFAULT_CAPACITY,
) : TerminalSessionMruCompletionSource {
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
    override fun recordSuccessfulCommand(
        commandLine: String,
        profileId: String?,
        workingDirectoryUri: String?,
    ) {
        val command = commandLine.trim()
        if (!isRecordableTerminalCompletionCommand(command)) return

        val normalized = normalizeTerminalCommandLine(command)
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
    override fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    override fun complete(request: TerminalCompletionRequest): List<TerminalCompletionCandidate> {
        val prefix = request.commandLine.substring(0, request.cursorOffset).trimStart()
        val normalizedPrefix = normalizeTerminalCommandLine(prefix)
        val candidates =
            synchronized(lock) {
                if (entries.isEmpty()) return@synchronized emptyList()
                val result = ArrayList<TerminalCompletionCandidate>()
                for (i in entries.indices) {
                    val entry = entries[i]
                    if (entry.normalizedCommandLine.startsWith(normalizedPrefix) && entry.normalizedCommandLine != normalizedPrefix) {
                        if (isRelativeCdCommand(entry.commandLine)) {
                            val entryUri = entry.workingDirectoryUri
                            val requestUri = request.workingDirectoryUri
                            if (entryUri != null && requestUri != null &&
                                canonicalizeWorkingDirectoryUri(entryUri) != canonicalizeWorkingDirectoryUri(requestUri)
                            ) {
                                continue
                            }
                        }
                        result += entry.toCandidate(request)
                    }
                }
                result
            }
        if (candidates.isEmpty()) return emptyList()
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
