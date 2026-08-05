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
import io.github.ketraterm.completion.commandline.TerminalCommandLineContext
import io.github.ketraterm.completion.commandline.commandEndOffset
import io.github.ketraterm.completion.commandline.commandPrefix
import io.github.ketraterm.completion.commandline.commandStartOffset
import io.github.ketraterm.completion.internal.canonicalizeWorkingDirectoryUri
import io.github.ketraterm.completion.internal.isRelativeCdCommand
import io.github.ketraterm.completion.internal.normalizeTerminalCommandLine
import io.github.ketraterm.completion.internal.saturatedCompletionCounterIncrement

/** Bounded full-command MRU index. Its owner serializes all access. */
internal class SessionCommandHistory(
    private val capacity: Int,
) {
    private val entries = ArrayList<Entry>(capacity)

    fun record(
        commandLine: String,
        profileId: String?,
        workingDirectoryUri: String?,
        sequence: Long,
    ) {
        val normalized = normalizeTerminalCommandLine(commandLine)
        val index = entries.indexOfFirst { it.normalizedCommandLine == normalized }
        if (index >= 0) {
            val entry = entries[index]
            entries[index] =
                entry.copy(
                    commandLine = commandLine,
                    profileId = profileId,
                    workingDirectoryUri = workingDirectoryUri,
                    useCount = saturatedCompletionCounterIncrement(entry.useCount),
                    lastUsedSequence = sequence,
                )
            return
        }
        if (entries.size == capacity) entries.removeOldest()
        entries +=
            Entry(
                commandLine = commandLine,
                normalizedCommandLine = normalized,
                profileId = profileId,
                workingDirectoryUri = workingDirectoryUri,
                useCount = 1,
                lastUsedSequence = sequence,
            )
    }

    fun appendCandidates(
        request: TerminalCompletionRequest,
        context: TerminalCommandLineContext,
        destination: MutableList<TerminalCompletionCandidate>,
    ) {
        val normalizedPrefix = normalizeTerminalCommandLine(context.commandPrefix(request.commandLine))
        for (entry in entries) {
            if (!entry.normalizedCommandLine.startsWith(normalizedPrefix) || entry.normalizedCommandLine == normalizedPrefix) continue
            if (!entry.isValidFor(request)) continue
            destination += entry.toCandidate(request, context.commandStartOffset, context.commandEndOffset)
        }
    }

    fun clear() = entries.clear()

    private fun Entry.isValidFor(request: TerminalCompletionRequest): Boolean {
        if (!isRelativeCdCommand(commandLine)) return true
        val entryDirectory = workingDirectoryUri ?: return true
        val requestDirectory = request.workingDirectoryUri ?: return true
        return canonicalizeWorkingDirectoryUri(entryDirectory) == canonicalizeWorkingDirectoryUri(requestDirectory)
    }

    private fun Entry.toCandidate(
        request: TerminalCompletionRequest,
        replacementStartOffset: Int,
        replacementEndOffset: Int,
    ): TerminalCompletionCandidate =
        TerminalCompletionCandidate(
            replacementText = commandLine,
            replacementStartOffset = replacementStartOffset,
            replacementEndOffset = replacementEndOffset,
            displayText = commandLine,
            source = SOURCE_ID,
            kind = TerminalCompletionCandidateKind.HISTORY,
            score = SessionCompletionRelevance.score(BASE_SCORE, useCount, lastUsedSequence, profileId, workingDirectoryUri, request),
        )

    private fun MutableList<Entry>.removeOldest() {
        var oldestIndex = 0
        for (index in 1 until size) {
            if (this[index].lastUsedSequence < this[oldestIndex].lastUsedSequence) oldestIndex = index
        }
        removeAt(oldestIndex)
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
        private const val SOURCE_ID = "mru"
        private const val BASE_SCORE = 700
    }
}
