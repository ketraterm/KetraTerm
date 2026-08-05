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
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.api.TerminalSessionMruCompletionSource
import io.github.ketraterm.completion.commandline.ContextAwareCompletionSource
import io.github.ketraterm.completion.commandline.TerminalCommandLineContext
import io.github.ketraterm.completion.commandline.TerminalCommandLineCursorRegion
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
import io.github.ketraterm.completion.internal.TERMINAL_COMPLETION_CANDIDATE_ORDER
import io.github.ketraterm.completion.internal.isRecordableTerminalCompletionCommand
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs

/**
 * Thread-safe session source coordinating bounded command history and observed-token indexes.
 *
 * The two indexes intentionally retain independent capacities. Full command history
 * owns whole-line replacements, while observed-token learning serves only unknown
 * executable families. This coordinator owns synchronization and publication order;
 * neither index performs I/O or exposes mutable state.
 */
internal class SessionMruCompletionSourceImpl(
    capacity: Int = DEFAULT_CAPACITY,
    commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
) : TerminalSessionMruCompletionSource,
    ContextAwareCompletionSource {
    private val lock = Any()
    private val commandHistory: SessionCommandHistory
    private val observedTokens: SessionObservedTokenIndex
    private var nextSequence = 1L

    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
        commandHistory = SessionCommandHistory(capacity)
        observedTokens = SessionObservedTokenIndex(capacity, commandSpecs.toList())
    }

    override fun recordSuccessfulCommand(
        commandLine: String,
        profileId: String?,
        workingDirectoryUri: String?,
    ) {
        val command = commandLine.trim()
        if (!isRecordableTerminalCompletionCommand(command)) return
        synchronized(lock) {
            commandHistory.record(command, profileId, workingDirectoryUri, nextSequenceLocked())
            observedTokens.record(command, profileId, workingDirectoryUri, ::nextSequenceLocked)
        }
    }

    override fun clear() {
        synchronized(lock) {
            commandHistory.clear()
            observedTokens.clear()
        }
    }

    override fun complete(request: TerminalCompletionRequest): List<TerminalCompletionCandidate> =
        complete(
            request,
            TerminalCommandLineTokenizer.parse(request.commandLine, request.cursorOffset, request.shellCapabilities.syntax),
        )

    override fun complete(
        request: TerminalCompletionRequest,
        commandLineContext: TerminalCommandLineContext,
    ): List<TerminalCompletionCandidate> {
        if (commandLineContext.cursorRegion == TerminalCommandLineCursorRegion.OPERATOR) return emptyList()
        if (commandLineContext.precededByOperator) return emptyList()
        val candidates =
            synchronized(lock) {
                buildList {
                    commandHistory.appendCandidates(request, commandLineContext, this)
                    observedTokens.appendCandidates(request, commandLineContext, this)
                }
            }
        return candidates
            .sortedWith(TERMINAL_COMPLETION_CANDIDATE_ORDER)
            .take(request.maxCandidates)
    }

    /** Returns a monotonic session-local sequence while preserving bounded score arithmetic after overflow. */
    private fun nextSequenceLocked(): Long {
        val sequence = nextSequence
        nextSequence = if (nextSequence == Long.MAX_VALUE) 1L else nextSequence + 1L
        return sequence
    }

    private companion object {
        private const val DEFAULT_CAPACITY = 128
    }
}
