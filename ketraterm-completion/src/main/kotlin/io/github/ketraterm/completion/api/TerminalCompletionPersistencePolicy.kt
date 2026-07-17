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

import io.github.ketraterm.completion.history.CommandCompletionStatsSanitizer
import io.github.ketraterm.completion.history.CommandPersistencePrivacyPolicy
import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import io.github.ketraterm.completion.model.TerminalCommandShapeStats
import io.github.ketraterm.completion.model.TerminalCompletionPersistenceDecision

/**
 * Host-facing privacy policy for persisted completion-learning data.
 *
 * The completion engine records only data supplied by its host and performs no
 * disk I/O. Hosts must apply this policy before persistent learning or storage
 * of exact command text and must sanitize snapshots again at the storage boundary.
 */
object TerminalCompletionPersistencePolicy {
    /**
     * Returns whether [command] may be learned persistently or written to disk.
     *
     * @param command full command line captured by an authoritative host integration.
     * @return `true` when [command] is safe enough for local persisted learning.
     */
    fun allowsCommand(command: String): Boolean = CommandPersistencePrivacyPolicy.allowsCommand(command)

    /**
     * Evaluates whether [command] may be learned persistently or written to disk.
     *
     * @param command full command line captured by an authoritative host integration.
     * @return auditable privacy decision explaining the allow or reject result.
     */
    fun evaluateCommand(command: String): TerminalCompletionPersistenceDecision =
        CommandPersistencePrivacyPolicy.evaluateCommand(command)

    /**
     * Returns whether an exact command-statistics row may be persisted.
     *
     * @param record aggregate exact command-statistics row.
     * @return `true` when [record] is safe enough for local persistence.
     */
    fun allowsCommandStats(record: TerminalCommandCompletionStats): Boolean =
        CommandPersistencePrivacyPolicy.allowsCommandStats(record)

    /**
     * Evaluates whether an exact command-statistics row may be persisted.
     *
     * @param record aggregate exact command-statistics row.
     * @return auditable privacy decision for [record].
     */
    fun evaluateCommandStats(record: TerminalCommandCompletionStats): TerminalCompletionPersistenceDecision =
        CommandPersistencePrivacyPolicy.evaluateCommandStats(record)

    /**
     * Returns whether a structural shape-statistics row may be persisted.
     *
     * @param record aggregate structural command-shape row.
     * @return `true` when [record] is safe enough for local persistence.
     */
    fun allowsShapeStats(record: TerminalCommandShapeStats): Boolean =
        CommandPersistencePrivacyPolicy.allowsShapeStats(record)

    /**
     * Evaluates whether a structural shape-statistics row may be persisted.
     *
     * @param record aggregate structural command-shape row.
     * @return auditable privacy decision for [record].
     */
    fun evaluateShapeStats(record: TerminalCommandShapeStats): TerminalCompletionPersistenceDecision =
        CommandPersistencePrivacyPolicy.evaluateShapeStats(record)

    /**
     * Returns a snapshot safe enough for local persisted completion learning.
     *
     * Exact command and structural shape rows are filtered independently.
     * Feedback rows are retained because their contract contains source and UX
     * counters rather than raw command arguments.
     *
     * @param snapshot completion-learning snapshot at a persistence boundary.
     * @return sanitized immutable snapshot.
     */
    fun sanitizeSnapshot(snapshot: TerminalCommandCompletionStatsSnapshot): TerminalCommandCompletionStatsSnapshot =
        CommandCompletionStatsSanitizer.sanitize(snapshot)
}
