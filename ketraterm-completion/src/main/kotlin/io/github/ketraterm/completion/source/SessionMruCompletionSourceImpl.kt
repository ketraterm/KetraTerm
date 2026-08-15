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

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.commandline.TerminalCommandLineCursorRegion
import io.github.ketraterm.completion.internal.TERMINAL_COMPLETION_CANDIDATE_ORDER
import io.github.ketraterm.completion.internal.isRecordableTerminalCompletionCommand
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs

/**
 * Thread-safe session source coordinating bounded command history and observed-token indexes.
 *
 * The two session indexes intentionally retain independent capacities. Command
 * history and positive persisted rows are projected through one learned
 * candidate stream, while observed-token learning serves only unknown executable
 * families. This coordinator owns synchronization and publication order; no
 * index performs I/O or exposes mutable state.
 */
internal class SessionMruCompletionSourceImpl(
    capacity: Int = DEFAULT_CAPACITY,
    commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
    private val learningStore: TerminalCompletionLearningStore? = null,
    private val clockEpochMillis: () -> Long = System::currentTimeMillis,
) : TerminalSessionMruCompletionSource {
    private val lock = Any()
    private val commandHistory: SessionCommandHistory
    private val observedTokens: SessionObservedTokenIndex
    private val commandSpecs = commandSpecs.toList()
    private var nextSequence = 1L

    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
        commandHistory = SessionCommandHistory(capacity)
        observedTokens = SessionObservedTokenIndex(capacity, this.commandSpecs)
    }

    override val isFastInMemory: Boolean
        get() = true

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

    override suspend fun complete(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
        limit: Int,
    ): List<TerminalCompletionCandidate> {
        val commandLineContext = context.commandLineContext
        if (commandLineContext.cursorRegion == TerminalCommandLineCursorRegion.OPERATOR) return emptyList()
        if (commandLineContext.precededByOperator) return emptyList()
        val candidates = ArrayList<TerminalCompletionCandidate>()
        synchronized(lock) {
            commandHistory.appendCandidates(request, context, candidates)
            observedTokens.appendCandidates(request, commandLineContext, candidates)
        }
        learningStore?.indexesFor(request.shellCapabilities.syntax, commandSpecs)?.history?.let { learnedHistory ->
            appendPersistedHistoryCandidates(
                request = request,
                lineContext = commandLineContext,
                completionContext = context,
                index = learnedHistory,
                nowEpochMillis = clockEpochMillis().coerceAtLeast(0L),
                destination = candidates,
            )
        }
        return candidates
            .sortedWith(TERMINAL_COMPLETION_CANDIDATE_ORDER)
            .take(limit)
    }

    /** Returns a monotonic session-local sequence while preserving bounded score arithmetic after overflow. */
    private fun nextSequenceLocked(): Long {
        val sequence = nextSequence
        if (nextSequence < Long.MAX_VALUE) nextSequence++
        return sequence
    }

    private companion object {
        private const val DEFAULT_CAPACITY = 128
    }
}
