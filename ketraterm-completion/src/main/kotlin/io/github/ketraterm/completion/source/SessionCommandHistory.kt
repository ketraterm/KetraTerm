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
import io.github.ketraterm.completion.api.TerminalCompletionContext
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.commandline.commandPrefix
import io.github.ketraterm.completion.internal.canonicalizeWorkingDirectoryUri
import io.github.ketraterm.completion.internal.isRelativeCdCommand
import io.github.ketraterm.completion.internal.normalizeTerminalCommandLine
import io.github.ketraterm.completion.internal.saturatedCompletionCounterIncrement
import io.github.ketraterm.completion.source.SessionCommandHistory.Entry

/** Bounded full-command MRU index. Its owner serializes all access. */
internal class SessionCommandHistory(
    private val capacity: Int,
) {
    private val entries =
        object : LinkedHashMap<String, Entry>(capacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > capacity
        }

    fun record(
        commandLine: String,
        profileId: String?,
        workingDirectoryUri: String?,
        sequence: Long,
    ) {
        val normalized = normalizeTerminalCommandLine(commandLine)
        val existing = entries[normalized]
        if (existing != null) {
            entries[normalized] =
                existing.copy(
                    commandLine = commandLine,
                    profileId = profileId,
                    workingDirectoryUri = workingDirectoryUri,
                    useCount = saturatedCompletionCounterIncrement(existing.useCount),
                    lastUsedSequence = sequence,
                )
        } else {
            entries[normalized] =
                Entry(
                    commandLine = commandLine,
                    normalizedCommandLine = normalized,
                    profileId = profileId,
                    workingDirectoryUri = workingDirectoryUri,
                    useCount = 1,
                    lastUsedSequence = sequence,
                )
        }
    }

    fun appendCandidates(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
        destination: MutableList<TerminalCompletionCandidate>,
    ) {
        val lineContext = context.commandLineContext
        val normalizedPrefix = normalizeTerminalCommandLine(lineContext.commandPrefix(request.commandLine))
        for (entry in entries.values) {
            if (!entry.normalizedCommandLine.startsWith(normalizedPrefix) || entry.normalizedCommandLine == normalizedPrefix) continue
            if (!entry.isValidFor(request)) continue
            projectLearnedCommandCandidate(
                request = request,
                requestLine = lineContext,
                completionContext = context,
                learnedCommand = entry.commandLine,
                source = SOURCE_ID,
                score =
                    sessionCompletionScore(
                        BASE_SCORE,
                        entry.useCount,
                        entry.lastUsedSequence,
                        entry.profileId,
                        entry.workingDirectoryUri,
                        request,
                    ),
                detailPrefix = "recent",
            )?.let(destination::add)
        }
    }

    fun clear() = entries.clear()

    private fun Entry.isValidFor(request: TerminalCompletionRequest): Boolean {
        if (!isRelativeCdCommand(commandLine)) return true
        val entryDirectory = workingDirectoryUri ?: return true
        val requestDirectory = request.workingDirectoryUri ?: return true
        return canonicalizeWorkingDirectoryUri(entryDirectory) == canonicalizeWorkingDirectoryUri(requestDirectory)
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
        private const val BASE_SCORE = 20
    }
}
