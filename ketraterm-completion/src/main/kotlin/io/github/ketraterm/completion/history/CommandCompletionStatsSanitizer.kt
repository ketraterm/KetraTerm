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
package io.github.ketraterm.completion.history

import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot

/**
 * Privacy sanitizer for persisted completion-learning snapshots.
 *
 * Exact command rows can contain raw command text, and shape rows can still
 * expose sensitive executable, subcommand, or option vocabulary. Those rows are
 * filtered through [CommandPersistencePrivacyPolicy]. Feedback rows are kept by
 * contract because they contain provider/source metadata and UX counters, not
 * raw command arguments.
 */
object CommandCompletionStatsSanitizer {
    /**
     * Returns a snapshot safe enough for local persisted suggestion learning.
     *
     * @param snapshot unsanitized completion-learning snapshot.
     * @return snapshot with sensitive command and shape rows removed.
     */
    fun sanitize(snapshot: TerminalCommandCompletionStatsSnapshot): TerminalCommandCompletionStatsSnapshot =
        TerminalCommandCompletionStatsSnapshot(
            commandStats = snapshot.commandStats.filter(CommandPersistencePrivacyPolicy::allowsCommandStats),
            shapeStats = snapshot.shapeStats.filter(CommandPersistencePrivacyPolicy::allowsShapeStats),
            feedbackStats = snapshot.feedbackStats,
        )
}
