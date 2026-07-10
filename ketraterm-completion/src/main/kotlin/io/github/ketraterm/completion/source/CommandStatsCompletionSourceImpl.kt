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

import io.github.ketraterm.completion.api.TerminalCommandStatsCompletionSource
import io.github.ketraterm.completion.api.TerminalCompletionCandidate
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.commandline.ContextAwareCompletionSource
import io.github.ketraterm.completion.commandline.TerminalCommandLineContext
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
import io.github.ketraterm.completion.internal.isRecordableTerminalCompletionCommand
import io.github.ketraterm.completion.model.*
import io.github.ketraterm.completion.ranking.ExactCommandStatsCandidateBuilder
import io.github.ketraterm.completion.stats.CommandCompletionStatsIndex
import io.github.ketraterm.completion.stats.CommandShapeStatsIndex
import io.github.ketraterm.completion.stats.CompletionFeedbackStatsIndex

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
internal class CommandStatsCompletionSourceImpl(
    capacity: Int = DEFAULT_CAPACITY,
    commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
) : TerminalCommandStatsCompletionSource,
    ContextAwareCompletionSource {
    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
    }

    private val lock = Any()
    private val commandStats = CommandCompletionStatsIndex(capacity)
    private val shapeStats = CommandShapeStatsIndex(capacity, commandSpecs)
    private val feedbackStats = CompletionFeedbackStatsIndex(capacity)

    /**
     * Replaces every stats family from one host-loaded snapshot.
     *
     * Each underlying index owns its duplicate compaction, malformed-row
     * filtering, relevance ordering, and capacity enforcement.
     *
     * @param snapshot compact stats snapshot loaded by a host.
     */
    override fun replaceSnapshot(snapshot: TerminalCommandCompletionStatsSnapshot) {
        synchronized(lock) {
            commandStats.replaceAll(snapshot.commandStats)
            shapeStats.replaceAll(snapshot.shapeStats)
            feedbackStats.replaceAll(snapshot.feedbackStats)
        }
    }

    /**
     * Returns a stable snapshot for host persistence.
     *
     * @return retained rows sorted by ranking relevance.
     */
    override fun snapshot(): List<TerminalCommandCompletionStats> =
        synchronized(lock) {
            commandStats.snapshot()
        }

    /**
     * Returns a stable privacy-preserving command-shape snapshot.
     *
     * @return retained shape rows sorted by ranking relevance.
     */
    override fun shapeSnapshot(): List<TerminalCommandShapeStats> =
        synchronized(lock) {
            shapeStats.snapshot()
        }

    /**
     * Returns a stable source-specific feedback snapshot.
     *
     * @return retained feedback rows sorted by ranking relevance.
     */
    override fun feedbackSnapshot(): List<TerminalCompletionFeedbackStats> =
        synchronized(lock) {
            feedbackStats.snapshot()
        }

    /**
     * Returns exact command and structural shape stats in one snapshot.
     *
     * @return immutable stats snapshot for host persistence.
     */
    override fun snapshotAll(): TerminalCommandCompletionStatsSnapshot =
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
    override fun recordCommandResult(
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
    override fun recordSuggestionFeedback(
        commandLine: String,
        feedback: TerminalCompletionFeedbackKind,
        profileId: String?,
        workingDirectoryUri: String?,
        feedbackAtEpochMillis: Long,
        context: TerminalCompletionFeedbackContext?,
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

    override fun complete(request: TerminalCompletionRequest): List<TerminalCompletionCandidate> =
        complete(
            request,
            TerminalCommandLineTokenizer.parse(request.commandLine, request.cursorOffset, request.shellCapabilities.syntax),
        )

    override fun complete(
        request: TerminalCompletionRequest,
        commandLineContext: TerminalCommandLineContext,
    ): List<TerminalCompletionCandidate> =
        synchronized(lock) {
            ExactCommandStatsCandidateBuilder.complete(request, commandStats.rawRows(), commandLineContext)
        }

    private companion object {
        private const val DEFAULT_CAPACITY = 2048
    }
}
