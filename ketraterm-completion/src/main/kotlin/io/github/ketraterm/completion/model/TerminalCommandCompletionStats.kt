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

import io.github.ketraterm.completion.internal.hasTerminalCompletionLineBreak
import io.github.ketraterm.completion.internal.normalizeTerminalCommandLine

/**
 * Aggregated command statistics used by indexed history completion.
 *
 * The model stores compact counters instead of raw repeated history rows.
 * Hosts own persistence and import/export these values into
 * [io.github.ketraterm.completion.api.TerminalCommandStatsCompletionSource];
 * this shared model performs no I/O.
 * The normalized matching key is derived from [commandLine] so callers cannot
 * persist contradictory command text and lookup state.
 *
 * @property commandLine canonical command text shown to the user.
 * @property profileId optional host profile id associated with the stats row.
 * @property workingDirectoryUri optional working directory URI associated with
 * the stats row.
 * @property useCount number of observed command executions.
 * @property successCount number of executions that completed successfully.
 * @property failureCount number of executions that completed unsuccessfully.
 * @property acceptedCount number of times this command was accepted from a
 * suggestion popup.
 * @property dismissedCount number of times this command was explicitly
 * dismissed from a suggestion popup.
 * @property lastUsedEpochMillis host wall-clock timestamp for the newest
 * execution or feedback event represented by this row.
 * @throws IllegalArgumentException if [commandLine] is blank or multiline, any
 * counter is negative, or [lastUsedEpochMillis] is negative.
 */
data class TerminalCommandCompletionStats
    @JvmOverloads
    constructor(
        val commandLine: String,
        val profileId: String? = null,
        val workingDirectoryUri: String? = null,
        val useCount: Int = 0,
        val successCount: Int = 0,
        val failureCount: Int = 0,
        val acceptedCount: Int = 0,
        val dismissedCount: Int = 0,
        val lastUsedEpochMillis: Long = 0L,
    ) {
        init {
            require(commandLine.isNotBlank()) { "commandLine must not be blank" }
            require(!commandLine.hasTerminalCompletionLineBreak()) { "commandLine must not contain line breaks" }
            require(useCount >= 0) { "useCount must be >= 0, was $useCount" }
            require(successCount >= 0) { "successCount must be >= 0, was $successCount" }
            require(failureCount >= 0) { "failureCount must be >= 0, was $failureCount" }
            require(acceptedCount >= 0) { "acceptedCount must be >= 0, was $acceptedCount" }
            require(dismissedCount >= 0) { "dismissedCount must be >= 0, was $dismissedCount" }
            require(lastUsedEpochMillis >= 0L) { "lastUsedEpochMillis must be >= 0, was $lastUsedEpochMillis" }
        }

        /**
         * Derived lowercase key used for prefix matching and exact-row
         * deduplication. This value is not constructor-owned durable state.
         */
        val normalizedCommandLine: String = normalizeTerminalCommandLine(commandLine)
    }
