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

internal enum class CompletionPersistenceDecisionKind {
    ALLOWED,
    BLANK_OR_MULTILINE,
    IGNORES_SPACE,
    SENSITIVE_KEYWORD,
}

internal enum class CompletionPersistenceDecisionLocation {
    COMMAND_TEXT,
    SHAPE_EXECUTABLE,
    SHAPE_SUBCOMMAND,
    SHAPE_OPTION_NAME,
}

internal data class CompletionPersistenceDecision(
    val kind: CompletionPersistenceDecisionKind,
    val matchedText: String? = null,
    val location: CompletionPersistenceDecisionLocation? = null,
) {
    val isAllowed: Boolean get() = kind == CompletionPersistenceDecisionKind.ALLOWED

    init {
        require((kind == CompletionPersistenceDecisionKind.SENSITIVE_KEYWORD) == (matchedText != null)) {
            "matchedText must be present only for sensitive-keyword decisions"
        }
        require(matchedText == null || matchedText.isNotBlank()) { "matchedText must not be blank" }
        require((kind != CompletionPersistenceDecisionKind.ALLOWED) == (location != null)) {
            "location must be present only for rejection decisions"
        }
    }

    companion object {
        val ALLOWED = CompletionPersistenceDecision(CompletionPersistenceDecisionKind.ALLOWED)
        val BLANK_OR_MULTILINE =
            CompletionPersistenceDecision(
                kind = CompletionPersistenceDecisionKind.BLANK_OR_MULTILINE,
                location = CompletionPersistenceDecisionLocation.COMMAND_TEXT,
            )
        val IGNORES_SPACE =
            CompletionPersistenceDecision(
                kind = CompletionPersistenceDecisionKind.IGNORES_SPACE,
                location = CompletionPersistenceDecisionLocation.COMMAND_TEXT,
            )

        fun sensitiveKeyword(
            keyword: String,
            location: CompletionPersistenceDecisionLocation = CompletionPersistenceDecisionLocation.COMMAND_TEXT,
        ): CompletionPersistenceDecision =
            CompletionPersistenceDecision(
                kind = CompletionPersistenceDecisionKind.SENSITIVE_KEYWORD,
                matchedText = keyword,
                location = location,
            )
    }
}
