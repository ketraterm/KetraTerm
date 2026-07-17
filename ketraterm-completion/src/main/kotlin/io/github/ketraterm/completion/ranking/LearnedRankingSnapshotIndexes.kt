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

import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import io.github.ketraterm.completion.model.TerminalCommandLineShape
import io.github.ketraterm.completion.model.TerminalCommandShapeStats
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackStats
import io.github.ketraterm.completion.model.TerminalCompletionTokenPosition

internal interface IndexedFeedbackRankingSnapshot {
    val rankingIndex: FeedbackRankingSnapshotIndex
}

internal interface IndexedShapeRankingSnapshot {
    val rankingIndex: ShapeRankingSnapshotIndex
}

internal fun indexedFeedbackRankingSnapshot(records: List<TerminalCompletionFeedbackStats>): List<TerminalCompletionFeedbackStats> =
    PublishedFeedbackRankingSnapshot(
        records = records,
        rankingIndex = FeedbackRankingSnapshotIndex.from(records),
    )

internal fun indexedShapeRankingSnapshot(records: List<TerminalCommandShapeStats>): List<TerminalCommandShapeStats> =
    PublishedShapeRankingSnapshot(
        records = records,
        rankingIndex = ShapeRankingSnapshotIndex.from(records),
    )

private class PublishedFeedbackRankingSnapshot(
    records: List<TerminalCompletionFeedbackStats>,
    override val rankingIndex: FeedbackRankingSnapshotIndex,
) : List<TerminalCompletionFeedbackStats> by records,
    IndexedFeedbackRankingSnapshot

private class PublishedShapeRankingSnapshot(
    records: List<TerminalCommandShapeStats>,
    override val rankingIndex: ShapeRankingSnapshotIndex,
) : List<TerminalCommandShapeStats> by records,
    IndexedShapeRankingSnapshot

/** Immutable direct-lookup index for one published feedback snapshot. */
internal class FeedbackRankingSnapshotIndex private constructor(
    private val sources: Map<String, FeedbackSourceBucket>,
) {
    val isEmpty: Boolean
        get() = sources.isEmpty()

    fun matchingRows(
        source: String,
        candidateKind: TerminalCompletionCandidateKind,
        tokenPosition: TerminalCompletionTokenPosition,
        profileId: String?,
        workingDirectoryUri: String?,
    ): List<TerminalCompletionFeedbackStats> =
        sources[source]
            ?.context(candidateKind, tokenPosition)
            ?.mostSpecificRows(profileId, workingDirectoryUri)
            ?: emptyList()

    companion object {
        val EMPTY = FeedbackRankingSnapshotIndex(emptyMap())

        fun from(records: List<TerminalCompletionFeedbackStats>): FeedbackRankingSnapshotIndex {
            if (records.isEmpty()) return EMPTY
            val mutableSources = HashMap<String, MutableFeedbackSourceBucket>()
            for (record in records) {
                mutableSources
                    .getOrPut(record.source, ::MutableFeedbackSourceBucket)
                    .add(record)
            }
            return FeedbackRankingSnapshotIndex(
                mutableSources.mapValues { (_, bucket) -> bucket.freeze() },
            )
        }
    }
}

private class FeedbackSourceBucket(
    private val contexts: Array<FeedbackContextRows?>,
) {
    fun context(
        candidateKind: TerminalCompletionCandidateKind,
        tokenPosition: TerminalCompletionTokenPosition,
    ): FeedbackContextRows? = contexts[contextIndex(candidateKind, tokenPosition)]
}

private class MutableFeedbackSourceBucket {
    private val contexts = arrayOfNulls<MutableFeedbackContextRows>(CONTEXT_SLOT_COUNT)

    fun add(record: TerminalCompletionFeedbackStats) {
        val index = contextIndex(record.candidateKind, record.tokenPosition)
        val context = contexts[index] ?: MutableFeedbackContextRows().also { contexts[index] = it }
        context.add(record)
    }

    fun freeze(): FeedbackSourceBucket =
        FeedbackSourceBucket(
            Array(contexts.size) { index -> contexts[index]?.freeze() },
        )
}

