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
package io.github.ketraterm.completion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TerminalCompletionFeedbackStatsTest {
    @Test
    fun `token position maps every candidate kind`() {
        assertEquals(
            TerminalCompletionTokenPosition.COMMAND,
            TerminalCompletionTokenPosition.fromCandidateKind(TerminalCompletionCandidateKind.COMMAND),
        )
        assertEquals(
            TerminalCompletionTokenPosition.SUBCOMMAND,
            TerminalCompletionTokenPosition.fromCandidateKind(TerminalCompletionCandidateKind.SUBCOMMAND),
        )
        assertEquals(
            TerminalCompletionTokenPosition.OPTION,
            TerminalCompletionTokenPosition.fromCandidateKind(TerminalCompletionCandidateKind.OPTION),
        )
        assertEquals(
            TerminalCompletionTokenPosition.ARGUMENT,
            TerminalCompletionTokenPosition.fromCandidateKind(TerminalCompletionCandidateKind.ARGUMENT),
        )
        assertEquals(
            TerminalCompletionTokenPosition.ARGUMENT,
            TerminalCompletionTokenPosition.fromCandidateKind(TerminalCompletionCandidateKind.PATH),
        )
        assertEquals(
            TerminalCompletionTokenPosition.UNKNOWN,
            TerminalCompletionTokenPosition.fromCandidateKind(TerminalCompletionCandidateKind.HISTORY),
        )
    }

    @Test
    fun `feedback context rejects blank source and malformed ranges`() {
        assertFailsWith<IllegalArgumentException> {
            TerminalCompletionFeedbackContext(
                source = " ",
                candidateKind = TerminalCompletionCandidateKind.SUBCOMMAND,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TerminalCompletionFeedbackContext(
                source = "spec",
                candidateKind = TerminalCompletionCandidateKind.SUBCOMMAND,
                replacementStartOffset = -1,
                replacementEndOffset = 4,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TerminalCompletionFeedbackContext(
                source = "spec",
                candidateKind = TerminalCompletionCandidateKind.SUBCOMMAND,
                replacementStartOffset = 5,
                replacementEndOffset = 4,
            )
        }
    }

    @Test
    fun `feedback stats reject negative counters and timestamps`() {
        assertFailsWith<IllegalArgumentException> {
            feedbackStats(acceptedCount = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            feedbackStats(dismissedCount = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            feedbackStats(lastUsedEpochMillis = -1)
        }
    }

    private fun feedbackStats(
        acceptedCount: Int = 0,
        dismissedCount: Int = 0,
        lastUsedEpochMillis: Long = 0,
    ): TerminalCompletionFeedbackStats =
        TerminalCompletionFeedbackStats(
            source = "spec",
            candidateKind = TerminalCompletionCandidateKind.SUBCOMMAND,
            acceptedCount = acceptedCount,
            dismissedCount = dismissedCount,
            lastUsedEpochMillis = lastUsedEpochMillis,
        )
}
