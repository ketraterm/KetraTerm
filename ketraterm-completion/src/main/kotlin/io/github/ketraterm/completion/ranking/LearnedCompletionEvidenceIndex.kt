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
package io.github.ketraterm.completion.ranking

import io.github.ketraterm.completion.api.TerminalCompletionCandidate
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCommandLineShape
import io.github.ketraterm.completion.model.TerminalCompletionTokenPosition

/** Immutable learned-evidence lookup facade used by global ranking. */
internal class LearnedCompletionEvidenceIndex(
    private val exactRows: Map<LearnedCompletionOutcomeKey, List<TerminalCommandCompletionStats>>,
    private val shapeIndex: ShapeRankingSnapshotIndex,
    private val feedbackIndex: FeedbackRankingSnapshotIndex,
) {
    fun exactAdjustment(
        key: LearnedCompletionOutcomeKey,
        request: TerminalCompletionRequest,
        nowEpochMillis: Long,
    ): Int {
        val rows = LearnedEvidenceContextSelector.mostSpecific(exactRows[key].orEmpty(), request) { it.evidenceContext() }
        if (rows.isEmpty()) return 0
        return LearnedEvidenceScoring.exact(
            counts = LearnedEvidenceCounters.fromCommands(rows),
            contextBoost = LearnedEvidenceContextSelector.boost(rows.first().evidenceContext(), request, profile = 20, directory = 30),
            nowEpochMillis = nowEpochMillis,
        )
    }

    fun shapeAdjustment(
        candidateShape: TerminalCommandLineShape?,
        request: TerminalCompletionRequest,
    ): Int {
        if (candidateShape == null) return 0
        val matchingRows = shapeIndex.familyRows(candidateShape).filter { it.shape.supports(candidateShape) }
        val rows = LearnedEvidenceContextSelector.mostSpecific(matchingRows, request) { it.evidenceContext() }
        if (rows.isEmpty()) return 0
        return LearnedEvidenceScoring.shape(
            counts = LearnedEvidenceCounters.fromShapes(rows),
            contextBoost = LearnedEvidenceContextSelector.boost(rows.first().evidenceContext(), request, profile = 10, directory = 15),
        )
    }

    fun providerAdjustment(
        candidate: TerminalCompletionCandidate,
        request: TerminalCompletionRequest,
    ): Int {
        val rows =
            feedbackIndex.matchingRows(
                source = candidate.source,
                candidateKind = candidate.kind,
                tokenPosition = TerminalCompletionTokenPosition.fromCandidateKind(candidate.kind),
                profileId = request.profileId,
                workingDirectoryUri = request.workingDirectoryUri,
            )
        if (rows.isEmpty()) return 0
        return LearnedEvidenceScoring.provider(
            counts = LearnedEvidenceCounters.fromFeedback(rows),
            contextBoost = LearnedEvidenceContextSelector.boost(rows.first().evidenceContext(), request, profile = 10, directory = 15),
        )
    }

    /** Returns whether a recorded shape is at least as specific as the projected candidate shape. */
    private fun TerminalCommandLineShape.supports(candidate: TerminalCommandLineShape): Boolean =
        executable == candidate.executable &&
            subcommands.startsWith(candidate.subcommands) &&
            optionNames.containsAll(candidate.optionNames) &&
            positionalArgumentCount >= candidate.positionalArgumentCount &&
            optionValueCount >= candidate.optionValueCount

    private fun List<String>.startsWith(prefix: List<String>): Boolean {
        if (prefix.size > size) return false
        for (index in prefix.indices) if (this[index] != prefix[index]) return false
        return true
    }
}
