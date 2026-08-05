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

import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCommandShapeStats
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackStats

/** Saturating aggregate used by every learned evidence scoring family. */
internal data class LearnedEvidenceCounts(
    var useCount: Long = 0,
    var successCount: Long = 0,
    var failureCount: Long = 0,
    var acceptedCount: Long = 0,
    var dismissedCount: Long = 0,
    var lastUsedEpochMillis: Long = 0,
)

/** Converts persisted rows into overflow-safe scoring aggregates. */
internal object LearnedEvidenceCounters {
    fun fromCommands(rows: List<TerminalCommandCompletionStats>): LearnedEvidenceCounts =
        LearnedEvidenceCounts().also { counts ->
            for (row in rows) {
                counts.add(
                    useCount = row.useCount,
                    successCount = row.successCount,
                    failureCount = row.failureCount,
                    acceptedCount = row.acceptedCount,
                    dismissedCount = row.dismissedCount,
                    lastUsedEpochMillis = row.lastUsedEpochMillis,
                )
            }
        }

    fun fromShapes(rows: List<TerminalCommandShapeStats>): LearnedEvidenceCounts =
        LearnedEvidenceCounts().also { counts ->
            for (row in rows) {
                counts.add(
                    useCount = row.useCount,
                    successCount = row.successCount,
                    failureCount = row.failureCount,
                    acceptedCount = row.acceptedCount,
                    dismissedCount = row.dismissedCount,
                    lastUsedEpochMillis = row.lastUsedEpochMillis,
                )
            }
        }

    fun fromFeedback(rows: List<TerminalCompletionFeedbackStats>): LearnedEvidenceCounts =
        LearnedEvidenceCounts().also { counts ->
            for (row in rows) {
                counts.acceptedCount = saturatedAdd(counts.acceptedCount, row.acceptedCount.toLong())
                counts.dismissedCount = saturatedAdd(counts.dismissedCount, row.dismissedCount.toLong())
                counts.lastUsedEpochMillis = maxOf(counts.lastUsedEpochMillis, row.lastUsedEpochMillis)
            }
        }

    private fun LearnedEvidenceCounts.add(
        useCount: Int,
        successCount: Int,
        failureCount: Int,
        acceptedCount: Int,
        dismissedCount: Int,
        lastUsedEpochMillis: Long,
    ) {
        this.useCount = saturatedAdd(this.useCount, useCount.toLong())
        this.successCount = saturatedAdd(this.successCount, successCount.toLong())
        this.failureCount = saturatedAdd(this.failureCount, failureCount.toLong())
        this.acceptedCount = saturatedAdd(this.acceptedCount, acceptedCount.toLong())
        this.dismissedCount = saturatedAdd(this.dismissedCount, dismissedCount.toLong())
        this.lastUsedEpochMillis = maxOf(this.lastUsedEpochMillis, lastUsedEpochMillis)
    }

    private fun saturatedAdd(
        left: Long,
        right: Long,
    ): Long = if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
