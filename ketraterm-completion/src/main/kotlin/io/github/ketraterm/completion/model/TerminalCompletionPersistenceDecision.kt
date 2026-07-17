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

/** Reason category for a persisted completion-learning privacy decision. */
enum class TerminalCompletionPersistenceDecisionKind {
    /** The completion-learning row is safe enough for local persistence. */
    ALLOWED,

    /** The command is blank or multiline and cannot be persisted safely. */
    BLANK_OR_MULTILINE,

    /** The command starts with whitespace and follows shell ignorespace privacy semantics. */
    IGNORES_SPACE,

    /** The completion-learning row contains credential-looking vocabulary. */
    SENSITIVE_KEYWORD,
}

/** Field or metadata surface that produced a completion-persistence decision. */
enum class TerminalCompletionPersistenceDecisionLocation {
    /** Raw exact command text. */
    COMMAND_TEXT,

    /** Public executable name in a structural command shape. */
    SHAPE_EXECUTABLE,

    /** Public subcommand name in a structural command shape. */
    SHAPE_SUBCOMMAND,

    /** Public option name in a structural command shape. */
    SHAPE_OPTION_NAME,
}

/**
 * Auditable privacy decision for persisted completion learning.
 *
 * @property kind reason category for the decision.
 * @property matchedText optional vocabulary fragment that caused rejection.
 * @property location optional field or metadata surface that produced the decision.
 */
data class TerminalCompletionPersistenceDecision(
    val kind: TerminalCompletionPersistenceDecisionKind,
    val matchedText: String? = null,
    val location: TerminalCompletionPersistenceDecisionLocation? = null,
) {
    /** Whether the row may be persisted. */
    val isAllowed: Boolean get() = kind == TerminalCompletionPersistenceDecisionKind.ALLOWED

    init {
        require((kind == TerminalCompletionPersistenceDecisionKind.SENSITIVE_KEYWORD) == (matchedText != null)) {
            "matchedText must be present only for sensitive-keyword decisions"
        }
        require(matchedText == null || matchedText.isNotBlank()) { "matchedText must not be blank" }
        require((kind != TerminalCompletionPersistenceDecisionKind.ALLOWED) == (location != null)) {
            "location must be present only for rejection decisions"
        }
    }

    companion object {
        /** Allowed persistence decision. */
        val ALLOWED: TerminalCompletionPersistenceDecision =
            TerminalCompletionPersistenceDecision(TerminalCompletionPersistenceDecisionKind.ALLOWED)

        /** Blank or multiline rejection decision. */
        val BLANK_OR_MULTILINE: TerminalCompletionPersistenceDecision =
            TerminalCompletionPersistenceDecision(
                kind = TerminalCompletionPersistenceDecisionKind.BLANK_OR_MULTILINE,
                location = TerminalCompletionPersistenceDecisionLocation.COMMAND_TEXT,
            )

        /** Ignorespace rejection decision. */
        val IGNORES_SPACE: TerminalCompletionPersistenceDecision =
            TerminalCompletionPersistenceDecision(
                kind = TerminalCompletionPersistenceDecisionKind.IGNORES_SPACE,
                location = TerminalCompletionPersistenceDecisionLocation.COMMAND_TEXT,
            )

        /**
         * Returns a sensitive-keyword rejection decision.
         *
         * @param keyword matched sensitive keyword.
         * @param location field or metadata surface that matched [keyword].
         * @return rejection decision carrying [keyword].
         */
        fun sensitiveKeyword(
            keyword: String,
            location: TerminalCompletionPersistenceDecisionLocation =
                TerminalCompletionPersistenceDecisionLocation.COMMAND_TEXT,
        ): TerminalCompletionPersistenceDecision =
            TerminalCompletionPersistenceDecision(
                kind = TerminalCompletionPersistenceDecisionKind.SENSITIVE_KEYWORD,
                matchedText = keyword,
                location = location,
            )
    }
}
