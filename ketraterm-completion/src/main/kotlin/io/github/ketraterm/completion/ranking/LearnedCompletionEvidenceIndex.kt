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
import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.api.TerminalShellSyntax
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
import io.github.ketraterm.completion.internal.canonicalizeWorkingDirectoryUri
import io.github.ketraterm.completion.model.*

/** Immutable direct-lookup view of every learned ranking signal in one snapshot. */
internal class LearnedCompletionEvidenceIndex private constructor(
    private val exactRows: Map<LearnedCompletionOutcomeKey, List<TerminalCommandCompletionStats>>,
    private val shapeRows: Map<String, List<TerminalCommandShapeStats>>,
    private val feedbackRows: Map<FeedbackKey, List<TerminalCompletionFeedbackStats>>,
) {
    fun exactAdjustment(
        key: LearnedCompletionOutcomeKey,
        request: TerminalCompletionRequest,
        nowEpochMillis: Long,
    ): Int {
        val rows = mostSpecific(exactRows[key].orEmpty(), request, TerminalCommandCompletionStats::evidenceContext)
        if (rows.isEmpty()) return 0
        return LearnedEvidenceScoring.exact(
            counts = LearnedEvidenceCounts.fromCommands(rows),
            contextBoost = rows.first().evidenceContext().boost(request, profile = 20, directory = 30),
            nowEpochMillis = nowEpochMillis,
        )
    }

    fun shapeAdjustment(
        candidateShape: TerminalCommandLineShape?,
        request: TerminalCompletionRequest,
    ): Int {
        if (candidateShape == null) return 0
        val matchingRows = shapeRows[candidateShape.executable].orEmpty().filter { it.shape.supports(candidateShape) }
        val rows = mostSpecific(matchingRows, request, TerminalCommandShapeStats::evidenceContext)
        if (rows.isEmpty()) return 0
        return LearnedEvidenceScoring.shape(
            counts = LearnedEvidenceCounts.fromShapes(rows),
            contextBoost = rows.first().evidenceContext().boost(request, profile = 10, directory = 15),
        )
    }

    fun providerAdjustment(
        candidate: TerminalCompletionCandidate,
        request: TerminalCompletionRequest,
    ): Int {
        val rows = feedbackRows(candidate, request)
        if (rows.isEmpty()) return 0
        return LearnedEvidenceScoring.provider(
            counts = LearnedEvidenceCounts.fromFeedback(rows),
            contextBoost = rows.first().evidenceContext().boost(request, profile = 10, directory = 15),
        )
    }

    private fun feedbackRows(
        candidate: TerminalCompletionCandidate,
        request: TerminalCompletionRequest,
    ): List<TerminalCompletionFeedbackStats> {
        val position = TerminalCompletionTokenPosition.fromCandidateKind(candidate.kind)
        val directory = request.workingDirectoryUri.canonicalDirectory()
        val contexts =
            arrayOf(
                FeedbackContext(request.profileId, directory),
                FeedbackContext(null, directory),
                FeedbackContext(request.profileId, null),
                FeedbackContext(null, null),
            )
        for (context in contexts) {
            feedbackRows[FeedbackKey(candidate.source, candidate.kind, position, context)]?.let { return it }
        }
        return emptyList()
    }

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

    companion object {
        fun build(
            snapshot: TerminalCommandCompletionStatsSnapshot,
            shellSyntax: TerminalShellSyntax,
            outcomeResolver: TerminalCompletionOutcomeKeyResolver,
        ): LearnedCompletionEvidenceIndex {
            val exactRows = HashMap<LearnedCompletionOutcomeKey, MutableList<TerminalCommandCompletionStats>>()
            for (row in snapshot.commandStats) {
                val tokens = TerminalCommandLineTokenizer.parse(row.commandLine, row.commandLine.length, shellSyntax).tokens
                if (tokens.isEmpty()) continue
                outcomeResolver.learnedKey(row.commandLine, shellSyntax, NO_PATH_TOKEN, pathAware = false)?.let { key ->
                    exactRows.getOrPut(key, ::ArrayList).add(row)
                }
                for (tokenIndex in tokens.indices) {
                    outcomeResolver.learnedKey(row.commandLine, shellSyntax, tokenIndex, pathAware = true)?.let { key ->
                        exactRows.getOrPut(key, ::ArrayList).add(row)
                    }
                }
            }

            val shapeRows = HashMap<String, MutableList<TerminalCommandShapeStats>>()
            for (row in snapshot.shapeStats) {
                shapeRows.getOrPut(row.shape.executable, ::ArrayList).add(row)
            }

            val feedbackRows = HashMap<FeedbackKey, MutableList<TerminalCompletionFeedbackStats>>()
            for (row in snapshot.feedbackStats) {
                val context = FeedbackContext(row.profileId, row.workingDirectoryUri.canonicalDirectory())
                val key = FeedbackKey(row.source, row.candidateKind, row.tokenPosition, context)
                feedbackRows.getOrPut(key, ::ArrayList).add(row)
            }

            return LearnedCompletionEvidenceIndex(
                exactRows = exactRows.freeze(),
                shapeRows = shapeRows.freeze(),
                feedbackRows = feedbackRows.freeze(),
            )
        }

        private const val NO_PATH_TOKEN = -1
    }
}

