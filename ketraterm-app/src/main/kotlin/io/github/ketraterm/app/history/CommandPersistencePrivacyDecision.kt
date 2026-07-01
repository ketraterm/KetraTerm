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
package io.github.ketraterm.app.history

/**
 * Reason category for a persisted command-learning privacy decision.
 */
internal enum class CommandPersistencePrivacyDecisionKind {
    /**
     * The command-learning row is safe enough for local persisted learning.
     */
    ALLOWED,

    /**
     * The command is blank or contains line breaks and is not a safe single-line
     * learning candidate.
     */
    BLANK_OR_MULTILINE,

    /**
     * The command starts with whitespace and should follow shell ignorespace
     * privacy semantics.
     */
    IGNORES_SPACE,

    /**
     * The command-learning row contains credential-looking vocabulary.
     */
    SENSITIVE_KEYWORD,
}

/**
 * Field or metadata surface that produced a privacy decision.
 */
internal enum class CommandPersistencePrivacyDecisionLocation {
    /**
     * Raw exact command text.
     */
    COMMAND_TEXT,

    /**
     * Public executable name in a structural command shape.
     */
    SHAPE_EXECUTABLE,

    /**
     * Public subcommand name in a structural command shape.
     */
    SHAPE_SUBCOMMAND,

    /**
     * Public option name in a structural command shape.
     */
    SHAPE_OPTION_NAME,

    /**
     * Normalized structural command-shape key.
     */
    SHAPE_KEY,
}

/**
 * Auditable privacy decision for command-learning persistence.
 *
 * @property kind reason category for the decision.
 * @property matchedText optional vocabulary fragment that caused rejection.
 * @property location optional field or metadata surface that produced the decision.
 */
internal data class CommandPersistencePrivacyDecision(
    val kind: CommandPersistencePrivacyDecisionKind,
    val matchedText: String? = null,
    val location: CommandPersistencePrivacyDecisionLocation? = null,
) {
    /**
     * Whether the row may be persisted.
     */
    val isAllowed: Boolean get() = kind == CommandPersistencePrivacyDecisionKind.ALLOWED

    init {
        require((kind == CommandPersistencePrivacyDecisionKind.SENSITIVE_KEYWORD) == (matchedText != null)) {
            "matchedText must be present only for sensitive-keyword decisions"
        }
        require(matchedText == null || matchedText.isNotBlank()) { "matchedText must not be blank" }
        require((kind != CommandPersistencePrivacyDecisionKind.ALLOWED) == (location != null)) {
            "location must be present only for rejection decisions"
        }
    }

    companion object {
        /**
         * Allowed persistence decision.
         */
        val ALLOWED: CommandPersistencePrivacyDecision =
            CommandPersistencePrivacyDecision(CommandPersistencePrivacyDecisionKind.ALLOWED)

        /**
         * Blank or multiline rejection decision.
         */
        val BLANK_OR_MULTILINE: CommandPersistencePrivacyDecision =
            CommandPersistencePrivacyDecision(
                kind = CommandPersistencePrivacyDecisionKind.BLANK_OR_MULTILINE,
                location = CommandPersistencePrivacyDecisionLocation.COMMAND_TEXT,
            )

        /**
         * Ignorespace rejection decision.
         */
        val IGNORES_SPACE: CommandPersistencePrivacyDecision =
            CommandPersistencePrivacyDecision(
                kind = CommandPersistencePrivacyDecisionKind.IGNORES_SPACE,
                location = CommandPersistencePrivacyDecisionLocation.COMMAND_TEXT,
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
            location: CommandPersistencePrivacyDecisionLocation = CommandPersistencePrivacyDecisionLocation.COMMAND_TEXT,
        ): CommandPersistencePrivacyDecision =
            CommandPersistencePrivacyDecision(
                kind = CommandPersistencePrivacyDecisionKind.SENSITIVE_KEYWORD,
                matchedText = keyword,
                location = location,
            )
    }
}
