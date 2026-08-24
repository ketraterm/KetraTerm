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
 * Immutable snapshot of exact command completion statistics.
 *
 * Hosts persist this model as compact suggestion-learning metadata. It stores
 * aggregate counters rather than repeated history rows or terminal output.
 *
 * @property commandStats exact command-line rows used for learned ranking and suggestions.
 */
class TerminalCommandCompletionStatsSnapshot
    @JvmOverloads
    constructor(
        commandStats: List<TerminalCommandCompletionStats> = emptyList(),
    ) {
        val commandStats: List<TerminalCommandCompletionStats> = immutableListCopy(commandStats)

        fun copy(commandStats: List<TerminalCommandCompletionStats> = this.commandStats): TerminalCommandCompletionStatsSnapshot =
            TerminalCommandCompletionStatsSnapshot(commandStats)

        operator fun component1(): List<TerminalCommandCompletionStats> = commandStats

        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is TerminalCommandCompletionStatsSnapshot && commandStats == other.commandStats)

        override fun hashCode(): Int = commandStats.hashCode()

        override fun toString(): String = "TerminalCommandCompletionStatsSnapshot(commandStats=$commandStats)"

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