private class FeedbackContextRows(
    private val broad: List<TerminalCompletionFeedbackStats>,
    private val byProfile: Map<String, List<TerminalCompletionFeedbackStats>>,
    private val byDirectory: Map<String, List<TerminalCompletionFeedbackStats>>,
    private val byProfileAndDirectory: Map<String, Map<String, List<TerminalCompletionFeedbackStats>>>,
) {
    fun mostSpecificRows(
        profileId: String?,
        workingDirectoryUri: String?,
    ): List<TerminalCompletionFeedbackStats> {
        if (profileId != null && workingDirectoryUri != null) {
            byProfileAndDirectory[profileId]?.get(workingDirectoryUri)?.let { return it }
        }
        if (workingDirectoryUri != null) {
            byDirectory[workingDirectoryUri]?.let { return it }
        }
        if (profileId != null) {
            byProfile[profileId]?.let { return it }
        }
        return broad
    }
}

private class MutableFeedbackContextRows {
    private val broad = ArrayList<TerminalCompletionFeedbackStats>(1)
    private val byProfile = HashMap<String, MutableList<TerminalCompletionFeedbackStats>>()
    private val byDirectory = HashMap<String, MutableList<TerminalCompletionFeedbackStats>>()
    private val byProfileAndDirectory =
        HashMap<String, MutableMap<String, MutableList<TerminalCompletionFeedbackStats>>>()

    fun add(record: TerminalCompletionFeedbackStats) {
        val profileId = record.profileId
        val workingDirectoryUri = record.workingDirectoryUri
        when {
            profileId != null && workingDirectoryUri != null ->
                byProfileAndDirectory
                    .getOrPut(profileId, ::HashMap)
                    .getOrPut(workingDirectoryUri, ::ArrayList)
                    .add(record)

            workingDirectoryUri != null -> byDirectory.getOrPut(workingDirectoryUri, ::ArrayList).add(record)
            profileId != null -> byProfile.getOrPut(profileId, ::ArrayList).add(record)
            else -> broad.add(record)
        }
    }

    fun freeze(): FeedbackContextRows =
        FeedbackContextRows(
            broad = broad.toList(),
            byProfile = byProfile.mapValues { (_, rows) -> rows.toList() },
            byDirectory = byDirectory.mapValues { (_, rows) -> rows.toList() },
            byProfileAndDirectory =
                byProfileAndDirectory.mapValues { (_, directories) ->
                    directories.mapValues { (_, rows) -> rows.toList() }
                },
        )
}

/** Immutable command-family index for one published shape snapshot. */
internal class ShapeRankingSnapshotIndex private constructor(
    private val rowsByExecutable: Map<String, List<TerminalCommandShapeStats>>,
) {
    val isEmpty: Boolean
        get() = rowsByExecutable.isEmpty()

    fun familyRows(shape: TerminalCommandLineShape): List<TerminalCommandShapeStats> = rowsByExecutable[shape.executable] ?: emptyList()

    companion object {
        val EMPTY = ShapeRankingSnapshotIndex(emptyMap())

        fun from(records: List<TerminalCommandShapeStats>): ShapeRankingSnapshotIndex {
            if (records.isEmpty()) return EMPTY
            val mutableRows = HashMap<String, MutableList<TerminalCommandShapeStats>>()
            for (record in records) {
                mutableRows.getOrPut(record.shape.executable, ::ArrayList).add(record)
            }
            return ShapeRankingSnapshotIndex(
                mutableRows.mapValues { (_, rows) -> rows.toList() },
            )
        }
    }
}

private val CANDIDATE_KIND_COUNT = TerminalCompletionCandidateKind.entries.size
private val TOKEN_POSITION_COUNT = TerminalCompletionTokenPosition.entries.size
private val CONTEXT_SLOT_COUNT = CANDIDATE_KIND_COUNT * TOKEN_POSITION_COUNT

private fun contextIndex(
    candidateKind: TerminalCompletionCandidateKind,
    tokenPosition: TerminalCompletionTokenPosition,
): Int = candidateKind.ordinal * TOKEN_POSITION_COUNT + tokenPosition.ordinal
