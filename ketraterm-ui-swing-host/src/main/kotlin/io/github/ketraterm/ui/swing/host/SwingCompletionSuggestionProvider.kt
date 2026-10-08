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
package io.github.ketraterm.ui.swing.host

import io.github.ketraterm.completion.api.TerminalCompletionCandidate
import io.github.ketraterm.completion.api.TerminalCompletionEngine
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.api.TerminalShellCapabilities
import io.github.ketraterm.ui.swing.suggestion.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.util.*
import javax.swing.SwingUtilities

/**
 * Host-neutral adapter from the pure completion engine to Swing suggestions.
 *
 * The adapter reads [contextProvider] for every request so hosts can publish
 * current profile and working-directory state without rebuilding the engine.
 *
 * @param engine pure progressive completion engine. This adapter does not select
 * a dispatcher; the owning suggestion caller controls its coroutine context.
 * @param contextProvider supplier for an immutable snapshot of current host-owned
 * metadata. [open] captures it synchronously on the EDT before background work.
 * Direct [suggestions] callers capture it in their own calling context.
 * @param sourceLabels exact source identifiers mapped to host-owned display labels.
 * Copied at construction; blank labels are rejected. Labels are trimmed and bounded
 * to 128 UTF-16 units without splitting a surrogate pair. Unknown identifiers use
 * the neutral built-in label or a humanized identifier. Source identity and ranking
 * are unaffected. No host callback is invoked during adaptation.
 * @param feedbackHandler observer captured with every opened source. Provider
 * replacement never redirects feedback from an already opened request.
 */
public class SwingCompletionSuggestionProvider
    @JvmOverloads
    constructor(
        private val engine: TerminalCompletionEngine,
        private val contextProvider: () -> SwingCompletionContext,
        sourceLabels: Map<String, String>,
        private val feedbackHandler: SwingShellSuggestionFeedbackHandler = SwingShellSuggestionFeedbackHandler.NONE,
    ) : SwingShellSuggestionProvider {
        private val sourceLabels =
            sourceLabels.mapValues { (_, label) ->
                require(label.isNotBlank()) { "source display labels must not be blank" }
                label.trim().boundedSourceLabel()
            }

        /** Creates an adapter with neutral source labels and optional live host context. */
        @JvmOverloads
        public constructor(
            engine: TerminalCompletionEngine,
            contextProvider: () -> SwingCompletionContext = { SwingCompletionContext.EMPTY },
            feedbackHandler: SwingShellSuggestionFeedbackHandler = SwingShellSuggestionFeedbackHandler.NONE,
        ) : this(engine, contextProvider, emptyMap(), feedbackHandler)

        /** Captures host context and feedback on the EDT; engine work starts only on collection. */
        override fun open(request: SwingShellSuggestionRequest): SwingShellSuggestionSource {
            check(SwingUtilities.isEventDispatchThread()) { "completion source must be opened on the EDT" }
            val context = contextProvider()
            return SwingShellSuggestionSource(suggestions(request, context), feedbackHandler)
        }

        /**
         * Returns progressive candidate snapshots adapted to the reusable Swing popup contract.
         *
         * @param request command text and cursor captured before provider work.
         * @return cold ordered Swing suggestion snapshots, or one empty snapshot when conversion is invalid.
         */
        override fun suggestions(request: SwingShellSuggestionRequest): Flow<List<SwingShellSuggestion>> =
            suggestions(request, contextProvider())

        private fun suggestions(
            request: SwingShellSuggestionRequest,
            requestContext: SwingCompletionContext,
        ): Flow<List<SwingShellSuggestion>> {
            val completionRequest =
                try {
                    TerminalCompletionRequest(
                        commandLine = request.commandText,
                        cursorOffset = request.cursorOffset,
                        workingDirectoryUri = requestContext.workingDirectoryUri,
                        profileId = requestContext.profileId,
                        shellCapabilities = requestContext.shellCapabilities,
                    )
                } catch (_: IllegalArgumentException) {
                    return flowOf(emptyList())
                }
            return flow {
                engine.completions(completionRequest).collect { candidates ->
                    emit(candidates.map { it.toSwingSuggestion(requestContext) })
                }
            }
        }

        private fun TerminalCompletionCandidate.toSwingSuggestion(requestContext: SwingCompletionContext): SwingShellSuggestion =
            SwingShellSuggestion(
                replacementText = replacementText,
                replacementStartOffset = replacementStartOffset,
                replacementEndOffset = replacementEndOffset,
                source = source,
                sourceDisplayText = sourceLabels[source] ?: source.toDisplayText(),
                kind = kind.name,
                displayText = displayText,
                detail = detail,
                accentRole = SwingShellSuggestionAccentRole.from(kind.name, source),
                interactionContext = requestContext,
                feedbackToken = feedbackToken,
                matchedRanges =
                    SwingShellSuggestionMatchRanges.fromPackedOffsets(
                        displayText,
                        matchedRanges.copyPackedOffsets(),
                    ),
            )

        private companion object {
            private fun String.toDisplayText(): String {
                val normalized = trim().boundedSourceLabel().lowercase(Locale.ROOT).boundedSourceLabel()
                return SOURCE_DISPLAY_TEXT[normalized]
                    ?: run {
                        normalized
                            .humanizeSourceIdentifier()
                            .replaceFirstChar { character -> character.titlecase(Locale.ROOT) }
                            .boundedSourceLabel()
                    }
            }

            private fun String.humanizeSourceIdentifier(): String {
                val result = StringBuilder(length.coerceAtMost(MAXIMUM_SOURCE_LABEL_CODE_UNITS))
                var separatorPending = false
                for (character in this) {
                    if (character == '-' || character == '_' || character.isWhitespace()) {
                        separatorPending = result.isNotEmpty()
                    } else {
                        if (separatorPending) result.append(' ')
                        result.append(character)
                        separatorPending = false
                    }
                }
                return result.toString().ifEmpty { messages.message("source.other") }
            }

            private fun String.boundedSourceLabel(): String {
                if (length <= MAXIMUM_SOURCE_LABEL_CODE_UNITS) return this
                var retainedLength = MAXIMUM_SOURCE_LABEL_CODE_UNITS - ELLIPSIS.length
                if (
                    Character.isHighSurrogate(this[retainedLength - 1]) && Character.isLowSurrogate(this[retainedLength])
                ) {
                    retainedLength--
                }
                return substring(0, retainedLength).trimEnd() + ELLIPSIS
            }

            private const val MAXIMUM_SOURCE_LABEL_CODE_UNITS = 128
            private const val ELLIPSIS = "…"
            private val messages = SwingHostMessages.forLocale()
            private val SOURCE_DISPLAY_TEXT =
                mapOf(
                    "spec" to messages.message("source.builtIn"),
                    "learned" to messages.message("source.learned"),
                    "observed" to messages.message("source.learned"),
                    "path" to messages.message("source.path"),
                )
        }
    }

/**
 * Immutable host metadata attached to a Swing completion request.
 *
 * @property profileId stable host profile id, or `null` when unknown.
 * @property workingDirectoryUri current authoritative working-directory URI.
 * @property shellCapabilities explicit shell lexical and replacement policy.
 */
public data class SwingCompletionContext
    @JvmOverloads
    constructor(
        val profileId: String? = null,
        val workingDirectoryUri: String? = null,
        val shellCapabilities: TerminalShellCapabilities = TerminalShellCapabilities.PLAIN,
    ) {
        public companion object {
            /** Empty context for hosts without profile or directory metadata. */
            @JvmField
            public val EMPTY: SwingCompletionContext = SwingCompletionContext()
        }
    }