/** Thread-safe identity cache for immutable learned snapshots and shell syntax. */
internal class LearnedCompletionEvidenceIndexCache(
    private val outcomeResolver: TerminalCompletionOutcomeKeyResolver,
) {
    private val lock = Any()

    @Volatile
    private var state = CacheState(snapshot = null, indexes = emptyMap())

    fun indexFor(
        snapshot: TerminalCommandCompletionStatsSnapshot,
        shellSyntax: TerminalShellSyntax,
    ): LearnedCompletionEvidenceIndex {
        val observed = state
        if (snapshot === observed.snapshot) observed.indexes[shellSyntax]?.let { return it }
        return synchronized(lock) {
            val current = state
            val indexes = if (snapshot === current.snapshot) current.indexes else emptyMap()
            indexes[shellSyntax]
                ?: LearnedCompletionEvidenceIndex.build(snapshot, shellSyntax, outcomeResolver).also { built ->
                    state = CacheState(snapshot, indexes + (shellSyntax to built))
                }
        }
    }

    private data class CacheState(
        val snapshot: TerminalCommandCompletionStatsSnapshot?,
        val indexes: Map<TerminalShellSyntax, LearnedCompletionEvidenceIndex>,
    )
}

private data class EvidenceContext(
    val profileId: String?,
    val workingDirectoryUri: String?,
) {
    fun specificity(request: TerminalCompletionRequest): Int {
        var value = 0
        if (profileId != null) {
            if (profileId != request.profileId) return NO_MATCH
            value += PROFILE_SPECIFICITY
        }
        if (workingDirectoryUri != null) {
            if (workingDirectoryUri != request.workingDirectoryUri.canonicalDirectory()) return NO_MATCH
            value += DIRECTORY_SPECIFICITY
        }
        return value
    }

    fun boost(
        request: TerminalCompletionRequest,
        profile: Int,
        directory: Int,
    ): Int {
        var score = 0
        if (profileId != null && profileId == request.profileId) score += profile
        if (workingDirectoryUri != null && workingDirectoryUri == request.workingDirectoryUri.canonicalDirectory()) score += directory
        return score
    }

    private companion object {
        private const val NO_MATCH = -1
        private const val PROFILE_SPECIFICITY = 1
        private const val DIRECTORY_SPECIFICITY = 2
    }
}

private data class FeedbackContext(
    val profileId: String?,
    val workingDirectoryUri: String?,
)

private data class FeedbackKey(
    val source: String,
    val candidateKind: TerminalCompletionCandidateKind,
    val tokenPosition: TerminalCompletionTokenPosition,
    val context: FeedbackContext,
)

private fun TerminalCommandCompletionStats.evidenceContext(): EvidenceContext =
    EvidenceContext(profileId, workingDirectoryUri.canonicalDirectory())

private fun TerminalCommandShapeStats.evidenceContext(): EvidenceContext =
    EvidenceContext(profileId, workingDirectoryUri.canonicalDirectory())

private fun TerminalCompletionFeedbackStats.evidenceContext(): EvidenceContext =
    EvidenceContext(profileId, workingDirectoryUri.canonicalDirectory())

private fun String?.canonicalDirectory(): String? = this?.let(::canonicalizeWorkingDirectoryUri)

private fun <T> mostSpecific(
    rows: List<T>,
    request: TerminalCompletionRequest,
    context: (T) -> EvidenceContext,
): List<T> {
    var bestSpecificity = -1
    val selected = ArrayList<T>()
    for (row in rows) {
        val specificity = context(row).specificity(request)
        if (specificity < 0) continue
        when {
            specificity > bestSpecificity -> {
                bestSpecificity = specificity
                selected.clear()
                selected += row
            }
            specificity == bestSpecificity -> selected += row
        }
    }
    return selected
}

private fun <K, V> Map<K, MutableList<V>>.freeze(): Map<K, List<V>> = mapValues { (_, rows) -> rows.toList() }
