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
package io.github.ketraterm.completion.model

/**
 * Complete snapshot of exact command, structural shape, and feedback statistics.
 *
 * Hosts persist this model as compact suggestion-learning metadata. The
 * snapshot intentionally contains aggregate counters only; raw repeated history
 * rows and raw terminal output are outside this contract.
 *
 * @property commandStats exact command-line rows used for concrete suggestions.
 * @property shapeStats privacy-preserving command-shape rows used for ranking
 * analytics and shape-aware suggestion ranking.
 * @property feedbackStats source-specific feedback rows used for provider-aware
 * ranking.
 */
class TerminalCommandCompletionStatsSnapshot
    @JvmOverloads
    constructor(
        commandStats: List<TerminalCommandCompletionStats> = emptyList(),
        shapeStats: List<TerminalCommandShapeStats> = emptyList(),
        feedbackStats: List<TerminalCompletionFeedbackStats> = emptyList(),
    ) {
        val commandStats: List<TerminalCommandCompletionStats> = immutableListCopy(commandStats)
        val shapeStats: List<TerminalCommandShapeStats> = immutableListCopy(shapeStats)
        val feedbackStats: List<TerminalCompletionFeedbackStats> = immutableListCopy(feedbackStats)

        fun copy(
            commandStats: List<TerminalCommandCompletionStats> = this.commandStats,
            shapeStats: List<TerminalCommandShapeStats> = this.shapeStats,
            feedbackStats: List<TerminalCompletionFeedbackStats> = this.feedbackStats,
        ): TerminalCommandCompletionStatsSnapshot =
            TerminalCommandCompletionStatsSnapshot(
                commandStats = commandStats,
                shapeStats = shapeStats,
                feedbackStats = feedbackStats,
            )

        operator fun component1(): List<TerminalCommandCompletionStats> = commandStats

        operator fun component2(): List<TerminalCommandShapeStats> = shapeStats

        operator fun component3(): List<TerminalCompletionFeedbackStats> = feedbackStats

        override fun equals(other: Any?): Boolean =
            this === other ||
                (
                    other is TerminalCommandCompletionStatsSnapshot &&
                        commandStats == other.commandStats &&
                        shapeStats == other.shapeStats &&
                        feedbackStats == other.feedbackStats
                )

        override fun hashCode(): Int {
            var result = commandStats.hashCode()
            result = 31 * result + shapeStats.hashCode()
            result = 31 * result + feedbackStats.hashCode()
            return result
        }

        override fun toString(): String =
            "TerminalCommandCompletionStatsSnapshot(" +
                "commandStats=$commandStats, " +
                "shapeStats=$shapeStats, " +
                "feedbackStats=$feedbackStats" +
                ")"

        companion object {
            /** Shared empty learned-statistics snapshot. */
            @JvmField
            val EMPTY = TerminalCommandCompletionStatsSnapshot()
        }
    }

private fun <T> immutableListCopy(values: List<T>): List<T> =
    if (values.isEmpty()) {
        emptyList()
    } else {
        java.util.List.copyOf(values)
    }
