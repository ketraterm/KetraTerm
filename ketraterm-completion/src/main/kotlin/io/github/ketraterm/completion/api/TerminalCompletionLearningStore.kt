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

import io.github.ketraterm.completion.model.*

/**
 * Mutable, bounded store for aggregated completion-learning statistics.
 *
 * This is the public host-facing learning contract. Hosts may load and persist
 * snapshots, record command lifecycle outcomes, and feed explicit popup
 * feedback. The store does not emit candidates: hosts expose [snapshotAll] to
 * the global ranker and learned-history source so one evidence set cannot appear
 * as a second provider vote. Implementations must stay dependency-free,
 * bounded, thread-safe, and must not scan raw shell history.
 */
interface TerminalCompletionLearningStore {
    /**
     * Replaces all retained statistics with [snapshot].
     *
     * Implementations compact duplicate keys independently for exact command,
     * command-shape, and source-specific feedback rows, then retain only their
     * bounded capacity. Hosts should pass already privacy-filtered snapshots
     * when loading from disk.
     *
     * @param snapshot compact completion stats snapshot loaded by a host.
     */
    fun replaceSnapshot(snapshot: TerminalCommandCompletionStatsSnapshot)

    /**
     * Returns exact command stats sorted by ranking relevance.
     *
     * @return stable exact command snapshot for host persistence.
     */
    fun snapshot(): List<TerminalCommandCompletionStats>

    /**
     * Returns command-shape stats sorted by ranking relevance.
     *
     * @return stable command-shape snapshot for host persistence.
     */
    fun shapeSnapshot(): List<TerminalCommandShapeStats>

    /**
     * Returns source-specific feedback stats sorted by ranking relevance.
     *
     * @return stable feedback snapshot for host persistence.
     */
    fun feedbackSnapshot(): List<TerminalCompletionFeedbackStats>

    /**
     * Returns every retained stats family in one immutable snapshot.
     *
     * @return exact command, command-shape, and feedback stats snapshot.
     */
    fun snapshotAll(): TerminalCommandCompletionStatsSnapshot

    /**
     * Records a completed command execution.
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
    )

    /**
     * Records explicit user feedback for a displayed suggestion.
     *
     * @param commandLine command text represented after applying the suggestion.
     * @param feedback accepted or dismissed feedback kind.
     * @param profileId optional host profile id.
     * @param workingDirectoryUri optional working-directory URI.
     * @param feedbackAtEpochMillis host timestamp for the feedback event.
     * @param context optional source-specific candidate context.
     */
    fun recordSuggestionFeedback(
        commandLine: String,
        feedback: TerminalCompletionFeedbackKind,
        profileId: String?,
        workingDirectoryUri: String?,
        feedbackAtEpochMillis: Long,
        context: TerminalCompletionFeedbackContext? = null,
    )
}
