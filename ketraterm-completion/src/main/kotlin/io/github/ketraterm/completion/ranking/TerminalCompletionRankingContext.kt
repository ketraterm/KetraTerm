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
import io.github.ketraterm.completion.commandline.TerminalCompletionActivePosition
import io.github.ketraterm.completion.commandline.TerminalCompletionContext
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import io.github.ketraterm.completion.model.TerminalPathArgumentKind

internal class TerminalCompletionRankingContext(
    private val context: TerminalCompletionContext,
) {
    fun priorityAdjustment(candidate: TerminalCompletionCandidate): Int =
        when (context.activePosition) {
            TerminalCompletionActivePosition.OPERATOR -> 0
            TerminalCompletionActivePosition.COMMAND -> commandPositionAdjustment(candidate)
            TerminalCompletionActivePosition.SUBCOMMAND -> subcommandPositionAdjustment(candidate)
            TerminalCompletionActivePosition.OPTION_NAME -> optionNamePositionAdjustment(candidate)
            TerminalCompletionActivePosition.OPTION_VALUE -> optionValuePositionAdjustment(candidate)
            TerminalCompletionActivePosition.POSITIONAL_ARGUMENT -> positionalArgumentAdjustment(candidate)
        }

    private fun commandPositionAdjustment(candidate: TerminalCompletionCandidate): Int =
        when (candidate.kind) {
            TerminalCompletionCandidateKind.COMMAND -> STRONG_CONTEXT_BOOST
            TerminalCompletionCandidateKind.PATH -> WEAK_CONTEXT_BOOST
            TerminalCompletionCandidateKind.HISTORY -> HISTORY_CONTEXT_PENALTY
            else -> 0
        }

    private fun subcommandPositionAdjustment(candidate: TerminalCompletionCandidate): Int =
        when (candidate.kind) {
            TerminalCompletionCandidateKind.SUBCOMMAND -> STRONG_CONTEXT_BOOST
            TerminalCompletionCandidateKind.HISTORY -> HISTORY_CONTEXT_PENALTY
            TerminalCompletionCandidateKind.PATH -> PATH_CONTEXT_PENALTY
            else -> 0
        }

    private fun optionNamePositionAdjustment(candidate: TerminalCompletionCandidate): Int =
        when (candidate.kind) {
            TerminalCompletionCandidateKind.OPTION -> STRONG_CONTEXT_BOOST
            TerminalCompletionCandidateKind.HISTORY -> HISTORY_CONTEXT_PENALTY
            TerminalCompletionCandidateKind.PATH -> PATH_CONTEXT_PENALTY
            else -> 0
        }

    private fun optionValuePositionAdjustment(candidate: TerminalCompletionCandidate): Int =
        when (candidate.kind) {
            TerminalCompletionCandidateKind.ARGUMENT ->
                if (candidate.matchesExpectedDomain()) {
                    DOMAIN_CONTEXT_BOOST
                } else {
                    STRONG_CONTEXT_BOOST
                }
            TerminalCompletionCandidateKind.PATH ->
                if (context.expectedPathKind == TerminalPathArgumentKind.NONE) {
                    PATH_CONTEXT_PENALTY
                } else {
                    STRONG_CONTEXT_BOOST
                }
            TerminalCompletionCandidateKind.HISTORY -> HISTORY_CONTEXT_PENALTY
            else -> 0
        }

    private fun positionalArgumentAdjustment(candidate: TerminalCompletionCandidate): Int =
        when (candidate.kind) {
            TerminalCompletionCandidateKind.ARGUMENT ->
                if (candidate.matchesExpectedDomain()) {
                    DOMAIN_CONTEXT_BOOST
                } else {
                    MEDIUM_CONTEXT_BOOST
                }
            TerminalCompletionCandidateKind.PATH ->
                if (context.expectedPathKind == TerminalPathArgumentKind.NONE) {
                    0
                } else {
                    STRONG_CONTEXT_BOOST
                }
            TerminalCompletionCandidateKind.HISTORY -> HISTORY_CONTEXT_PENALTY
            TerminalCompletionCandidateKind.SUBCOMMAND -> PATH_CONTEXT_PENALTY
            else -> 0
        }

    private fun TerminalCompletionCandidate.matchesExpectedDomain(): Boolean =
        context.expectedValueDomain != TerminalCompletionValueDomain.NONE &&
            valueDomain == context.expectedValueDomain

    private companion object {
        private const val DOMAIN_CONTEXT_BOOST = 320
        private const val STRONG_CONTEXT_BOOST = 160
        private const val MEDIUM_CONTEXT_BOOST = 80
        private const val WEAK_CONTEXT_BOOST = 40
        private const val HISTORY_CONTEXT_PENALTY = -40
        private const val PATH_CONTEXT_PENALTY = -80
    }
}
